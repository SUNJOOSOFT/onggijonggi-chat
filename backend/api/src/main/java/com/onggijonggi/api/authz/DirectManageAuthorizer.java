package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : DirectManageAuthorizer.java
 * Description : 03·CORE "이 사람이 이 workspace에 직접 MANAGE를 갖나"를 판정 스위치와 무관하게 엄격히 본다.
 *               감사 조회(#259)와 Workspace·부여 관리 쓰기(#260)가 함께 쓴다.
 *               WorkspaceAuthorizer는 app.rbac.enforce가 꺼지면 늘 허용하고, 스위치를 켜는 casbin 프로필이 꺼지면
 *               Casbin 서버 자체가 없다. 그래서 Casbin 판정을 거치지 않고 DB의 부여에서 직접 계산한다. 사람 속성만은
 *               Casbin(p2, MemberAttributes)에서 읽는다 — 우리 DB에 없다. 프로필이 꺼져 있으면 속성이 없어 아무도 관리할 수 없다.
 *               선검증은 WorkspaceAuthorizer와 같다: Tenant ACTIVE, 속성이 노드와 같은 Tenant에 있고 그 org-unit이 ACTIVE.
 *               속성을 읽을 수 없으면(적재 전·장애) 관리 권한이 없는 것으로 본다.
 *               판정은 Casbin 규칙과 뜻이 같아야 한다(CasbinPolicy.rules) — 역할→액션은 CasbinPolicy.roleAllows를 쓰고,
 *               직급 규칙은 "팀이 없거나 내 팀이면서 내 서열 숫자 <= 규칙 서열"이다. 부모 노드의 부여는 보지 않는다(상속 없음).
 *               DB가 블로킹이라 호출하는 쪽이 boundedElastic에서 부른다.
 */
@Service
public class DirectManageAuthorizer {

	private final MemberAttributes members;
	private final TenantRepository tenants;
	private final OrgUnitRepository orgUnits;
	private final WorkspaceGrantRepository workspaceGrants;
	private final RankGrantRepository rankGrants;
	private final AppUserRepository users;
	private final RbacPolicyRefresh policyRefresh;
	private final RbacProperties rbacProperties;

	public DirectManageAuthorizer(MemberAttributes members, TenantRepository tenants, OrgUnitRepository orgUnits,
			WorkspaceGrantRepository workspaceGrants, RankGrantRepository rankGrants, AppUserRepository users,
			RbacPolicyRefresh policyRefresh, RbacProperties rbacProperties) {
		this.members = members;
		this.tenants = tenants;
		this.orgUnits = orgUnits;
		this.workspaceGrants = workspaceGrants;
		this.rankGrants = rankGrants;
		this.users = users;
		this.policyRefresh = policyRefresh;
		this.rbacProperties = rbacProperties;
	}

	/** ACTIVE 노드에 직접 MANAGE가 있나. 감사 조회가 쓴다 — 읽기라 Casbin 반영 지연과 무관하다. */
	public boolean hasDirectManage(String subject, WorkspaceNode node) {
		return node.getStatus() == WorkspaceNodeStatus.ACTIVE && manages(subject, node);
	}

	/** 관리 목록의 복구 대상 조회. 비활성 노드도 보존된 직접 부여로 읽되 비활성 계정은 허용하지 않는다. */
	public boolean canReadManagement(String subject, WorkspaceNode node) {
		return users.findByKeycloakSubj(subject).filter(user -> user.getStatus() == AppUserStatus.ACTIVE).isPresent()
				&& manages(subject, node);
	}

	/**
	 * 관리 쓰기용. ACTIVE 노드에 직접 MANAGE가 없으면 403이다. 계정이 비활성이거나, 판정이 켜진 동안 그 Tenant의 커밋된 변경이
	 * 아직 Casbin에 반영되지 못했으면(RbacPolicyRefresh가 막아 둠) 쓰기도 막는다 — 반영되지 않은 규칙 위에 변경을 더 쌓지 않는다.
	 */
	public void require(String subject, WorkspaceNode node) {
		if (node.getStatus() != WorkspaceNodeStatus.ACTIVE) throw forbidden();
		requireWriter(subject, node);
	}

	/**
	 * 노드 상태와 무관하게 그 노드 자체의 직접 부여로 판정한다. 비활성 노드 재활성화(보존된 부여로 판정)와, 상태를 보기 전에 권한부터
	 * 확인해야 하는 연산(비활성화)이 쓴다 — 상태를 먼저 보면 권한 없는 사람도 409/403 차이로 노드 상태를 알 수 있다.
	 * 부모 노드의 권한으로 대신하지 않는다(상속 없음).
	 */
	public void requireIgnoringStatus(String subject, WorkspaceNode node) {
		requireWriter(subject, node);
	}

	private void requireWriter(String subject, WorkspaceNode node) {
		if (rbacProperties.isEnforce() && policyRefresh.isBlocked(node.getTenantId())) throw forbidden();
		if (users.findByKeycloakSubj(subject).filter(user -> user.getStatus() == AppUserStatus.ACTIVE).isEmpty()) {
			throw forbidden();
		}
		if (!manages(subject, node)) throw forbidden();
	}

	/** 노드 상태는 보지 않는다 — 호출하는 쪽이 ACTIVE·INACTIVE 중 무엇을 요구하는지 정한다. */
	private boolean manages(String subject, WorkspaceNode node) {
		if (tenants.findById(node.getTenantId()).filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE).isEmpty()) {
			return false;
		}
		List<MemberAttribute> assignments;
		try {
			assignments = members.findBySubject(subject).stream()
					.filter(assignment -> node.getTenantId().equals(assignment.tenantId()) && isActiveOrgUnit(assignment))
					.toList();
		} catch (MemberAttributesUnavailableException unavailable) {
			return false;
		}
		if (assignments.isEmpty()) return false;
		List<WorkspaceGrant> grants = workspaceGrants.findByWorkspaceNodeId(node.getId());
		List<RankGrant> rankRules = rankGrants.findByWorkspaceNodeId(node.getId());
		return assignments.stream().anyMatch(assignment -> grants.stream().anyMatch(grant -> CasbinPolicy.allows(grant, assignment, CasbinPolicy.MANAGE))
				|| rankRules.stream().anyMatch(rule -> CasbinPolicy.allows(rule, assignment, CasbinPolicy.MANAGE)));
	}

	private boolean isActiveOrgUnit(MemberAttribute assignment) {
		return orgUnits.findById(assignment.orgUnitId())
				.filter(unit -> unit.isActiveIn(assignment.tenantId()))
				.isPresent();
	}

	private static ResponseStatusException forbidden() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN);
	}
}
