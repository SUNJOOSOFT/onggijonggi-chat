package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : WorkspaceAuthorizer.java
 * Description : 03·CORE "이 사람이 이 workspace에서 이 액션을 할 수 있나" 판정 API.
 *               권한 판정(app.rbac.enforce)이 꺼져 있으면 늘 true다 — 끄면 지금과 똑같이 동작해야 한다.
 *               켜져 있으면(#299):
 *               - COMMON도 예외가 없다. 모든 ACTIVE org-unit이 COMMON VIEWER 부여를 가지므로 소속이 있는 사람은
 *                 Casbin 판정으로 COMMON을 보고, 소속이 없는 사람은 COMMON도 못 본다.
 *               - 배정(org_unit_mbr)이 없으면 Casbin에 묻지 않고 거부한다 — 서열 0 같은 값으로 넘기면 서열 규칙이 열린다.
 *               - Casbin에 묻기 전에 DB로 확인한다: 배정의 Tenant와 대상 노드의 Tenant가 같고, 그 Tenant와 배정된
 *                 org-unit이 ACTIVE여야 한다. Casbin model에는 Tenant 차원이 없어서 이 검사가 Tenant 격리를 맡는다
 *                 (팀을 정하지 않은 직급 규칙이 다른 Tenant의 같은 직급을 통과시키지 않게 한다).
 *               - 배정마다 팀·서열을 속성으로 넘겨 묻고 하나라도 통과하면 허용한다. 지금은 겸직이 없어 배정이 늘 하나지만,
 *                 겸직이 생겨도 이 모양 그대로 쓴다.
 *               - Casbin 오류·시간 초과·적재 실패는 모두 거부다.
 *               DB와 gRPC가 블로킹이라 boundedElastic에서 돈다.
 */
@Service
public class WorkspaceAuthorizer {

	private final RbacProperties rbacProperties;
	private final WorkspaceNodeRepository nodes;
	private final OrgUnitMemberRepository members;
	private final TenantRepository tenants;
	private final OrgUnitRepository orgUnits;
	private final CasbinRuleLoader loader;
	private final CasbinClient client;
	private final ObjectMapper objectMapper;
	private final RbacPolicyRefresh policyRefresh;

	public WorkspaceAuthorizer(RbacProperties rbacProperties, WorkspaceNodeRepository nodes, OrgUnitMemberRepository members,
			TenantRepository tenants, OrgUnitRepository orgUnits, CasbinRuleLoader loader, CasbinClient client,
			ObjectMapper objectMapper, RbacPolicyRefresh policyRefresh) {
		this.rbacProperties = rbacProperties;
		this.nodes = nodes;
		this.members = members;
		this.tenants = tenants;
		this.orgUnits = orgUnits;
		this.loader = loader;
		this.client = client;
		this.objectMapper = objectMapper;
		this.policyRefresh = policyRefresh;
	}

	public Mono<Boolean> canView(String subject, UUID workspaceNodeId) {
		return authorize(subject, workspaceNodeId, CasbinPolicy.VIEW);
	}

	/** COLLAB Thread 생성 시 확인한다(#265) — COMMON도 예외 없이 이 판정을 그대로 탄다. */
	public Mono<Boolean> canCreateThread(String subject, UUID workspaceNodeId) {
		return authorize(subject, workspaceNodeId, CasbinPolicy.THREAD_CREATE);
	}

	public Mono<Boolean> canManage(String subject, UUID workspaceNodeId) {
		return authorize(subject, workspaceNodeId, CasbinPolicy.MANAGE);
	}

	/**
	 * 검색 결과를 워크스페이스 경로로 거를 때 쓴다 — 판정이 꺼져 있으면 {@link AccessibleNodePaths#noRestriction()}를
	 * 돌려줘 호출부가 필터를 아예 안 걸게 한다. Tenant 안의 ACTIVE 노드마다 VIEW를 묻는 방식이라, 노드 수가 많아지면
	 * 비용이 늘어난다 — 지금은 노드 수가 문서 수보다 훨씬 적을 것으로 보고 이 모양을 쓴다.
	 */
	public Mono<AccessibleNodePaths> accessibleNodePaths(String subject, UUID tenantId) {
		if (!rbacProperties.isEnforce()) return Mono.just(AccessibleNodePaths.noRestriction());
		return Mono.fromCallable(() -> AccessibleNodePaths.restrictedTo(accessibleNodePathsBlocking(subject, tenantId)))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** workspaceNodeId가 null(워크스페이스가 정해지지 않은 방)이면 판정이 켜져 있을 때 어떤 액션이든 거부다. */
	private Mono<Boolean> authorize(String subject, UUID workspaceNodeId, String action) {
		if (!rbacProperties.isEnforce()) return Mono.just(true);
		if (workspaceNodeId == null) return Mono.just(false);
		return Mono.fromCallable(() -> authorizeBlocking(subject, workspaceNodeId, action)).subscribeOn(Schedulers.boundedElastic());
	}

	private boolean authorizeBlocking(String subject, UUID workspaceNodeId, String action) {
		Optional<WorkspaceNode> node = nodes.findById(workspaceNodeId);
		if (node.isEmpty() || node.get().getStatus() != WorkspaceNodeStatus.ACTIVE) return false;
		UUID tenantId = node.get().getTenantId();
		if (policyRefresh.isBlocked(tenantId) || !isActiveTenant(tenantId)) return false;
		List<String> attributesList = activeAssignmentAttributes(subject, tenantId);
		if (attributesList.isEmpty()) return false;
		loader.ensureLoaded();
		return passesAnyAssignment(attributesList, workspaceNodeId, action);
	}

	private List<UUID[]> accessibleNodePathsBlocking(String subject, UUID tenantId) {
		if (policyRefresh.isBlocked(tenantId) || !isActiveTenant(tenantId)) return List.of();
		List<String> attributesList = activeAssignmentAttributes(subject, tenantId);
		if (attributesList.isEmpty()) return List.of();
		loader.ensureLoaded();
		return nodes.findByTenantId(tenantId).stream()
				.filter(node -> node.getStatus() == WorkspaceNodeStatus.ACTIVE)
				.filter(node -> passesAnyAssignment(attributesList, node.getId(), CasbinPolicy.VIEW))
				.map(WorkspaceNode::getPath)
				.toList();
	}

	private List<String> activeAssignmentAttributes(String subject, UUID tenantId) {
		return members.findBySubject(subject).stream()
				.filter(assignment -> tenantId.equals(assignment.getTenantId()) && isActiveOrgUnit(assignment))
				.map(this::attributes)
				.toList();
	}

	private boolean passesAnyAssignment(List<String> attributesList, UUID workspaceNodeId, String action) {
		for (String attributes : attributesList) {
			if (client.enforce(attributes, workspaceNodeId.toString(), action)) return true;
			// 서버가 재시작해 규칙을 잃었으면 한 번만 다시 넣고 다시 묻는다. 그래도 안 되면 거부다.
			if (!client.isLoaded()) {
				loader.ensureLoaded();
				if (client.enforce(attributes, workspaceNodeId.toString(), action)) return true;
			}
		}
		return false;
	}

	private boolean isActiveTenant(UUID tenantId) {
		return tenants.findById(tenantId).filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE).isPresent();
	}

	/** 배정된 org-unit이 배정과 같은 Tenant에 있고 ACTIVE인가. 비활성 org-unit의 배정은 판정에 쓰지 않는다. */
	private boolean isActiveOrgUnit(OrgUnitMember assignment) {
		return orgUnits.findById(assignment.getOrgUnitId())
				.filter(unit -> unit.isActiveIn(assignment.getTenantId()))
				.isPresent();
	}

	/** r.sub JSON. 서열은 숫자로 넘긴다 — 문자열이면 casbin-server가 비교에서 오류를 낸다. */
	private String attributes(OrgUnitMember assignment) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put(CasbinPolicy.ORG_UNIT, assignment.getOrgUnitId().toString());
		attributes.put(CasbinPolicy.RANK, assignment.getRank().order());
		return objectMapper.writeValueAsString(attributes);
	}
}
