package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.api.chat.CollabThreadCreationService;
import com.onggijonggi.api.chat.DirectChatTurnService;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : CutoverValidationService.java
 * Description : 점검창에서 다시 실행하는 읽기 전용 단일 Tenant·최종 Workspace 사전 검증.
 *               Keycloak은 활성 subject만 제공하고 현재 소속·직급·권한의 정본은 DB다.
 */
@Service
public class CutoverValidationService {

	private final AppUserRepository users;
	private final OrgUnitMemberRepository members;
	private final OrgUnitRepository orgUnits;
	private final TenantRepository tenants;
	private final WorkspaceNodeRepository nodes;
	private final WorkspaceGrantRepository grants;
	private final RankGrantRepository rankGrants;
	private final JdbcTemplate jdbc;
	/** 응답이 끝없이 커지지 않게 하는 상한. 넘으면 FAILURES_TRUNCATED가 붙는다. */
	private static final int MAX_FAILURES = 500;

	public CutoverValidationService(AppUserRepository users, OrgUnitMemberRepository members,
			OrgUnitRepository orgUnits, TenantRepository tenants, WorkspaceNodeRepository nodes,
			WorkspaceGrantRepository grants, RankGrantRepository rankGrants, JdbcTemplate jdbc) {
		this.users = users;
		this.members = members;
		this.orgUnits = orgUnits;
		this.tenants = tenants;
		this.nodes = nodes;
		this.grants = grants;
		this.rankGrants = rankGrants;
		this.jdbc = jdbc;
	}

