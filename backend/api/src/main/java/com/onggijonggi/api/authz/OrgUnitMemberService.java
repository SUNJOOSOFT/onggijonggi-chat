package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.AuthorizationAudit;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : OrgUnitMemberService.java
 * Description : 03·CORE 사람의 팀·직급 배정(org_unit_mbr)을 바꾸는 유일한 경로다. CSV 임포트와 데모 배정 화면이 모두
 *               이 서비스를 부른다 — 어느 쪽으로 넣든 결과와 이력이 같아야 한다.
 *               배정과 이력(authz_adt)은 한 트랜잭션이다. 여러 건을 한 번에 넣으면 하나라도 실패할 때 전부 되돌린다.
 *               겸직이 없어 사람(subject)당 배정은 하나다. 사람 배정은 Casbin에 넣지 않으므로(판정 때 DB에서 읽는다)
 *               바꾼 뒤 Casbin을 다시 적재하지 않는다. 블로킹(JPA)이라 WebFlux에서는 boundedElastic에서 부른다.
 */
@Service
public class OrgUnitMemberService {

	private final OrgUnitMemberRepository members;
	private final OrgUnitRepository orgUnits;
	private final AuthorizationAuditRepository audits;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactions;

	public OrgUnitMemberService(OrgUnitMemberRepository members, OrgUnitRepository orgUnits, AuthorizationAuditRepository audits,
			ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
		this.members = members;
		this.orgUnits = orgUnits;
		this.audits = audits;
		this.objectMapper = objectMapper;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** 누가 바꾸는가. userId가 null이면 SYSTEM(CSV 임포트)이다. requestId는 한 번의 실행을 묶는다(CSV 파일 해시 등). */
	public record Actor(UUID userId, String requestId) {

		public static Actor system(String requestId) {
			return new Actor(null, requestId);
		}

		public static Actor user(UUID userId, String requestId) {
			return new Actor(userId, requestId);
		}
	}

	/** 배정 한 건. orgUnitId가 null이면 배정 해제다. */
	public record Change(String subject, UUID orgUnitId, Rank rank) {

		public static Change assign(String subject, UUID orgUnitId, Rank rank) {
			return new Change(subject, orgUnitId, rank);
		}

		public static Change unassign(String subject) {
			return new Change(subject, null, null);
		}
	}

	public enum Outcome {
		ASSIGNED, CHANGED, UNASSIGNED, UNCHANGED
	}

	public record Result(String subject, Outcome outcome) {
	}

	/** 배정 대상이 없거나 비활성인 팀, 한 번에 같은 사람을 두 번 넣는 것 같은 입력 오류. 이때는 아무것도 바뀌지 않는다. */
	public static class InvalidChangeException extends RuntimeException {

		public InvalidChangeException(String message) {
			super(message);
		}
	}

	public Result apply(Change change, Actor actor) {
		return applyAll(List.of(change), actor).get(0);
	}

	/** 모두 한 트랜잭션이다. 하나라도 실패하면 전부 되돌린다. 결과는 입력 순서다. */
	public List<Result> applyAll(List<Change> changes, Actor actor) {
		Set<String> subjects = new HashSet<>();
		for (Change change : changes) {
			if (change.subject() == null || change.subject().isBlank()) throw new InvalidChangeException("subject가 비어 있다");
			if (!subjects.add(change.subject())) throw new InvalidChangeException("같은 사람이 두 번 있다: " + change.subject());
			if (change.orgUnitId() != null && change.rank() == null) throw new InvalidChangeException("직급이 없다: " + change.subject());
		}
		return transactions.execute(status -> {
			List<Result> results = new ArrayList<>();
			for (Change change : changes) results.add(applyOne(change, actor));
			return results;
		});
	}

	private Result applyOne(Change change, Actor actor) {
		Optional<OrgUnitMember> current = members.findBySubject(change.subject()).stream().findFirst();
		if (change.orgUnitId() == null) {
			if (current.isEmpty()) return new Result(change.subject(), Outcome.UNCHANGED);
			OrgUnitMember member = current.get();
			Map<String, Object> before = snapshot(member.getOrgUnitId(), member.getRank());
			members.delete(member);
			members.flush();
			record(member.getTenantId(), actor, AuthorizationAuditEventKind.MEMBER_UNASSIGNED, change.subject(), before, null);
			return new Result(change.subject(), Outcome.UNASSIGNED);
		}
		OrgUnit unit = orgUnits.findById(change.orgUnitId())
				.orElseThrow(() -> new InvalidChangeException("없는 팀이다: " + change.orgUnitId()));
		if (unit.getStatus() != OrgUnitStatus.ACTIVE) throw new InvalidChangeException("비활성 팀에는 배정할 수 없다: " + unit.getKey());
		if (current.isEmpty()) {
			members.saveAndFlush(new OrgUnitMember(unit.getTenantId(), unit.getId(), change.subject(), change.rank()));
			record(unit.getTenantId(), actor, AuthorizationAuditEventKind.MEMBER_ASSIGNED, change.subject(), null,
					snapshot(unit.getId(), change.rank()));
			return new Result(change.subject(), Outcome.ASSIGNED);
		}
		OrgUnitMember member = current.get();
		if (member.getOrgUnitId().equals(unit.getId()) && member.getRank() == change.rank()) {
			return new Result(change.subject(), Outcome.UNCHANGED);
		}
		Map<String, Object> before = snapshot(member.getOrgUnitId(), member.getRank());
		member.moveTo(unit.getTenantId(), unit.getId(), change.rank());
		members.saveAndFlush(member);
		record(unit.getTenantId(), actor, AuthorizationAuditEventKind.MEMBER_CHANGED, change.subject(), before,
				snapshot(unit.getId(), change.rank()));
		return new Result(change.subject(), Outcome.CHANGED);
	}

	private void record(UUID tenantId, Actor actor, AuthorizationAuditEventKind event, String subject, Map<String, Object> before,
			Map<String, Object> after) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("subj", subject);
		audits.save(AuthorizationAudit.member(tenantId, actor.userId(), event, json(ref), before == null ? null : json(before),
				after == null ? null : json(after), actor.requestId()));
	}

	/** 팀 key도 함께 남긴다 — 나중에 id만으로는 사람이 읽을 수 없다. */
	private Map<String, Object> snapshot(UUID orgUnitId, Rank rank) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("org_unit_id", orgUnitId);
		value.put("org_unit_key", orgUnits.findById(orgUnitId).map(OrgUnit::getKey).orElse(null));
		value.put("rank", rank.name());
		return value;
	}

	private String json(Object value) {
		return objectMapper.writeValueAsString(value);
	}
}
