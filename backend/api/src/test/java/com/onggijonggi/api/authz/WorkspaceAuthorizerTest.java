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

import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.Rank;
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
 * Description : 판정 API가 Casbin에 묻기 전후에 지키는 규칙을 검증한다 — 스위치가 꺼지면 늘 허용, COMMON은 누구나,
 *               미배정자는 묻지 않고 거부, 서열은 숫자 속성, 배정마다 묻기(겸직 대비), 규칙을 잃으면 한 번만 다시 넣고 재시도.
 *               Casbin 판정 자체는 CasbinServerContainerTest가 실제 서버로 확인한다.
 */
class WorkspaceAuthorizerTest {

	private static final UUID TENANT = UUID.randomUUID();
	private static final String SUBJECT = "sub-kim";

	private final RbacProperties rbac = new RbacProperties();
	private final WorkspaceNodeRepository nodes = mock(WorkspaceNodeRepository.class);
	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final CasbinRuleLoader loader = mock(CasbinRuleLoader.class);
	private final CasbinClient client = mock(CasbinClient.class);
	private final WorkspaceAuthorizer authorizer = new WorkspaceAuthorizer(rbac, nodes, members, loader, client, new JsonMapper());

	private WorkspaceNode root;
	private WorkspaceNode hr;

	@BeforeEach
	void setUp() {
		rbac.setEnforce(true);
		root = WorkspaceNode.root(TENANT, "Root");
		hr = WorkspaceNode.child(TENANT, root.getId(), root.getPath(), "hr", WorkspaceNodeKind.ORG, "인사팀", WorkspaceNodeStatus.ACTIVE);
		when(nodes.findById(hr.getId())).thenReturn(Optional.of(hr));
		when(client.isLoaded()).thenReturn(true);
	}

	@Test
	void everythingIsAllowedWhenEnforcementIsOff() {
		rbac.setEnforce(false);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isTrue();
		verifyNoInteractions(nodes, members, loader, client);
	}

	@Test
	void anyoneSeesTheCommonWorkspaceEvenWithoutAnAssignment() {
		WorkspaceNode common = WorkspaceNode.common(TENANT, root.getId(), root.getPath(), "Common");
		when(nodes.findById(common.getId())).thenReturn(Optional.of(common));

		assertThat(authorizer.canView(SUBJECT, common.getId()).block()).isTrue();
		verifyNoInteractions(members, client);
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
		WorkspaceNode retired = WorkspaceNode.child(TENANT, root.getId(), root.getPath(), "retired", WorkspaceNodeKind.ORG, "Retired",
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
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(TENANT, team, SUBJECT, Rank.K)));
		String expected = "{\"OrgUnit\":\"" + team + "\",\"Rank\":4}";
		when(client.enforce(expected, hr.getId().toString(), "view")).thenReturn(true);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isTrue();
		verify(loader).ensureLoaded();
	}

	@Test
	void anyPassingAssignmentAllowsSoThatConcurrentPositionsNeedNoChangeHere() {
		OrgUnitMember first = new OrgUnitMember(TENANT, UUID.randomUUID(), SUBJECT, Rank.S);
		OrgUnitMember second = new OrgUnitMember(TENANT, UUID.randomUUID(), SUBJECT, Rank.TL);
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(first, second));
		when(client.enforce(anyString(), eq(hr.getId().toString()), eq("view"))).thenReturn(false, true);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isTrue();
		verify(client, times(2)).enforce(anyString(), any(), any());
	}

	@Test
	void lostRulesAreReloadedOnceAndAskedAgain() {
		// Casbin 서버가 재시작하면 enforce가 false를 내고 적재 상태가 비워진다. 한 번만 다시 넣고 다시 묻는다.
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(TENANT, UUID.randomUUID(), SUBJECT, Rank.B)));
		when(client.enforce(anyString(), any(), any())).thenReturn(false, true);
		when(client.isLoaded()).thenReturn(false);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isTrue();
		verify(loader, times(2)).ensureLoaded();
		verify(client, times(2)).enforce(anyString(), any(), any());
	}

	@Test
	void aDenialWithRulesStillLoadedIsNotRetried() {
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(TENANT, UUID.randomUUID(), SUBJECT, Rank.S)));
		when(client.enforce(anyString(), any(), any())).thenReturn(false);

		assertThat(authorizer.canView(SUBJECT, hr.getId()).block()).isFalse();
		verify(client, times(1)).enforce(anyString(), any(), any());
		verify(loader, times(1)).ensureLoaded();
		verify(loader, never()).onReadiness(any());
	}
}