	/**
	 * 운영자 API용: Keycloak 활성 계정 목록이 비었는데 DB에 사용자가 있으면 목록을 못 받은 것으로 본다.
	 * 목록이 비면 모든 참여 이력이 "비활성 계정"으로 취급돼 계정 검사가 통째로 건너뛰어지므로, 통과로 읽히지 않게 막는다.
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CutoverValidationResult validateWithKeycloak(Collection<String> enabledSubjects) {
		CutoverValidationResult result = validate(enabledSubjects);
		if (!enabledSubjects.isEmpty() || users.count() == 0) return result;
		List<CutoverValidationResult.Failure> failures = new ArrayList<>(result.failures());
		failures.add(failure("ENABLED_SUBJECTS_EMPTY", null, null));
		return new CutoverValidationResult(result.tenantId(), List.copyOf(failures));
	}

	/** 읽기 전용 REPEATABLE_READ — 여러 표를 나눠 읽어도 한 시점의 스냅샷이라, 점검창에 쓰기가 남아 있어도 조합이 어긋나지 않는다.
	 * Keycloak에서 활성인 subject만 받는다. app_user의 로컬 INACTIVE는 재활성화 가능하므로 제외 근거가 아니다. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CutoverValidationResult validate(Collection<String> enabledSubjects) {
		List<Tenant> activeTenants = tenants.findAll().stream()
				.filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE).toList();
		if (activeTenants.size() != 1) return stopped(null, "ACTIVE_TENANT_COUNT");
		UUID tenantId = activeTenants.get(0).getId();
		Map<UUID, WorkspaceNode> nodeById = new HashMap<>();
		for (WorkspaceNode node : nodes.findAll()) nodeById.put(node.getId(), node);
		UUID commonId = activeCommonId(tenantId, nodeById);
		if (commonId == null) return stopped(tenantId, "ACTIVE_COMMON_REQUIRED");
		if (enabledSubjects.stream().anyMatch(subject -> subject == null || subject.isBlank()))
			return stopped(tenantId, "INVALID_ENABLED_SUBJECT");

		Snapshot snapshot = snapshot(tenantId, commonId, new HashSet<>(enabledSubjects));
		// 같은 (코드, Thread, subject)는 한 번만 담는다. 상한을 넘으면 나머지는 버리고 표시만 남긴다.
		Set<CutoverValidationResult.Failure> failures = new LinkedHashSet<>();
		for (String subject : snapshot.enabled()) {
			if (!snapshot.hasValidAssignment(snapshot.assignmentBySubject().get(subject)))
				failures.add(failure("INVALID_ACTIVE_SUBJECT_ASSIGNMENT", null, subject));
		}

		List<ThreadRow> threads = jdbc.query("select id, kind, drc_own_user_id, tnn_id, wrk_node_id from thr order by id",
				(rows, index) -> new ThreadRow(rows.getObject(1, UUID.class), rows.getString(2),
						rows.getObject(3, UUID.class), rows.getObject(4, UUID.class), rows.getObject(5, UUID.class)));
		Map<UUID, ThreadRow> threadById = new HashMap<>();
		for (ThreadRow thread : threads) threadById.put(thread.id(), thread);
		List<Actor> actors = new ArrayList<>(jdbc.query(
				"select thr_id, user_id from thr_mbr where status = 'ACTIVE' order by thr_id, user_id",
				(rows, index) -> new Actor(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class))));
		Set<Actor> ownerMemberships = new HashSet<>(jdbc.query(
				"select thr_id, user_id from thr_mbr where status = 'ACTIVE' and role = ?",
				(rows, index) -> new Actor(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class)), ThrMbrRole.OWNER.name()));

		for (ThreadRow thread : threads) {
			if (thread.tenantId() != null && !tenantId.equals(thread.tenantId()))
				failures.add(failure("THREAD_TENANT_MISMATCH", thread.id(), null));
			boolean direct = ThrKind.DIRECT.name().equals(thread.kind());
			if (direct && thread.workspaceId() != null && !thread.workspaceId().equals(commonId))
				failures.add(failure("DIRECT_OUTSIDE_COMMON", thread.id(), null));
			WorkspaceNode finalNode = nodeById.get(snapshot.finalNodeId(thread));
			if (finalNode == null || !tenantId.equals(finalNode.getTenantId())
					|| finalNode.getStatus() != WorkspaceNodeStatus.ACTIVE || finalNode.getKind() == WorkspaceNodeKind.ROOT)
				failures.add(failure("INVALID_THREAD_WORKSPACE", thread.id(), null));
			if (direct) {
				if (!ownerMemberships.contains(new Actor(thread.id(), thread.directOwner())))
					failures.add(failure("DIRECT_OWNER_MEMBERSHIP_MISSING", thread.id(), null));
				actors.add(new Actor(thread.id(), thread.directOwner()));
			}
		}
		// 재시도할 수 있는 멱등 키와 PENDING 초대의 행위자도 현재 Tenant를 벗어나면 절체를 멈춘다. 키 보존 기간은 생성 서비스의 값을 그대로 쓴다.
		Instant now = Instant.now();
		actors.addAll(jdbc.query("""
				select thr_id, user_id from thr_idm_key where created_at >= ?
				union all select thr_id, user_id from msg_idm_key where created_at >= ?
				union all select thr_id, created_by_user_id from thr_inv where status = 'PENDING'
				""", (rows, index) -> new Actor(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class)),
				Timestamp.from(now.minus(CollabThreadCreationService.IDEMPOTENCY_KEY_TTL)),
				Timestamp.from(now.minus(DirectChatTurnService.IDEMPOTENCY_KEY_TTL))));
		for (Actor actor : actors) {
			ThreadRow thread = threadById.get(actor.threadId());
			if (thread != null) snapshot.checkActor(actor, snapshot.finalNodeId(thread), failures);
		}
		List<CutoverValidationResult.Failure> reported = new ArrayList<>(failures.stream().limit(MAX_FAILURES).toList());
		if (failures.size() > MAX_FAILURES) reported.add(failure("FAILURES_TRUNCATED", null, null));
		return new CutoverValidationResult(tenantId, List.copyOf(reported));
	}

	/** 뒤 검사가 앞 결과에 기대므로 더 진행하지 않고 원인 하나만 알린다. */
	private static CutoverValidationResult stopped(UUID tenantId, String code) {
		return new CutoverValidationResult(tenantId, List.of(failure(code, null, null)));
	}

	/** 그 Tenant의 ACTIVE ROOT 바로 아래 ACTIVE COMMON이 정확히 하나일 때 그 id, 아니면 null. */
	private static UUID activeCommonId(UUID tenantId, Map<UUID, WorkspaceNode> nodeById) {
		List<WorkspaceNode> commons = nodeById.values().stream()
				.filter(node -> node.getTenantId().equals(tenantId) && node.getKind() == WorkspaceNodeKind.COMMON
						&& node.getStatus() == WorkspaceNodeStatus.ACTIVE)
				.filter(node -> {
					WorkspaceNode parent = nodeById.get(node.getParentId());
					return parent != null && parent.getKind() == WorkspaceNodeKind.ROOT
							&& parent.getStatus() == WorkspaceNodeStatus.ACTIVE;
				}).toList();
		return commons.size() == 1 ? commons.get(0).getId() : null;
	}

