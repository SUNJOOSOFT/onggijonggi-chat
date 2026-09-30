package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : WorkspaceAuthorizerTest.java
 * Description : 판정 API가 Casbin에 묻기 전후에 지키는 규칙을 검증한다(#299) — 스위치가 꺼지면 늘 허용, COMMON도
 *               예외 없이 판정을 탄다, 미배정자·다른 Tenant 배정·비활성 Tenant·비활성 org-unit은 묻지 않고 거부,
 *               서열은 숫자 속성, 배정마다 묻기(겸직 대비), 규칙을 잃으면 한 번만 다시 넣고 재시도.
 *               Casbin 판정 자체는 CasbinServerContainerTest가 실제 서버로 확인한다.
 */
class WorkspaceAuthorizerTest {

	private static final String SUBJECT = "sub-kim";

	private final Tenant acme = new Tenant("acme", "ACME", TenantStatus.ACTIVE);
	private final UUID tenantId = acme.getId();
	private final RbacProperties rbac = new RbacProperties();
	private final WorkspaceNodeRepository nodes = mock(WorkspaceNodeRepository.class);
	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final TenantRepository tenants = mock(TenantRepository.class);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final CasbinRuleLoader loader = mock(CasbinRuleLoader.class);
	private final CasbinClient client = mock(CasbinClient.class);
	private final RbacPolicyRefresh policyRefresh = mock(RbacPolicyRefresh.class);
	private final WorkspaceAuthorizer authorizer = new WorkspaceAuthorizer(rbac, nodes, members, tenants, orgUnits, loader, client,
			new JsonMapper(), policyRefresh);

	private WorkspaceNode root;
	private WorkspaceNode hr;

	@BeforeEach
	void setUp() {
		rbac.setEnforce(true);
		root = WorkspaceNode.root(tenantId, "Root");
		hr = WorkspaceNode.child(tenantId, root.getId(), root.getPath(), "hr", WorkspaceNodeKind.ORG, "인사팀", WorkspaceNodeStatus.ACTIVE);
		when(nodes.findById(hr.getId())).thenReturn(Optional.of(hr));
		when(tenants.findById(tenantId)).thenReturn(Optional.of(acme));
		// 배정된 팀은 기본으로 같은 Tenant의 ACTIVE 팀이다. 비활성·다른 Tenant 팀은 해당 테스트에서 바꾼다.
		when(orgUnits.findById(any())).thenReturn(Optional.of(new OrgUnit(tenantId, "team", "Team", OrgUnitStatus.ACTIVE)));
		when(client.isLoaded()).thenReturn(true);
	}

	@Test
	void everythingIsAllowedWhenEnforcementIsOff() {
		rbac.setEnforce(false);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isTrue();
		verifyNoInteractions(nodes, members, tenants, orgUnits, loader, client);
	}

	@Test
	void theCommonWorkspaceIsNotOpenToPeopleWithoutAnAssignment() {
		// COMMON도 예외가 없다. 소속이 없으면 1:1 채팅도 쓸 수 없다(#299).
		WorkspaceNode common = WorkspaceNode.common(tenantId, root.getId(), root.getPath(), "Common");
		when(nodes.findById(common.getId())).thenReturn(Optional.of(common));
		when(members.findBySubject(SUBJECT)).thenReturn(List.of());

		assertThat(authorizer.canView(SUBJECT, common.getId()).block()).isFalse();
		assertThat(authorizer.canCreateThread(SUBJECT, common.getId()).block()).isFalse();
		verifyNoInteractions(client);
	}

	@Test
	void theCommonWorkspaceIsSeenThroughItsRequiredViewerGrant() {
		// 소속이 있으면 COMMON도 Casbin에 묻는다 — 모든 ACTIVE 팀이 가진 COMMON VIEWER 부여가 통과시킨다.
		WorkspaceNode common = WorkspaceNode.common(tenantId, root.getId(), root.getPath(), "Common");
		when(nodes.findById(common.getId())).thenReturn(Optional.of(common));
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, UUID.randomUUID(), SUBJECT, Rank.S)));
		when(client.enforce(anyString(), eq(common.getId().toString()), eq(CasbinPolicy.VIEW))).thenReturn(true);

		assertThat(authorizer.canView(SUBJECT, common.getId()).block()).isTrue();
		verify(client).enforce(anyString(), eq(common.getId().toString()), eq(CasbinPolicy.VIEW));
	}

	@Test
	void anAssignmentInAnotherTenantIsDeniedWithoutAskingCasbin() {
		// Casbin model에는 Tenant 차원이 없다. 팀을 정하지 않은 직급 규칙이 다른 Tenant의 같은 직급을 통과시키지 않게
		// 배정의 Tenant가 대상 노드의 Tenant와 다르면 묻지 않는다.
		UUID otherTenant = UUID.randomUUID();
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(otherTenant, UUID.randomUUID(), SUBJECT, Rank.TL)));

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isFalse();
		verifyNoInteractions(client);
	}

	@Test
	void anInactiveTenantIsDeniedWithoutAskingCasbin() {
		Tenant closed = new Tenant("closed", "Closed", TenantStatus.INACTIVE);
		when(tenants.findById(tenantId)).thenReturn(Optional.of(closed));
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, UUID.randomUUID(), SUBJECT, Rank.TL)));

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isFalse();
		verifyNoInteractions(client);
	}

	@Test
	void anAssignmentToAnInactiveOrgUnitIsDeniedWithoutAskingCasbin() {
		when(orgUnits.findById(any())).thenReturn(Optional.of(new OrgUnit(tenantId, "retired", "Retired", OrgUnitStatus.INACTIVE)));
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, UUID.randomUUID(), SUBJECT, Rank.TL)));

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isFalse();
		verifyNoInteractions(client);
	}

	@Test
	void canCreateThreadAsksCasbinForTheThreadCreateAction() {
		UUID team = UUID.randomUUID();
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, team, SUBJECT, Rank.K)));
		when(client.enforce(anyString(), eq(hr.getId().toString()), eq(CasbinPolicy.THREAD_CREATE))).thenReturn(true);

		assertThat(authorizer.canCreateThread(SUBJECT, hr.getId()).block()).isTrue();
	}

	@Test
	void canManageAsksCasbinForTheManageAction() {
		UUID team = UUID.randomUUID();
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, team, SUBJECT, Rank.K)));
		when(client.enforce(anyString(), eq(hr.getId().toString()), eq(CasbinPolicy.MANAGE))).thenReturn(true);

		assertThat(authorizer.canManage(SUBJECT, hr.getId()).block()).isTrue();
	}

	@Test
	void unassignedPeopleAreDeniedWithoutAskingCasbin() {
		// 서열 0 같은 값으로 물으면 "서열 4 이하" 규칙이 열린다. 묻지 않는 것이 유일하게 안전하다.
		when(members.findBySubject(SUBJECT)).thenReturn(List.of());

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isFalse();
		verifyNoInteractions(client);
	}

	@Test
	void inactiveOrMissingWorkspacesAreDenied() {
		WorkspaceNode retired = WorkspaceNode.child(tenantId, root.getId(), root.getPath(), "retired", WorkspaceNodeKind.ORG, "Retired",
				WorkspaceNodeStatus.INACTIVE);
		when(nodes.findById(retired.getId())).thenReturn(Optional.of(retired));
		UUID missing = UUID.randomUUID();
		when(nodes.findById(missing)).thenReturn(Optional.empty());

		assertThat(authorizer.canView(SUBJECT, retired.getId()).block()).isFalse();
		assertThat(authorizer.canView(SUBJECT, missing).block()).isFalse();
		verifyNoInteractions(client);
	}

	@Test
	void theAssignmentIsSentAsTeamIdAndNumericRank() {
		UUID team = UUID.randomUUID();
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, team, SUBJECT, Rank.K)));
		String expected = "{\"OrgUnit\":\"" + team + "\",\"Rank\":4}";
		when(client.enforce(expected, hr.getId().toString(), "view")).thenReturn(true);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isTrue();
		verify(loader).ensureLoaded();
	}

	@Test
	void anyPassingAssignmentAllowsSoThatConcurrentPositionsNeedNoChangeHere() {
		OrgUnitMember first = new OrgUnitMember(tenantId, UUID.randomUUID(), SUBJECT, Rank.S);
		OrgUnitMember second = new OrgUnitMember(tenantId, UUID.randomUUID(), SUBJECT, Rank.TL);
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(first, second));
		when(client.enforce(anyString(), eq(hr.getId().toString()), eq("view"))).thenReturn(false, true);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isTrue();
		verify(client, times(2)).enforce(anyString(), any(), any());
	}

	@Test
	void lostRulesAreReloadedOnceAndAskedAgain() {
		// Casbin 서버가 재시작하면 enforce가 false를 내고 적재 상태가 비워진다. 한 번만 다시 넣고 다시 묻는다.
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, UUID.randomUUID(), SUBJECT, Rank.B)));
		when(client.enforce(anyString(), any(), any())).thenReturn(false, true);
		when(client.isLoaded()).thenReturn(false);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isTrue();
		verify(loader, times(2)).ensureLoaded();
		verify(client, times(2)).enforce(anyString(), any(), any());
	}

	@Test
	void aDenialWithRulesStillLoadedIsNotRetried() {
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, UUID.randomUUID(), SUBJECT, Rank.S)));
		when(client.enforce(anyString(), any(), any())).thenReturn(false);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isFalse();
		verify(client, times(1)).enforce(anyString(), any(), any());
		verify(loader, times(1)).ensureLoaded();
		verify(loader, never()).onReadiness(any());
	}
}
