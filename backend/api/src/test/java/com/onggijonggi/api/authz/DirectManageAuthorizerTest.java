package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.authz.OrgUnit;
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
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.authz.WorkspaceRole;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : DirectManageAuthorizerTest.java
 * Description : 직접 MANAGE 판정(#259 감사 조회, #260 관리 쓰기)을 검증한다 — 스위치와 무관하게 DB 부여로 계산하고, ADMIN
 *               역할만 MANAGE를 준다, 팀 규칙은 내 팀만, 직급 규칙은 팀이 없거나 내 팀이면서 서열이 닿을 때만, 부모의 부여는
 *               보지 않는다, 노드·Tenant·팀이 비활성이거나 배정이 없거나 다른 Tenant면 거부한다. 쓰기는 비활성 계정과
 *               Casbin 반영이 막힌 Tenant(판정이 켜진 동안)도 거부하고, 상태 무관 판정은 비활성 노드 자체의 보존된 부여로 본다.
 */
class DirectManageAuthorizerTest {

	private static final String SUBJECT = "sub-kim";

	private final Tenant acme = new Tenant("acme", "ACME", TenantStatus.ACTIVE);
	private final UUID tenantId = acme.getId();
	private final OrgUnit team = new OrgUnit(tenantId, "hr", "인사팀", OrgUnitStatus.ACTIVE);
	private final MemberAttributes members = mock(MemberAttributes.class);
	private final TenantRepository tenants = mock(TenantRepository.class);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final WorkspaceGrantRepository workspaceGrants = mock(WorkspaceGrantRepository.class);
	private final RankGrantRepository rankGrants = mock(RankGrantRepository.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final RbacPolicyRefresh policyRefresh = mock(RbacPolicyRefresh.class);
	private final RbacProperties rbac = new RbacProperties();
	private final AppUser actor = new AppUser(SUBJECT);
	private final DirectManageAuthorizer authorizer = new DirectManageAuthorizer(members, tenants, orgUnits, workspaceGrants,
			rankGrants, users, policyRefresh, rbac);

	private WorkspaceNode root;
	private WorkspaceNode hr;
	private WorkspaceNode payroll;

	@BeforeEach
	void setUp() {
		root = WorkspaceNode.root(tenantId, "Root");
		hr = WorkspaceNode.child(tenantId, root.getId(), root.getPath(), "hr", WorkspaceNodeKind.ORG, "인사팀",
				WorkspaceNodeStatus.ACTIVE);
		payroll = WorkspaceNode.child(tenantId, hr.getId(), hr.getPath(), "payroll", WorkspaceNodeKind.ORG, "급여",
				WorkspaceNodeStatus.ACTIVE);
		when(tenants.findById(tenantId)).thenReturn(Optional.of(acme));
		when(orgUnits.findById(team.getId())).thenReturn(Optional.of(team));
		when(workspaceGrants.findByWorkspaceNodeId(any())).thenReturn(List.of());
		when(rankGrants.findByWorkspaceNodeId(any())).thenReturn(List.of());
		assign(Rank.K);
		when(users.findByKeycloakSubj(SUBJECT)).thenReturn(Optional.of(actor));
	}

	@Test
	void anAdminGrantToMyTeamGivesManage() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);

		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isTrue();
	}

	@Test
	void managementReadIncludesInactiveRecoveryTargetsWithBothSwitchValues() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);
		hr.reconcileStatus(WorkspaceNodeStatus.INACTIVE);
		for (boolean enforce : List.of(false, true)) {
			rbac.setEnforce(enforce);
			assertThat(authorizer.canReadManagement(SUBJECT, hr)).isTrue();
			assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
		}
	}

	@Test
	void managementReadDoesNotBypassMissingGrantWhenSwitchIsOff() {
		rbac.setEnforce(false);
		assertThat(authorizer.canReadManagement(SUBJECT, hr)).isFalse();
	}

	@Test
	void viewerAndContributorGrantsDoNotGiveManage() {
		grant(hr, team.getId(), WorkspaceRole.VIEWER);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();

		grant(hr, team.getId(), WorkspaceRole.CONTRIBUTOR);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void anAdminGrantToAnotherTeamDoesNotCount() {
		grant(hr, UUID.randomUUID(), WorkspaceRole.ADMIN);

		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void aTeamlessRankRuleCountsWhenMyRankReachesIt() {
		// 과장(서열 4)은 "과장 이상(<= 4)" 규칙을 통과하고 "부장 이상(<= 2)" 규칙은 통과하지 못한다.
		rankRule(hr, null, Rank.K, WorkspaceRole.ADMIN);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isTrue();

		rankRule(hr, null, Rank.B, WorkspaceRole.ADMIN);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void aTeamRankRuleCountsOnlyForMyTeam() {
		rankRule(hr, team.getId(), Rank.D, WorkspaceRole.ADMIN);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isTrue();

		rankRule(hr, UUID.randomUUID(), Rank.D, WorkspaceRole.ADMIN);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void aRankRuleBelowAdminDoesNotGiveManage() {
		rankRule(hr, null, Rank.S, WorkspaceRole.CONTRIBUTOR);

		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void aParentAdminGrantDoesNotReachTheChild() {
		// 권한은 상속되지 않는다(#299). 부모 노드에만 ADMIN이 있으면 자식의 감사는 볼 수 없다.
		grant(hr, team.getId(), WorkspaceRole.ADMIN);

		assertThat(authorizer.hasDirectManage(SUBJECT, payroll)).isFalse();
	}

	@Test
	void inactiveNodeTenantOrTeamIsDenied() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);

		WorkspaceNode closedNode = WorkspaceNode.child(tenantId, root.getId(), root.getPath(), "old", WorkspaceNodeKind.ORG, "옛 팀",
				WorkspaceNodeStatus.INACTIVE);
		when(workspaceGrants.findByWorkspaceNodeId(closedNode.getId()))
				.thenReturn(List.of(new WorkspaceGrant(tenantId, team.getId(), closedNode.getId(), WorkspaceRole.ADMIN)));
		assertThat(authorizer.hasDirectManage(SUBJECT, closedNode)).isFalse();

		when(tenants.findById(tenantId)).thenReturn(Optional.of(new Tenant("acme", "ACME", TenantStatus.INACTIVE)));
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();

		when(tenants.findById(tenantId)).thenReturn(Optional.of(acme));
		when(orgUnits.findById(team.getId())).thenReturn(Optional.of(new OrgUnit(tenantId, "hr", "인사팀", OrgUnitStatus.INACTIVE)));
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	@Test
	void noAssignmentOrAnAssignmentInAnotherTenantIsDenied() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);

		when(members.findBySubject(SUBJECT)).thenReturn(List.of());
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();

		when(members.findBySubject(SUBJECT))
				.thenReturn(List.of(new MemberAttribute(SUBJECT, UUID.randomUUID(), team.getId(), Rank.TL)));
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isFalse();
	}

	// ------------------------------------------------------------------ 관리 쓰기(#260)

	@Test
	void writesNeedDirectManageEvenWhenEnforcementIsOff() {
		rbac.setEnforce(false);
		assertForbidden(() -> authorizer.require(SUBJECT, hr));

		grant(hr, team.getId(), WorkspaceRole.ADMIN);
		assertThatCode(() -> authorizer.require(SUBJECT, hr)).doesNotThrowAnyException();
		// 부모의 ADMIN으로 자식 쓰기를 대신하지 않는다.
		assertForbidden(() -> authorizer.require(SUBJECT, payroll));
	}

	@Test
	void writesAreRefusedForAnInactiveAccount() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);
		actor.deactivate();

		assertForbidden(() -> authorizer.require(SUBJECT, hr));
	}

	@Test
	void writesWaitWhileTheTenantsCommittedRulesAreNotLoadedButOnlyWhenEnforced() {
		grant(hr, team.getId(), WorkspaceRole.ADMIN);
		when(policyRefresh.isBlocked(tenantId)).thenReturn(true);

		rbac.setEnforce(true);
		assertForbidden(() -> authorizer.require(SUBJECT, hr));
		// 판정이 꺼진 동안은 Casbin 반영 여부가 채팅 판정에 쓰이지 않으므로 쓰기를 막지 않는다.
		rbac.setEnforce(false);
		assertThatCode(() -> authorizer.require(SUBJECT, hr)).doesNotThrowAnyException();
		// 읽기(감사 조회)는 반영 지연과 무관하다.
		rbac.setEnforce(true);
		assertThat(authorizer.hasDirectManage(SUBJECT, hr)).isTrue();
	}

	@Test
	void reactivationIsJudgedByTheInactiveNodesOwnPreservedGrants() {
		WorkspaceNode closed = WorkspaceNode.child(tenantId, hr.getId(), hr.getPath(), "closed", WorkspaceNodeKind.WORK, "닫힌 방",
				WorkspaceNodeStatus.INACTIVE);
		// 부모 hr의 ADMIN만으로는 되살릴 수 없다.
		grant(hr, team.getId(), WorkspaceRole.ADMIN);
		assertForbidden(() -> authorizer.requireIgnoringStatus(SUBJECT, closed));

		grant(closed, team.getId(), WorkspaceRole.ADMIN);
		assertThatCode(() -> authorizer.requireIgnoringStatus(SUBJECT, closed)).doesNotThrowAnyException();
		// 일반 쓰기 판정은 비활성 노드를 받지 않는다.
		assertForbidden(() -> authorizer.require(SUBJECT, closed));
	}

	private static void assertForbidden(Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	private void assign(Rank rank) {
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new MemberAttribute(SUBJECT, tenantId, team.getId(), rank)));
	}

	private void grant(WorkspaceNode node, UUID orgUnitId, WorkspaceRole role) {
		when(workspaceGrants.findByWorkspaceNodeId(node.getId()))
				.thenReturn(List.of(new WorkspaceGrant(tenantId, orgUnitId, node.getId(), role)));
	}

	private void rankRule(WorkspaceNode node, UUID orgUnitId, Rank rank, WorkspaceRole role) {
		when(rankGrants.findByWorkspaceNodeId(node.getId()))
				.thenReturn(List.of(new RankGrant(tenantId, node.getId(), orgUnitId, rank, role)));
	}
}