	private Snapshot snapshot(UUID tenantId, UUID commonId, Set<String> enabled) {
		Map<UUID, String> subjectByUser = new HashMap<>();
		for (AppUser user : users.findAll()) subjectByUser.put(user.getId(), user.getKeycloakSubj());
		Map<UUID, OrgUnit> unitById = new HashMap<>();
		for (OrgUnit unit : orgUnits.findAll()) unitById.put(unit.getId(), unit);
		// 사람당 배정 한 행이 DB 제약이다. 겸직이 생기면 이 표와 아래 판정을 함께 고쳐야 한다.
		Map<String, OrgUnitMember> assignmentBySubject = new HashMap<>();
		for (OrgUnitMember member : members.findAll()) assignmentBySubject.put(member.getSubject(), member);
		Map<UUID, List<WorkspaceGrant>> grantsByNode = new HashMap<>();
		for (WorkspaceGrant grant : grants.findAll())
			grantsByNode.computeIfAbsent(grant.getWorkspaceNodeId(), ignored -> new ArrayList<>()).add(grant);
		Map<UUID, List<RankGrant>> ranksByNode = new HashMap<>();
		for (RankGrant grant : rankGrants.findAll())
			ranksByNode.computeIfAbsent(grant.getWorkspaceNodeId(), ignored -> new ArrayList<>()).add(grant);
		return new Snapshot(tenantId, commonId, enabled, subjectByUser, unitById, assignmentBySubject, grantsByNode, ranksByNode);
	}

	private static CutoverValidationResult.Failure failure(String code, UUID threadId, String subject) {
		return new CutoverValidationResult.Failure(code, threadId, subject);
	}

	/** 한 번의 검증이 읽은 표를 묶은 것. 판정은 모두 이 값으로만 한다. */
	private record Snapshot(UUID tenantId, UUID commonId, Set<String> enabled, Map<UUID, String> subjectByUser,
			Map<UUID, OrgUnit> unitById, Map<String, OrgUnitMember> assignmentBySubject,
			Map<UUID, List<WorkspaceGrant>> grantsByNode, Map<UUID, List<RankGrant>> ranksByNode) {

		/** 놓인 곳이 없으면(절체 전) COMMON이 최종 위치다. */
		UUID finalNodeId(ThreadRow thread) {
			return thread.workspaceId() == null ? commonId : thread.workspaceId();
		}

		/** 배정이 이 Tenant의 ACTIVE org-unit을 가리키나. */
		boolean hasValidAssignment(OrgUnitMember member) {
			if (member == null || !tenantId.equals(member.getTenantId())) return false;
			OrgUnit unit = unitById.get(member.getOrgUnitId());
			return unit != null && unit.isActiveIn(tenantId);
		}

		void checkActor(Actor actor, UUID nodeId, Set<CutoverValidationResult.Failure> failures) {
			String subject = subjectByUser.get(actor.userId());
			if (subject == null) {
				failures.add(failure("UNKNOWN_THREAD_ACTOR", actor.threadId(), null));
				return;
			}
			if (!enabled.contains(subject)) return; // 삭제·비활성 계정의 참여 이력은 보존한다.
			OrgUnitMember member = assignmentBySubject.get(subject);
			if (!hasValidAssignment(member)) {
				failures.add(failure("THREAD_ACTOR_TENANT_MISMATCH", actor.threadId(), subject));
				return;
			}
			// 판정식은 Casbin 정책과 같은 CasbinPolicy.allows다. 여기서는 부여가 같은 Tenant인지만 더 본다.
			boolean direct = grantsByNode.getOrDefault(nodeId, List.of()).stream()
					.anyMatch(grant -> tenantId.equals(grant.getTenantId()) && CasbinPolicy.allows(grant, member, CasbinPolicy.VIEW));
			boolean rank = ranksByNode.getOrDefault(nodeId, List.of()).stream()
					.anyMatch(rule -> tenantId.equals(rule.getTenantId()) && CasbinPolicy.allows(rule, member, CasbinPolicy.VIEW));
			if (!direct && !rank) failures.add(failure("THREAD_ACTOR_VIEW_MISSING", actor.threadId(), subject));
		}
	}

	private record ThreadRow(UUID id, String kind, UUID directOwner, UUID tenantId, UUID workspaceId) {
	}

	/** Thread 안에서 행동하는 사람(참여자, DIRECT 소유자, 재시도 키·초대의 작성자). */
	private record Actor(UUID threadId, UUID userId) {
	}
}
