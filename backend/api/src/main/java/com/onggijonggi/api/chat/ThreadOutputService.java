package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : ThreadOutputService.java
 * Description : 방의 "출력"(문서 생성 워커가 만든 파일, thr_out)을 남기고 목록·파일을 읽는다(#414). 파일 내용은 워커의 저장소에 있고
 *               여기서는 위치와 메타데이터만 다룬다. register는 서버 안에서 부르는 진입점이라 호출하는 쪽(생성 도구)이 이미
 *               방 접근을 확인한 상태로 보고, list·download는 방 문서(ThreadDocumentService.access)와 같은 순서로 방 접근을 확인한다.
 *               호출부는 boundedElastic에서 실행한다.
 */
@Service
public class ThreadOutputService {
	private static final Set<String> FILE_KINDS = Set.of("DOCX", "PDF", "MD");
	private static final int MAX_FILE_NAME_LENGTH = 255;
	private static final int MAX_FILE_KEY_LENGTH = 1024;
	private static final String VIEW_SELECT = """
			select o.id, o.file_name, o.file_kind, o.file_size, o.file_key, u.keycloak_subj, o.created_at
			from thr_out o join app_user u on u.id = o.user_id
			""";
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ThrRepository threads;
	private final ThrMbrRepository memberships;
	private final AppUserRepository users;
	private final WorkspaceAuthorizer authorizer;
	private final ThreadOutputStorage storage;
	@PersistenceContext private EntityManager entityManager;

	public ThreadOutputService(JdbcTemplate jdbc, PlatformTransactionManager manager, ThrRepository threads,
			ThrMbrRepository memberships, AppUserRepository users, WorkspaceAuthorizer authorizer, ThreadOutputStorage storage) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
		this.threads = threads;
		this.memberships = memberships;
		this.users = users;
		this.authorizer = authorizer;
		this.storage = storage;
	}

	private record Row(ThreadOutputView view, String fileKey) { }
	public record Download(String fileName, byte[] bytes) { }

	/**
	 * 생성 결과를 방의 출력으로 남긴다. 같은 requestKey로 다시 부르면 새 건을 만들지 않고 처음 남긴 건을 돌려준다 — 재시도나
	 * 중복 호출에도 출력이 하나만 남는다. 같은 키에 다른 파일이 오면 호출부의 버그라 예외로 드러낸다.
	 */
	public ThreadOutputView register(UUID threadId, UUID requestKey, UUID userId, String fileName, String fileKind,
			long fileSize, String fileKey) {
		if (fileName == null || fileName.isBlank() || fileName.length() > MAX_FILE_NAME_LENGTH
				|| !FILE_KINDS.contains(fileKind) || fileSize <= 0
				|| fileKey == null || fileKey.isBlank() || fileKey.length() > MAX_FILE_KEY_LENGTH)
			throw new IllegalArgumentException("출력 등록 값이 올바르지 않다");
		return transactions.execute(tx -> {
			Thr thread = threads.findById(threadId).orElseThrow(ThreadOutputService::notFound);
			if (thread.getTenantId() == null) throw notFound();
			// 중복 키는 예외 대신 무시한다 — 유니크 위반은 PostgreSQL에서 트랜잭션을 중단시켜 바로 뒤의 조회까지 막는다.
			int inserted = jdbc.update("""
					insert into thr_out(id, tnn_id, thr_id, user_id, file_name, file_kind, file_size, file_key, req_key)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (tnn_id, thr_id, req_key) do nothing""",
					UUID.randomUUID(), thread.getTenantId(), threadId, userId, fileName, fileKind, fileSize, fileKey, requestKey);
			Row row = jdbc.query(VIEW_SELECT + " where o.thr_id = ? and o.req_key = ?",
					(rs, index) -> new Row(view(rs), rs.getString("file_key")), threadId, requestKey).stream().findFirst()
					.orElseThrow(ThreadOutputService::notFound);
			if (inserted == 0 && !row.fileKey().equals(fileKey))
				throw new IllegalStateException("같은 요청 키로 다른 파일이 이미 등록되어 있다");
			return row.view();
		});
	}

	/** 방의 출력을 최신순으로 돌려준다. 방에 들어올 수 없는 사람에게는 방도 출력도 없는 것처럼 404로 답한다. */
	public List<ThreadOutputView> list(UUID threadId, CurrentActor actor) {
		return transactions.execute(tx -> {
			access(threadId, actor);
			return jdbc.query(VIEW_SELECT + " where o.thr_id = ? order by o.created_at desc, o.id desc",
					(rs, index) -> view(rs), threadId);
		});
	}

	/**
	 * 출력 한 건의 파일을 내려준다. 방 접근을 확인한 뒤 그 방의 출력만 찾고, 파일은 트랜잭션 밖에서 워커를 거쳐 읽는다 — 느린
	 * 저장소 호출 동안 방 잠금을 쥐지 않는다. 방에 들어올 수 없거나 그 방의 출력이 아니면 404로 답한다.
	 */
	public Download download(UUID threadId, UUID outputId, CurrentActor actor) {
		record Target(UUID tenant, String fileName, String fileKey) { }
		Target target = transactions.execute(tx -> {
			Thr thread = access(threadId, actor);
			Row row = jdbc.query(VIEW_SELECT + " where o.thr_id = ? and o.id = ?",
					(rs, index) -> new Row(view(rs), rs.getString("file_key")), threadId, outputId).stream().findFirst()
					.orElseThrow(ThreadOutputService::notFound);
			return new Target(thread.getTenantId(), row.view().fileName(), row.fileKey());
		});
		return new Download(target.fileName(), storage.read(target.tenant(), target.fileKey()));
	}

	private static ThreadOutputView view(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new ThreadOutputView(rs.getObject("id", UUID.class), rs.getString("file_name"), rs.getString("file_kind"),
				rs.getLong("file_size"), rs.getString("keycloak_subj"), rs.getTimestamp("created_at").toInstant());
	}

	/**
	 * ThreadDocumentService.access와 같은 순서로 확인한다 — tenant·방 공유 잠금, 활성 사용자, 활성 참여, DIRECT 방의 소유자,
	 * 방의 워크스페이스를 볼 권한. 참여 행을 잠가 출력 접근과 참여 종료가 서로 앞서 확정되지 않게 한다.
	 */
	Thr access(UUID threadId, CurrentActor actor) {
		Thr thread = threads.findById(threadId).orElseThrow(ThreadOutputService::notFound);
		if (thread.getTenantId() == null) throw notFound();
		jdbc.queryForList("select id from tnn where id = ? for share", thread.getTenantId());
		if (jdbc.queryForList("select id from thr where id = ? for share", threadId).isEmpty()) throw notFound();
		entityManager.refresh(thread);
		if (users.findById(actor.userId()).filter(user -> user.getStatus() == AppUserStatus.ACTIVE).isEmpty())
			throw notFound();
		if (jdbc.queryForList("select id from thr_mbr where thr_id = ? and user_id = ? and status = 'ACTIVE' for share",
				threadId, actor.userId()).isEmpty()) throw notFound();
		var member = memberships.findByThrIdAndUserIdAndStatus(threadId, actor.userId(), ThrMbrStatus.ACTIVE)
				.orElseThrow(ThreadOutputService::notFound);
		entityManager.refresh(member);
		boolean owner = member.getRole() == ThrMbrRole.OWNER;
		if (thread.getKind() == ThrKind.DIRECT && (!owner || !actor.userId().equals(thread.getDrcOwnUserId())))
			throw notFound();
		if (!authorizer.canViewBlocking(actor.subject(), thread.getWorkspaceNodeId())) throw notFound();
		return thread;
	}

	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
