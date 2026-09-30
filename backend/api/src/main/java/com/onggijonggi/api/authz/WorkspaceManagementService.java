package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.AuthorizationAudit;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.AuthorizationAuditTargetKind;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
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
import com.onggijonggi.common.authz.WorkspaceRole;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : WorkspaceManagementService.java
 * Description : 03·CORE Workspace 노드·부여·org-unit 변경과 협업방 이동(#260). 연산마다 Tenant 행을 먼저 잠그고
 *               (같은 Tenant의 권한 변경은 직렬), DB 변경과 authz_adt 감사를 한 트랜잭션에서 함께 성공·롤백한다.
 *               커밋 뒤 RbacPolicyRefresh로 Casbin 반영과 구독 해제를 한다. 배포 설정이 선언한 노드·부여·org-unit은 바꾸지 않는다
 *               (바꾸면 다음 bootstrap이 drift로 Tenant를 막는다). 권한은 DirectManageAuthorizer로 대상 노드마다 직접 본다.
 */
@Service
public class WorkspaceManagementService {
	private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,62}");
	/** wrk_node_guard의 깊이 제한(path 원소 수 11 이하)과 같다. 넘기면 DB가 거부해 500이 되므로 먼저 409로 막는다. */
	private static final int MAX_DEPTH = 11;
	private final TenantRepository tenants;
	private final WorkspaceNodeRepository nodes;
	private final WorkspaceGrantRepository grants;
	private final RankGrantRepository rankGrants;
	private final OrgUnitRepository orgUnits;
	private final OrgUnitMemberRepository members;
	private final AppUserRepository users;
	private final ThrRepository threads;
	private final AuthorizationAuditRepository audits;
	private final DirectManageAuthorizer manage;
	private final RbacBootstrapConfigReader config;
	private final RbacPolicyRefresh refresh;
	private final EntityManager entityManager;
	private final ObjectMapper json;
	private final TransactionTemplate transactions;

	/**
	 * 관리 연산의 행위자. requestId는 한 요청이 남긴 감사 행을 묶고, traceId는 응답의 X-Trace-Id와 같아 오류·로그와 감사를
	 * 이어 준다(TraceIdWebFilter가 발급한다).
	 */
	public record Actor(UUID userId, String subject, List<String> roles, String requestId, String traceId) {
		/** 요청 밖(테스트 등)에서 부를 때. 추적 ID는 없다. */
		public Actor(UUID userId, String subject, List<String> roles, String requestId) {
			this(userId, subject, roles, requestId, null);
		}
	}

	public record RankGrantView(UUID id, UUID orgUnitId, Rank rank, WorkspaceRole role, boolean declared) { }

	public WorkspaceManagementService(TenantRepository tenants, WorkspaceNodeRepository nodes,
			WorkspaceGrantRepository grants, RankGrantRepository rankGrants, OrgUnitRepository orgUnits,
			OrgUnitMemberRepository members,
			AppUserRepository users, ThrRepository threads, AuthorizationAuditRepository audits,
			DirectManageAuthorizer manage, RbacBootstrapConfigReader config, RbacPolicyRefresh refresh,
			EntityManager entityManager, ObjectMapper json, PlatformTransactionManager transactionManager) {
		this.tenants = tenants;
		this.nodes = nodes;
		this.grants = grants;
		this.rankGrants = rankGrants;
		this.orgUnits = orgUnits;
		this.members = members;
		this.users = users;
		this.threads = threads;
		this.audits = audits;
		this.manage = manage;
		this.config = config;
		this.refresh = refresh;
		this.entityManager = entityManager;
		this.json = json;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * key는 요청으로 받지 않고 서버가 만든다. key는 Tenant 전체에서 유일해 요청자가 정하게 하면, 만들어 보고 409가 나는지로
	 * 볼 수 없는 노드의 key(사람이 지은 이름이라 내용이 드러난다)를 떠볼 수 있다. 사람이 읽는 key가 필요한 것은 배포 설정이
	 * 선언하는 노드뿐이고, API로 만든 노드는 id로 식별한다.
	 */
	public UUID createNode(Actor actor, UUID parentId, WorkspaceNodeKind kind, String name) {
		validName(name);
		if (kind != WorkspaceNodeKind.ORG && kind != WorkspaceNodeKind.WORK) throw badRequest();
		UUID tenantId = preauthorized(actor, required(parentId), true);
		UUID result = transactions.execute(status -> {
			lock(tenantId);
			WorkspaceNode parent = node(parentId, tenantId);
			manage.require(actor.subject(), parent);
			if (parent.getKind() == WorkspaceNodeKind.COMMON || parent.getPath().length + 1 > MAX_DEPTH) throw conflict();
			uniqueSiblingName(parentId, name, null);
			WorkspaceNode created = nodes.saveAndFlush(WorkspaceNode.child(tenantId, parentId, parent.getPath(),
					generatedKey(tenantId), kind, name, WorkspaceNodeStatus.ACTIVE));
			UUID actorOrgUnit = members.findBySubject(actor.subject()).stream()
					.filter(member -> tenantId.equals(member.getTenantId()))
					.map(member -> member.getOrgUnitId())
					.filter(id -> orgUnits.findById(id).filter(unit -> unit.getStatus() == OrgUnitStatus.ACTIVE).isPresent())
					.findFirst().orElseThrow(WorkspaceManagementService::forbidden);
			WorkspaceGrant initial = grants.saveAndFlush(new WorkspaceGrant(tenantId, actorOrgUnit, created.getId(), WorkspaceRole.ADMIN));
			audit(actor, tenantId, AuthorizationAuditEventKind.NODE_CREATED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(created), created.getId(), null, nodeSnapshot(created));
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					policyRef(initial), created.getId(), null, grantSnapshot(initial));
			return created.getId();
		});
		refresh.publish(tenantId, Set.of(result), null);
		return result;
	}

	public void renameNode(Actor actor, UUID nodeId, String name) {
		validName(name);
		UUID tenantId = preauthorized(actor, nodeId, true);
		transactions.executeWithoutResult(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			manage.require(actor.subject(), current);
			undeclaredNode(declared(tenant), current);
			uniqueSiblingName(current.getParentId(), name, nodeId);
			Map<String, Object> before = nodeSnapshot(current);
			current.rename(name);
			nodes.saveAndFlush(current);
			audit(actor, tenantId, AuthorizationAuditEventKind.NODE_RENAMED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(current), nodeId, before, nodeSnapshot(current));
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public void deactivateSubtree(Actor actor, UUID nodeId) {
		UUID tenantId = preauthorized(actor, nodeId, false);
		Set<UUID> affected = transactions.execute(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceNode target = node(nodeId, tenantId);
			// 권한부터 본다 — 상태·종류를 먼저 보면 권한 없는 사람도 409/403 차이로 노드 상태를 알 수 있다.
			manage.requireIgnoringStatus(actor.subject(), target);
			if (target.getKind() == WorkspaceNodeKind.ROOT || target.getKind() == WorkspaceNodeKind.COMMON
					|| target.getStatus() != WorkspaceNodeStatus.ACTIVE) throw conflict();
			List<WorkspaceNode> subtree = nodes.findByTenantId(tenantId).stream()
					.filter(candidate -> candidate.getStatus() == WorkspaceNodeStatus.ACTIVE
							&& Arrays.asList(candidate.getPath()).contains(nodeId))
					.sorted(Comparator.comparingInt((WorkspaceNode candidate) -> candidate.getPath().length).reversed()).toList();
			Optional<RbacBootstrapSpec.TenantSpec> declared = declared(tenant);
			for (WorkspaceNode current : subtree) {
				manage.require(actor.subject(), current);
				undeclaredNode(declared, current);
				if (threads.existsByWorkspaceNodeId(current.getId())) throw conflict();
			}
			for (WorkspaceNode current : subtree) {
				Map<String, Object> before = nodeSnapshot(current);
				current.reconcileStatus(WorkspaceNodeStatus.INACTIVE);
				nodes.saveAndFlush(current);
				audit(actor, tenantId, AuthorizationAuditEventKind.NODE_DEACTIVATED, AuthorizationAuditTargetKind.WORKSPACE,
						nodeRef(current), current.getId(), before, nodeSnapshot(current));
			}
			return subtree.stream().map(WorkspaceNode::getId).collect(Collectors.toSet());
		});
		refresh.publish(tenantId, affected, null);
	}

	public void reactivateNode(Actor actor, UUID nodeId) {
		UUID tenantId = preauthorized(actor, nodeId, false);
		transactions.executeWithoutResult(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			// 권한부터 본다. 비활성 노드 자체의 보존된 직접 부여로 판정하고, 부모의 MANAGE로 대신하지 않는다(상속 없음).
			manage.requireIgnoringStatus(actor.subject(), current);
			if (current.getStatus() != WorkspaceNodeStatus.INACTIVE
					|| current.getKind() == WorkspaceNodeKind.ROOT || current.getKind() == WorkspaceNodeKind.COMMON) throw conflict();
			WorkspaceNode parent = node(current.getParentId(), tenantId);
			if (parent.getStatus() != WorkspaceNodeStatus.ACTIVE) throw conflict();
			undeclaredNode(declared(tenant), current);
			uniqueSiblingName(parent.getId(), current.getName(), nodeId);
			Map<String, Object> before = nodeSnapshot(current);
			current.reconcileStatus(WorkspaceNodeStatus.ACTIVE);
			nodes.saveAndFlush(current);
			audit(actor, tenantId, AuthorizationAuditEventKind.NODE_REACTIVATED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(current), nodeId, before, nodeSnapshot(current));
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public void reparentLeaf(Actor actor, UUID nodeId, UUID newParentId) {
		required(newParentId);
		UUID tenantId = preauthorized(actor, nodeId, true);
		transactions.executeWithoutResult(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			// 권한부터 본다(세 노드 모두 직접 MANAGE). ROOT에는 팀 부여가 없어 보통 여기서 403이다.
			manage.require(actor.subject(), current);
			// 배포 설정이 ROOT에 직급 규칙을 걸면 권한이 통과할 수 있다 — ROOT는 부모가 없어 옮길 수 없으므로 409로 막는다.
			if (current.getKind() == WorkspaceNodeKind.ROOT) throw conflict();
			WorkspaceNode oldParent = node(current.getParentId(), tenantId);
			WorkspaceNode newParent = node(newParentId, tenantId);
			manage.require(actor.subject(), oldParent);
			manage.require(actor.subject(), newParent);
			if (oldParent.getKind() == WorkspaceNodeKind.ROOT || newParent.getKind() == WorkspaceNodeKind.ROOT
					|| newParent.getKind() == WorkspaceNodeKind.COMMON || current.getStatus() != WorkspaceNodeStatus.ACTIVE
					|| newParent.getStatus() != WorkspaceNodeStatus.ACTIVE || oldParent.getId().equals(newParentId)
					|| nodeId.equals(newParentId) || newParent.getPath().length + 1 > MAX_DEPTH
					|| nodes.existsByParentId(nodeId) || threads.existsByWorkspaceNodeId(nodeId)) throw conflict();
			Optional<RbacBootstrapSpec.TenantSpec> declared = declared(tenant);
			undeclaredNode(declared, current);
			uniqueSiblingName(newParentId, current.getName(), nodeId);
			List<WorkspaceGrant> previous = grants.findByWorkspaceNodeId(nodeId);
			for (WorkspaceGrant grant : previous) {
				undeclaredGrant(declared, tenantId, grant);
				if (orgUnits.findById(grant.getOrgUnitId())
						.filter(unit -> unit.getStatus() == OrgUnitStatus.ACTIVE).isEmpty()) throw conflict();
			}
			if (previous.stream().noneMatch(grant -> grant.getRole() == WorkspaceRole.ADMIN)) throw conflict();
			Map<String, Object> before = nodeSnapshot(current);
			// DB guard는 부모를 바꾸는 UPDATE 순간 그 노드의 직접 부여가 0건이길 요구한다. 같은 트랜잭션에서 지웠다가 되살린다.
			entityManager.flush();
			for (WorkspaceGrant grant : previous) {
				entityManager.createNativeQuery("delete from wrk_grn where id = :id")
						.setParameter("id", grant.getId()).executeUpdate();
			}
			current.moveTo(newParentId, newParent.getPath());
			nodes.saveAndFlush(current);
			for (WorkspaceGrant grant : previous) {
				// 되살린 부여는 원래 생성 시각을 지킨다 — 이동이 부여를 새로 만든 것처럼 보이지 않게 한다.
				entityManager.createNativeQuery("insert into wrk_grn (id, tnn_id, org_unit_id, wrk_node_id, role, created_at, updated_at) "
						+ "values (:id, :tenant, :org, :node, :role, :createdAt, now())")
						.setParameter("id", grant.getId()).setParameter("tenant", tenantId)
						.setParameter("org", grant.getOrgUnitId()).setParameter("node", nodeId)
						.setParameter("role", grant.getRole().name()).setParameter("createdAt", grant.getCreatedAt()).executeUpdate();
			}
			Map<String, Object> after = nodeSnapshot(current);
			// 부여는 같은 구성으로 되살렸으므로 전후가 같다 — 이동이 부여를 바꾸지 않았다는 기록이다.
			List<Map<String, Object>> restoredGrants = previous.stream().map(WorkspaceManagementService::grantSnapshot).toList();
			before.put("grants", restoredGrants);
			after.put("grants", restoredGrants);
			audit(actor, tenantId, AuthorizationAuditEventKind.NODE_REPARENTED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(current), nodeId, before, after);
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public UUID addGrant(Actor actor, UUID nodeId, UUID orgUnitId, WorkspaceRole role) {
		if (role == null) throw badRequest();
		required(orgUnitId);
		UUID tenantId = preauthorized(actor, nodeId, true);
		UUID id = transactions.execute(status -> {
			lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			manage.require(actor.subject(), current);
			if (current.getKind() == WorkspaceNodeKind.ROOT) throw badRequest();
			if (orgUnits.findById(orgUnitId).filter(value -> tenantId.equals(value.getTenantId())
					&& value.getStatus() == OrgUnitStatus.ACTIVE).isEmpty()) throw conflict();
			if (hasGrant(nodeId, orgUnitId, role, null)) throw conflict();
			WorkspaceGrant grant = grants.saveAndFlush(new WorkspaceGrant(tenantId, orgUnitId, nodeId, role));
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant), nodeId, null, grantSnapshot(grant));
			return grant.getId();
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
		return id;
	}

	public void changeGrantRole(Actor actor, UUID grantId, WorkspaceRole role) {
		if (role == null) throw badRequest();
		UUID tenantId = preauthorizedGrant(actor, grantId);
		UUID nodeId = transactions.execute(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceGrant grant = grant(grantId, tenantId);
			WorkspaceNode node = node(grant.getWorkspaceNodeId(), tenantId);
			manage.require(actor.subject(), node);
			undeclaredGrant(declared(tenant), tenantId, grant);
			if (requiredCommonGrant(node, grant)) throw conflict();
			if (grant.getRole() == role) return node.getId();
			if (hasGrant(node.getId(), grant.getOrgUnitId(), role, grantId)) throw conflict();
			if (grant.getRole() == WorkspaceRole.ADMIN && role != WorkspaceRole.ADMIN) requireRemainingAdmin(node.getId(), grantId);
			Map<String, Object> before = grantSnapshot(grant);
			grant.changeRole(role);
			grants.saveAndFlush(grant);
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_REPLACED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant), node.getId(), before, grantSnapshot(grant));
			return node.getId();
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public void removeGrant(Actor actor, UUID grantId) {
		UUID tenantId = preauthorizedGrant(actor, grantId);
		UUID nodeId = transactions.execute(status -> {
			Tenant tenant = lock(tenantId);
			WorkspaceGrant grant = grant(grantId, tenantId);
			WorkspaceNode node = node(grant.getWorkspaceNodeId(), tenantId);
			manage.require(actor.subject(), node);
			undeclaredGrant(declared(tenant), tenantId, grant);
			if (requiredCommonGrant(node, grant)) throw conflict();
			if (grant.getRole() == WorkspaceRole.ADMIN) requireRemainingAdmin(node.getId(), grantId);
			Map<String, Object> before = grantSnapshot(grant);
			grants.delete(grant);
			grants.flush();
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_REMOVED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant), node.getId(), before, null);
			return node.getId();
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	public List<RankGrantView> listRankGrants(Actor actor, UUID nodeId) {
		UUID tenantId = preauthorized(actor, nodeId, true);
		Tenant tenant = tenants.findById(tenantId).orElseThrow(WorkspaceManagementService::notFound);
		Optional<RbacBootstrapSpec.TenantSpec> specification = declared(tenant);
		WorkspaceNode node = node(nodeId, tenantId);
		List<RankGrant> rules = rankGrants.findByWorkspaceNodeId(nodeId);
		Map<UUID, String> orgKeys = new HashMap<>();
		if (specification.isPresent()) {
			List<UUID> orgIds = rules.stream().map(RankGrant::getOrgUnitId).filter(Objects::nonNull).distinct().toList();
			for (OrgUnit unit : orgUnits.findAllById(orgIds)) orgKeys.put(unit.getId(), unit.getKey());
		}
		return rules.stream()
				.map(grant -> new RankGrantView(grant.getId(), grant.getOrgUnitId(), grant.getRank(), grant.getRole(),
						specification.isPresent() && isDeclaredRankGrant(specification, node, grant,
								grant.getOrgUnitId() == null ? null : orgKey(orgKeys, grant.getOrgUnitId()))))
				.sorted(Comparator.comparingInt((RankGrantView view) -> view.rank().order()).thenComparing(RankGrantView::id))
				.toList();
	}

	public UUID addRankGrant(Actor actor, UUID nodeId, UUID orgUnitId, Rank rank, WorkspaceRole role) {
		if (rank == null || role == null) throw badRequest();
		UUID tenantId = preauthorized(actor, nodeId, true);
		UUID id = transactions.execute(status -> {
			lock(tenantId);
			WorkspaceNode current = node(nodeId, tenantId);
			manage.require(actor.subject(), current);
			if (current.getKind() == WorkspaceNodeKind.ROOT) throw badRequest();
			if (orgUnitId != null && orgUnits.findById(orgUnitId).filter(unit -> tenantId.equals(unit.getTenantId())
					&& unit.getStatus() == OrgUnitStatus.ACTIVE).isEmpty()) throw conflict();
			if (hasRankGrant(nodeId, orgUnitId, rank, role, null)) throw conflict();
			RankGrant grant = rankGrants.saveAndFlush(new RankGrant(tenantId, nodeId, orgUnitId, rank, role));
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					rankPolicyRef(grant), nodeId, null, rankGrantSnapshot(grant));
			return grant.getId();
		});
		refresh.publish(tenantId, Set.of(), null);
		return id;
	}

	public void changeRankGrantRank(Actor actor, UUID grantId, Rank rank) {
		if (rank == null) throw badRequest();
		changeRankGrant(actor, grantId, rank, null);
	}

	public void changeRankGrantRole(Actor actor, UUID grantId, WorkspaceRole role) {
		if (role == null) throw badRequest();
		changeRankGrant(actor, grantId, null, role);
	}

	private void changeRankGrant(Actor actor, UUID grantId, Rank rank, WorkspaceRole role) {
		UUID tenantId = preauthorizedRankGrant(actor, grantId);
		RankGrantChange change = transactions.execute(status -> {
			Tenant tenant = lock(tenantId);
			RankGrant grant = rankGrant(grantId, tenantId);
			WorkspaceNode node = node(grant.getWorkspaceNodeId(), tenantId);
			manage.require(actor.subject(), node);
			undeclaredRankGrant(declared(tenant), node, grant);
			Rank nextRank = rank == null ? grant.getRank() : rank;
			WorkspaceRole nextRole = role == null ? grant.getRole() : role;
			if (nextRank == grant.getRank() && nextRole == grant.getRole()) return null;
			if (hasRankGrant(node.getId(), grant.getOrgUnitId(), nextRank, nextRole, grantId)) throw conflict();
			boolean reduced = nextRank.order() < grant.getRank().order() || nextRole.ordinal() < grant.getRole().ordinal();
			Map<String, Object> before = rankGrantSnapshot(grant);
			if (rank != null) grant.changeRank(rank);
			else grant.changeRole(role);
			rankGrants.saveAndFlush(grant);
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_REPLACED, AuthorizationAuditTargetKind.POLICY,
					rankPolicyRef(grant), node.getId(), before, rankGrantSnapshot(grant));
			return new RankGrantChange(node.getId(), reduced);
		});
		if (change != null) refresh.publish(tenantId, change.reduced() ? Set.of(change.nodeId()) : Set.of(), null);
	}

	public void removeRankGrant(Actor actor, UUID grantId) {
		UUID tenantId = preauthorizedRankGrant(actor, grantId);
		UUID nodeId = transactions.execute(status -> {
			Tenant tenant = lock(tenantId);
			RankGrant grant = rankGrant(grantId, tenantId);
			WorkspaceNode node = node(grant.getWorkspaceNodeId(), tenantId);
			manage.require(actor.subject(), node);
			undeclaredRankGrant(declared(tenant), node, grant);
			Map<String, Object> before = rankGrantSnapshot(grant);
			rankGrants.delete(grant);
			rankGrants.flush();
			audit(actor, tenantId, AuthorizationAuditEventKind.POLICY_REMOVED, AuthorizationAuditTargetKind.POLICY,
					rankPolicyRef(grant), node.getId(), before, null);
			return node.getId();
		});
		refresh.publish(tenantId, Set.of(nodeId), null);
	}

	private record RankGrantChange(UUID nodeId, boolean reduced) { }

	public void moveCollabThread(Actor actor, UUID threadId, UUID destinationId) {
		required(destinationId);
		Thr existing = threads.findById(threadId).orElseThrow(WorkspaceManagementService::notFound);
		UUID tenantId = existing.getTenantId();
		// 협업방이 아니거나 Tenant·워크스페이스가 없으면 404다(없는 방과 같게). 그 다음에야 원 노드 권한을 잠금 전에 본다.
		if (tenantId == null || existing.getKind() != ThrKind.COLLAB || existing.getWorkspaceNodeId() == null) throw notFound();
		preauthorized(actor, existing.getWorkspaceNodeId(), true);
		transactions.executeWithoutResult(status -> {
			lock(tenantId);
			// 행 잠금으로 읽는다 — 잠금 없이 읽은 값을 그대로 저장하면 그사이 메시지 채번이 올린 next_seq를 옛 값으로 되돌린다.
			Thr thread = threads.findByIdForSeqUpdate(threadId).orElseThrow(WorkspaceManagementService::notFound);
			if (thread.getKind() != ThrKind.COLLAB || !tenantId.equals(thread.getTenantId())
					|| thread.getWorkspaceNodeId() == null) throw notFound();
			WorkspaceNode source = node(thread.getWorkspaceNodeId(), tenantId);
			WorkspaceNode destination = node(destinationId, tenantId);
			manage.require(actor.subject(), source);
			manage.require(actor.subject(), destination);
			if (destination.getKind() == WorkspaceNodeKind.ROOT || source.getId().equals(destinationId)) throw conflict();
			thread.moveToWorkspace(destinationId);
			threads.saveAndFlush(thread);
			Map<String, Object> before = Map.of("thread_id", threadId, "wrk_node_id", source.getId());
			Map<String, Object> after = Map.of("thread_id", threadId, "wrk_node_id", destinationId);
			for (UUID nodeId : List.of(source.getId(), destinationId)) {
				audit(actor, tenantId, AuthorizationAuditEventKind.THREAD_MOVED, AuthorizationAuditTargetKind.THREAD,
						Map.of("thread_id", threadId), nodeId, before, after);
			}
		});
		refresh.publish(tenantId, Set.of(), threadId);
	}

	public UUID createOrgUnit(Actor actor, String tenantKey, String key, String name) {
		validKey(key);
		validName(name);
		UUID tenantId = tenants.findByKey(tenantKey).orElseThrow(WorkspaceManagementService::notFound).getId();
		UUID orgUnitId = transactions.execute(status -> {
			Tenant tenant = lock(tenantKey);
			undeclaredOrgUnit(declared(tenant), key);
			if (orgUnits.findByTenantIdAndKey(tenant.getId(), key).isPresent()) throw conflict();
			OrgUnit unit = orgUnits.saveAndFlush(new OrgUnit(tenant.getId(), key, name, OrgUnitStatus.ACTIVE));
			WorkspaceNode common = nodes.findByTenantIdAndKey(tenant.getId(), "common")
					.orElseThrow(WorkspaceManagementService::conflict);
			WorkspaceGrant grant = grants.saveAndFlush(new WorkspaceGrant(tenant.getId(), unit.getId(),
					common.getId(), WorkspaceRole.VIEWER));
			audit(actor, tenant.getId(), AuthorizationAuditEventKind.ORG_UNIT_CREATED, AuthorizationAuditTargetKind.ORG_UNIT,
					orgUnitRef(unit), null, null, orgSnapshot(unit));
			audit(actor, tenant.getId(), AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant), common.getId(), null, grantSnapshot(grant));
			return unit.getId();
		});
		refresh.publish(tenantId, Set.of(), null);
		return orgUnitId;
	}

	public void changeOrgUnit(Actor actor, String tenantKey, UUID orgUnitId, String name, OrgUnitStatus targetStatus) {
		// 상태 변경이 아니면 이름 변경이다 — 이름이 비면 400이다(그대로 두면 NOT NULL 위반이 500이 된다).
		if (targetStatus == null || name != null) validName(name);
		UUID tenantId = tenants.findByKey(tenantKey).orElseThrow(WorkspaceManagementService::notFound).getId();
		Set<UUID> affected = transactions.execute(status -> {
			Tenant tenant = lock(tenantKey);
			OrgUnit unit = orgUnits.findById(orgUnitId).filter(value -> value.getTenantId().equals(tenant.getId()))
					.orElseThrow(WorkspaceManagementService::notFound);
			undeclaredOrgUnit(declared(tenant), unit.getKey());
			// app_user가 없는 배정(아직 로그인하지 않은 사람)도 배정된 사용자로 센다 — 비활성화하면 그 사람이 막힌다.
			if (targetStatus == OrgUnitStatus.INACTIVE && members.findByOrgUnitId(unit.getId()).stream()
					.anyMatch(member -> users.findByKeycloakSubj(member.getSubject())
									.map(user -> user.getStatus() == AppUserStatus.ACTIVE).orElse(true))) throw conflict();
			// 이 팀이 어떤 노드의 마지막 ACTIVE ADMIN이면 비활성화할 수 없다 — 그 노드는 아무도 관리하지 못하게 되고, 노드를
			// 관리하는 API는 일반 ADMIN에게만 있어 되살릴 길이 없다. 다른 팀에 ADMIN을 먼저 주고 비활성화한다.
			if (targetStatus == OrgUnitStatus.INACTIVE && grants.findByOrgUnitId(unit.getId()).stream()
					.filter(grant -> grant.getRole() == WorkspaceRole.ADMIN)
					.anyMatch(grant -> !activeAdminRemains(grant.getWorkspaceNodeId(),
							other -> other.getOrgUnitId().equals(unit.getId())))) throw conflict();
			Map<String, Object> before = orgSnapshot(unit);
			AuthorizationAuditEventKind event;
			if (targetStatus != null) {
				if (unit.getStatus() == targetStatus) throw conflict();
				unit.reconcileStatus(targetStatus);
				event = targetStatus == OrgUnitStatus.ACTIVE ? AuthorizationAuditEventKind.ORG_UNIT_REACTIVATED
						: AuthorizationAuditEventKind.ORG_UNIT_DEACTIVATED;
			} else {
				unit.rename(name);
				event = AuthorizationAuditEventKind.ORG_UNIT_RENAMED;
			}
			orgUnits.saveAndFlush(unit);
			audit(actor, tenant.getId(), event, AuthorizationAuditTargetKind.ORG_UNIT,
					orgUnitRef(unit), null, before, orgSnapshot(unit));
			return grants.findByOrgUnitId(orgUnitId).stream().map(WorkspaceGrant::getWorkspaceNodeId).collect(Collectors.toSet());
		});
		refresh.publish(tenantId, affected, null);
	}

	/**
	 * Tenant 행을 잠그기 전에 읽기만으로 대상 노드의 직접 MANAGE를 먼저 본다. 권한 없는 요청이 잠금을 잡아 같은 Tenant의 관리 쓰기를
	 * 줄 세우지 못하게 하기 위해서다. 잠금 안에서 같은 판정을 다시 한다(그 사이 부여가 바뀌었을 수 있다). activeOnly가 false면
	 * 노드 상태와 무관하게 본다(비활성화·재활성화). 돌려주는 값은 그 노드의 Tenant다.
	 */
	private UUID preauthorized(Actor actor, UUID nodeId, boolean activeOnly) {
		WorkspaceNode target = nodes.findById(nodeId).orElseThrow(WorkspaceManagementService::notFound);
		if (activeOnly) manage.require(actor.subject(), target);
		else manage.requireIgnoringStatus(actor.subject(), target);
		return target.getTenantId();
	}
	private UUID preauthorizedGrant(Actor actor, UUID grantId) {
		WorkspaceGrant target = grants.findById(grantId).orElseThrow(WorkspaceManagementService::notFound);
		return preauthorized(actor, target.getWorkspaceNodeId(), true);
	}
	private UUID preauthorizedRankGrant(Actor actor, UUID grantId) {
		RankGrant target = rankGrants.findById(grantId).orElseThrow(WorkspaceManagementService::notFound);
		return preauthorized(actor, target.getWorkspaceNodeId(), true);
	}
	private Tenant lock(UUID tenantId) {
		Tenant current = tenants.findById(tenantId).orElseThrow(WorkspaceManagementService::notFound);
		return lock(current.getKey());
	}
	private Tenant lock(String tenantKey) {
		return tenants.findByKeyForUpdate(tenantKey).filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE)
				.orElseThrow(WorkspaceManagementService::notFound);
	}
	private WorkspaceNode node(UUID id, UUID tenantId) {
		return nodes.findById(id).filter(value -> value.getTenantId().equals(tenantId))
				.orElseThrow(WorkspaceManagementService::notFound);
	}
	private WorkspaceGrant grant(UUID id, UUID tenantId) {
		return grants.findById(id).filter(value -> value.getTenantId().equals(tenantId))
				.orElseThrow(WorkspaceManagementService::notFound);
	}
	private RankGrant rankGrant(UUID id, UUID tenantId) {
		return rankGrants.findById(id).filter(value -> value.getTenantId().equals(tenantId))
				.orElseThrow(WorkspaceManagementService::notFound);
	}
	/**
	 * 배포 설정이 이 Tenant에 선언한 내용. 설정 파일을 읽고 파싱하므로 연산마다 한 번만 부르고 결과를 넘긴다 — 한 요청 안에서
	 * 파일이 바뀌어 판정이 갈리지도 않는다. 설정이 없거나 이 Tenant를 선언하지 않았으면 비어 있다.
	 */
	private Optional<RbacBootstrapSpec.TenantSpec> declared(Tenant tenant) {
		return config.loadWithFingerprint().stream().flatMap(loaded -> loaded.spec().tenants().stream())
				.filter(spec -> spec.key().equals(tenant.getKey())).findFirst();
	}
	private static void undeclaredNode(Optional<RbacBootstrapSpec.TenantSpec> declared, WorkspaceNode node) {
		if (declared.stream().flatMap(spec -> spec.nodes().stream()).anyMatch(spec -> spec.key().equals(node.getKey()))) {
			throw conflict();
		}
	}
	private static void undeclaredOrgUnit(Optional<RbacBootstrapSpec.TenantSpec> declared, String key) {
		if (declared.stream().flatMap(spec -> spec.orgUnits().stream()).anyMatch(spec -> spec.key().equals(key))) {
			throw conflict();
		}
	}
	private void undeclaredGrant(Optional<RbacBootstrapSpec.TenantSpec> declared, UUID tenantId, WorkspaceGrant grant) {
		if (declared.isEmpty()) return;
		String nodeKey = node(grant.getWorkspaceNodeId(), tenantId).getKey();
		String orgKey = orgUnits.findById(grant.getOrgUnitId()).orElseThrow(WorkspaceManagementService::conflict).getKey();
		if (declared.get().grants().stream().anyMatch(spec -> spec.node().equals(nodeKey) && spec.orgUnit().equals(orgKey)
				&& spec.role().equals(grant.getRole().name()))) throw conflict();
	}
	private void undeclaredRankGrant(Optional<RbacBootstrapSpec.TenantSpec> declared, WorkspaceNode node,
			RankGrant grant) {
		if (isDeclaredRankGrant(declared, node, grant)) throw conflict();
	}
	private boolean isDeclaredRankGrant(Optional<RbacBootstrapSpec.TenantSpec> declared, WorkspaceNode node,
			RankGrant grant) {
		if (declared.isEmpty()) return false;
		String orgKey = grant.getOrgUnitId() == null ? null : orgUnits.findById(grant.getOrgUnitId())
				.orElseThrow(WorkspaceManagementService::conflict).getKey();
		return isDeclaredRankGrant(declared, node, grant, orgKey);
	}
	private static boolean isDeclaredRankGrant(Optional<RbacBootstrapSpec.TenantSpec> declared, WorkspaceNode node,
			RankGrant grant, String orgKey) {
		return declared.get().rankGrants().stream().anyMatch(spec -> spec.node().equals(node.getKey())
				&& Objects.equals(spec.orgUnit(), orgKey) && spec.rank().equals(grant.getRank().name())
				&& spec.role().equals(grant.getRole().name()));
	}
	private static String orgKey(Map<UUID, String> orgKeys, UUID orgUnitId) {
		String key = orgKeys.get(orgUnitId);
		if (key == null) throw conflict();
		return key;
	}
	private boolean hasRankGrant(UUID nodeId, UUID orgUnitId, Rank rank, WorkspaceRole role, UUID except) {
		return rankGrants.findByWorkspaceNodeId(nodeId).stream().anyMatch(grant ->
				Objects.equals(grant.getOrgUnitId(), orgUnitId) && grant.getRank() == rank && grant.getRole() == role
						&& !grant.getId().equals(except));
	}
	/** 같은 노드·org-unit·역할의 부여가 이미 있나(except는 자기 자신). 있으면 uq_wrk_grn_policy 위반이 500이 되므로 먼저 409로 막는다. */
	private boolean hasGrant(UUID nodeId, UUID orgUnitId, WorkspaceRole role, UUID except) {
		return grants.findByWorkspaceNodeId(nodeId).stream().anyMatch(grant -> grant.getOrgUnitId().equals(orgUnitId)
				&& grant.getRole() == role && !grant.getId().equals(except));
	}
	/**
	 * 지우거나 낮출 부여 말고도 ACTIVE org-unit의 직접 ADMIN이 남아 있어야 한다. 비활성 팀의 ADMIN은 판정에 쓰이지 않으므로
	 * 남은 관리자로 세지 않는다 — 세면 실제로는 아무도 관리하지 못하는 노드가 남는다.
	 */
	private void requireRemainingAdmin(UUID nodeId, UUID removedId) {
		if (!activeAdminRemains(nodeId, grant -> grant.getId().equals(removedId))) throw conflict();
	}
	/** excluded에 해당하는 부여를 빼고도 그 노드에 ACTIVE org-unit의 직접 ADMIN이 남나. */
	private boolean activeAdminRemains(UUID nodeId, Predicate<WorkspaceGrant> excluded) {
		return grants.findByWorkspaceNodeId(nodeId).stream()
				.anyMatch(grant -> !excluded.test(grant) && grant.getRole() == WorkspaceRole.ADMIN
						&& orgUnits.findById(grant.getOrgUnitId()).filter(unit -> unit.getStatus() == OrgUnitStatus.ACTIVE)
								.isPresent());
	}
	/** API로 만드는 노드의 key. node_key 형식(소문자로 시작, 64자 이하)을 따르고 Tenant 안에서 겹치지 않게 다시 뽑는다. */
	private String generatedKey(UUID tenantId) {
		for (int attempt = 0; attempt < 5; attempt++) {
			String key = "n-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
			if (nodes.findByTenantIdAndKey(tenantId, key).isEmpty()) return key;
		}
		throw conflict();
	}
	private boolean requiredCommonGrant(WorkspaceNode node, WorkspaceGrant grant) {
		return node.getKind() == WorkspaceNodeKind.COMMON && grant.getRole() == WorkspaceRole.VIEWER;
	}
	/** ACTIVE 형제끼리 이름이 대소문자 무시로 유일해야 한다(DB의 lower(name) 부분 유일 인덱스와 같다). */
	private void uniqueSiblingName(UUID parentId, String name, UUID except) {
		if (nodes.findByParentId(parentId).stream().anyMatch(candidate -> candidate.getStatus() == WorkspaceNodeStatus.ACTIVE
				&& !Objects.equals(candidate.getId(), except) && candidate.getName().equalsIgnoreCase(name))) throw conflict();
	}
	private void audit(Actor actor, UUID tenantId, AuthorizationAuditEventKind event, AuthorizationAuditTargetKind target,
			Map<String, Object> ref, UUID nodeId, Map<String, Object> before, Map<String, Object> after) {
		audits.save(AuthorizationAudit.managed(tenantId, actor.userId(), json.writeValueAsString(actor.roles()), event,
				target, json.writeValueAsString(ref), nodeId, before == null ? null : json.writeValueAsString(before),
				after == null ? null : json.writeValueAsString(after), actor.requestId(), actor.traceId()));
	}
	// 대상 참조와 스냅샷은 bootstrap(RbacBootstrapService)이 남기는 SYSTEM 행과 같은 키·모양으로 쓴다 — 감사 조회(#259)를 읽는
	// 쪽이 행위자가 SYSTEM이든 USER이든 같은 키로 읽게 하고, id만으로는 사람이 읽을 수 없어 key를 함께 남긴다.
	private static Map<String, Object> nodeRef(WorkspaceNode node) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("wrk_node_id", node.getId());
		ref.put("node_key", node.getKey());
		return ref;
	}
	private Map<String, Object> policyRef(WorkspaceGrant grant) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("wrk_grn_id", grant.getId());
		ref.put("org_unit_key", orgUnits.findById(grant.getOrgUnitId()).map(OrgUnit::getKey).orElse(null));
		ref.put("role", grant.getRole().name());
		ref.put("wrk_node_id", grant.getWorkspaceNodeId());
		return ref;
	}
	private Map<String, Object> rankPolicyRef(RankGrant grant) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("rank_grn_id", grant.getId());
		ref.put("org_unit_key", grant.getOrgUnitId() == null ? null : orgUnits.findById(grant.getOrgUnitId())
				.orElseThrow(WorkspaceManagementService::conflict).getKey());
		ref.put("rank", grant.getRank().name());
		ref.put("role", grant.getRole().name());
		ref.put("wrk_node_id", grant.getWorkspaceNodeId());
		return ref;
	}
	private static Map<String, Object> orgUnitRef(OrgUnit unit) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("org_unit_key", unit.getKey());
		return ref;
	}
	private static Map<String, Object> nodeSnapshot(WorkspaceNode node) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", node.getId());
		snapshot.put("node_key", node.getKey());
		snapshot.put("kind", node.getKind().name());
		snapshot.put("prn_id", node.getParentId());
		snapshot.put("name", node.getName());
		snapshot.put("status", node.getStatus().name());
		snapshot.put("path", Arrays.stream(node.getPath()).map(UUID::toString).toList());
		snapshot.put("inactive_at", node.getInactiveAt());
		return snapshot;
	}
	private static Map<String, Object> grantSnapshot(WorkspaceGrant grant) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", grant.getId());
		snapshot.put("tnn_id", grant.getTenantId());
		snapshot.put("org_unit_id", grant.getOrgUnitId());
		snapshot.put("role", grant.getRole().name());
		snapshot.put("wrk_node_id", grant.getWorkspaceNodeId());
		return snapshot;
	}
	private static Map<String, Object> rankGrantSnapshot(RankGrant grant) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", grant.getId());
		snapshot.put("tnn_id", grant.getTenantId());
		snapshot.put("org_unit_id", grant.getOrgUnitId());
		snapshot.put("rank", grant.getRank().name());
		snapshot.put("role", grant.getRole().name());
		snapshot.put("wrk_node_id", grant.getWorkspaceNodeId());
		return snapshot;
	}
	private static Map<String, Object> orgSnapshot(OrgUnit unit) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", unit.getId());
		snapshot.put("org_unit_key", unit.getKey());
		snapshot.put("name", unit.getName());
		snapshot.put("status", unit.getStatus().name());
		snapshot.put("inactive_at", unit.getInactiveAt());
		return snapshot;
	}
	private static void validKey(String value) {
		if (value == null || !KEY.matcher(value).matches()) throw badRequest();
	}
	private static void validName(String value) {
		if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.strip())
				|| value.chars().anyMatch(Character::isISOControl)) throw badRequest();
	}
	private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
	private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
	private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN); }
	private static ResponseStatusException conflict() { return new RbacStateConflictException(); }
	/** 요청 본문의 필수 id가 비었으면 400이다 — 그대로 조회에 넘기면 IllegalArgumentException이 500이 된다. */
	private static UUID required(UUID id) {
		if (id == null) throw badRequest();
		return id;
	}
}
