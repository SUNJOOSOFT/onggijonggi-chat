package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : ThreadApprovalService.java
 * Description : 협업방에서 한 참여자가 다른 참여자에게 방 안의 메시지에 대한 승인을 요청하고, 지정된 사람이 승인·반려하는
 *               요청 건(thr_apv)을 처리한다(#407). 호출부는 boundedElastic에서 실행한다.
 *               방 접근 확인은 ThreadDocumentService.access와 같은 순서로 하고 협업방(COLLAB)에서만 동작한다.
 *               요청 건은 요청자와 지정된 사람에게만 보인다.
 */
@Service
public class ThreadApprovalService {
	/** thr_apv.rsn의 길이 제약과 같은 값이다 — 한쪽만 바꾸면 DB가 거절한다. */
	public static final int MAX_REASON_LENGTH = 500;
	private static final String VIEW_SELECT = """
			select a.id, a.thr_id, a.msg_id, a.req_user_id, a.trg_user_id, r.keycloak_subj as req_subj,
			  t.keycloak_subj as trg_subj, a.status, a.rsn, a.created_at, a.completed_at
			from thr_apv a
			join app_user r on r.id = a.req_user_id
			join app_user t on t.id = a.trg_user_id
			""";
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ThrRepository threads;
	private final AppUserRepository users;
	private final WorkspaceAuthorizer authorizer;
	private final ApprovalNoticeService notices;
	@PersistenceContext private EntityManager entityManager;

	public ThreadApprovalService(JdbcTemplate jdbc, PlatformTransactionManager manager, ThrRepository threads,
			AppUserRepository users, WorkspaceAuthorizer authorizer, ApprovalNoticeService notices) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
		this.threads = threads;
		this.users = users;
		this.authorizer = authorizer;
		this.notices = notices;
	}

	private record Access(Thr thread) {
		boolean writable() { return thread.getStatus() == ThrStatus.ACTIVE; }
	}

	private record Row(UUID id, UUID thread, UUID message, UUID requester, UUID target, String requesterSubject,
			String targetSubject, String status, String reason, Instant created, Instant completed) { }

	/** notice는 이번 호출로 상태가 실제로 바뀐 때만 있다 — 재전송이나 같은 결정의 반복은 null이다. */
	private record Outcome(ThreadApprovalView view, ApprovalNotice notice) { }

	private Access access(UUID threadId, CurrentActor actor, boolean write) {
		Thr thread = threads.findById(threadId).orElseThrow(ThreadApprovalService::notFound);
		if (thread.getTenantId() == null) throw notFound();
		jdbc.queryForList("select id from tnn where id = ? for share", thread.getTenantId());
		if (jdbc.queryForList("select id from thr where id = ? for share", threadId).isEmpty()) throw notFound();
		entityManager.refresh(thread);
		if (users.findById(actor.userId()).filter(user -> user.getStatus() == AppUserStatus.ACTIVE).isEmpty())
			throw notFound();
		// 참여 행을 잠가 요청·처리와 참여 종료가 서로 앞서 확정되지 않게 한다.
		if (notActiveMember(threadId, actor.userId())) throw notFound();
		if (thread.getKind() != ThrKind.COLLAB) throw notFound();
		if (!authorizer.canViewBlocking(actor.subject(), thread.getWorkspaceNodeId())) throw notFound();
		if (write && thread.getStatus() != ThrStatus.ACTIVE) throw conflict();
		return new Access(thread);
	}

	private boolean notActiveMember(UUID threadId, UUID userId) {
		return jdbc.queryForList("select id from thr_mbr where thr_id = ? and user_id = ? and status = 'ACTIVE' for share",
				threadId, userId).isEmpty();
	}

	/**
	 * 같은 approvalId로 다시 보내면 새 요청을 만들지 않고 처음 만든 건을 돌려준다. 같은 id가 다른 내용으로 오면 충돌이다.
	 * 승인은 방 안의 사람·AI 메시지에 대해서만 요청할 수 있고, 시스템 줄과 아직 쓰는 중인 답변은 대상이 아니다.
	 * 같은 원글에 처리 전인 요청이 있으면 새 요청은 거절한다(#423). 승인이나 반려로 끝난 원글은 다시 요청할 수 있다.
	 */
	public ThreadApprovalView request(UUID threadId, UUID approvalId, UUID messageId, String targetSubject,
			CurrentActor actor) {
		return announce(threadId, transactions.execute(tx -> {
			Access access = access(threadId, actor, true);
			AppUser target = users.findByKeycloakSubj(targetSubject)
					.filter(user -> user.getStatus() == AppUserStatus.ACTIVE).orElseThrow(ThreadApprovalService::badTarget);
			if (target.getId().equals(actor.userId())) throw badTarget();
			if (notActiveMember(threadId, target.getId())) throw badTarget();
			if (!authorizer.canViewBlocking(targetSubject, access.thread().getWorkspaceNodeId())) throw badTarget();
			List<String[]> message = jdbc.query("select ath_kind, status from msg where id = ? and thr_id = ?",
					(rs, row) -> new String[] {rs.getString(1), rs.getString(2)}, messageId, threadId);
			if (message.isEmpty()) throw notFound();
			if (message.get(0)[0].equals("SYSTEM") || !message.get(0)[1].equals("COMPLETE")) throw badMessage();
			// 같은 원글의 요청을 이 잠금으로 한 줄로 세운다. 먼저 들어온 요청이 커밋되면 뒤의 요청은 잠금을 얻은 뒤 그 건을 보고
			// 거절된다. KEY SHARE와 겹치지 않는 NO KEY UPDATE라 thr_apv 삽입의 외래키 확인이나 다른 표의 같은 행 참조를 막지 않는다.
			jdbc.queryForList("select id from msg where id = ? and thr_id = ? for no key update", messageId, threadId);
			// 같은 id의 재전송은 아래 삽입이 기존 건을 돌려주므로 대기 요청 확인에서 뺀다.
			if (Boolean.TRUE.equals(jdbc.queryForObject(
					"select exists(select 1 from thr_apv where msg_id = ? and status = 'PENDING' and id <> ?)", Boolean.class,
					messageId, approvalId))) throw ThreadApprovalException.alreadyPending();
			// 중복 id는 예외 대신 무시한다 — 유니크 위반은 PostgreSQL에서 트랜잭션을 중단시켜 바로 뒤의 조회까지 막는다.
			int inserted = jdbc.update("""
					insert into thr_apv(id, tnn_id, thr_id, msg_id, req_user_id, trg_user_id) values (?, ?, ?, ?, ?, ?)
					on conflict (id) do nothing""", approvalId, access.thread().getTenantId(), threadId, messageId,
					actor.userId(), target.getId());
			Row row = find(approvalId);
			if (inserted == 0 && !sameRequest(row, threadId, messageId, actor.userId(), target.getId())) throw conflict();
			ApprovalNotice notice = inserted == 1 ? new ApprovalNotice(ApprovalNotice.Event.REQUESTED, approvalId,
					row.requesterSubject(), row.targetSubject(), row.message(), null) : null;
			return new Outcome(view(row, actor, access), notice);
		}));
	}

	/**
	 * 지정된 사람만 처리할 수 있고, 지정되지 않은 사람에게는 건의 존재도 알리지 않는다(404). 이미 처리된 건에 같은 결정을
	 * 다시 보내면 현재 값을 돌려주고, 다른 결정이면 충돌이다. 사유는 반려할 때만 받는다.
	 */
	public ThreadApprovalView decide(UUID threadId, UUID approvalId, boolean approved, String reason, CurrentActor actor) {
		String note = reason == null || reason.isBlank() ? null : reason.strip();
		if (note != null && (approved || note.length() > MAX_REASON_LENGTH))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		return announce(threadId, transactions.execute(tx -> {
			Access access = access(threadId, actor, true);
			// 같은 건을 동시에 처리하는 요청을 이 잠금으로 한 줄로 세운다. 잠근 뒤의 상태로 멱등·충돌을 판정한다.
			List<UUID> locked = jdbc.queryForList("select trg_user_id from thr_apv where id = ? and thr_id = ? for update",
					UUID.class, approvalId, threadId);
			if (locked.isEmpty() || !locked.get(0).equals(actor.userId())) throw notFound();
			Row current = find(approvalId);
			String wanted = approved ? "APPROVED" : "REJECTED";
			ApprovalNotice notice = null;
			if (current.status().equals("PENDING")) {
				jdbc.update("update thr_apv set status = ?, rsn = ?, completed_at = now() where id = ?", wanted, note, approvalId);
				notice = new ApprovalNotice(ApprovalNotice.Event.valueOf(wanted), approvalId, current.requesterSubject(),
						current.targetSubject(), current.message(), note);
			} else if (!current.status().equals(wanted)) {
				throw conflict();
			}
			return new Outcome(view(find(approvalId), actor, access), notice);
		}));
	}

	/** 요청·처리가 커밋된 뒤에만 줄과 알림을 남긴다 — 상태가 바뀌지 않은 호출(notice가 null)은 방에 아무것도 알리지 않는다. */
	private ThreadApprovalView announce(UUID threadId, Outcome outcome) {
		if (outcome.notice() != null) notices.recordBlocking(threadId, outcome.notice());
		return outcome.view();
	}

	/** 이 사람이 요청했거나 지정된 건만 오래된 순으로 돌려준다. 잠기거나 보관된 방도 읽을 수 있다. */
	public List<ThreadApprovalView> list(UUID threadId, CurrentActor actor) {
		return transactions.execute(tx -> {
			Access access = access(threadId, actor, false);
			return jdbc.query(VIEW_SELECT + " where a.thr_id = ? and (a.req_user_id = ? or a.trg_user_id = ?) order by a.created_at, a.id",
					this::row, threadId, actor.userId(), actor.userId()).stream().map(row -> view(row, actor, access)).toList();
		});
	}

	private Row find(UUID approvalId) {
		return jdbc.query(VIEW_SELECT + " where a.id = ?", this::row, approvalId).stream().findFirst()
				.orElseThrow(ThreadApprovalService::notFound);
	}

	private Row row(ResultSet rs, int index) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getObject("thr_id", UUID.class), rs.getObject("msg_id", UUID.class),
				rs.getObject("req_user_id", UUID.class), rs.getObject("trg_user_id", UUID.class), rs.getString("req_subj"),
				rs.getString("trg_subj"), rs.getString("status"), rs.getString("rsn"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
	}

	private static boolean sameRequest(Row row, UUID threadId, UUID messageId, UUID requester, UUID target) {
		return row.thread().equals(threadId) && row.message().equals(messageId) && row.requester().equals(requester)
				&& row.target().equals(target);
	}

	private static ThreadApprovalView view(Row row, CurrentActor actor, Access access) {
		boolean canDecide = access.writable() && row.status().equals("PENDING") && row.target().equals(actor.userId());
		return new ThreadApprovalView(row.id(), row.message(), row.requesterSubject(), row.targetSubject(), row.status(),
				row.reason(), row.created(), row.completed(), canDecide);
	}

	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
	private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
	/** 지정한 사람이 이 방에서 승인할 수 없는 사람이다(본인·비참여자·권한 없음·없는 사용자). */
	private static ResponseStatusException badTarget() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "target"); }
	private static ResponseStatusException badMessage() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "message"); }
}
