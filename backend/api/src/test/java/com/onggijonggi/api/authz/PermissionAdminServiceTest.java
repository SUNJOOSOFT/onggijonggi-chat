package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakPerson;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Class Name : PermissionAdminServiceTest.java
 * Description : 권한 관리 화면 한 장을 만드는 규칙을 검증한다 — 사람은 Keycloak 계정 전체(미배정 포함), workspace는 ROOT·COMMON·
 *               비활성을 빼고 트리 순서, "누가 무엇을 보나"는 판정 API의 결과 그대로, 배정 변경은 배정 서비스를 화면 사용자로 부른다.
 */
class PermissionAdminServiceTest {

	private static final UUID TENANT = UUID.randomUUID();

	private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final WorkspaceNodeRepository nodes = mock(WorkspaceNodeRepository.class);
	private final WorkspaceAuthorizer authorizer = mock(WorkspaceAuthorizer.class);
	private final OrgUnitMemberService memberService = mock(OrgUnitMemberService.class);
	private final PermissionAdminService service = new PermissionAdminService(keycloak, orgUnits, members, nodes, authorizer, memberService);

	@Test
	void theOverviewListsEveryoneAndAsksTheAuthorizerForEachWorkspace() {
		OrgUnit hr = new OrgUnit(TENANT, "hr", "인사팀", OrgUnitStatus.ACTIVE);
		OrgUnit old = new OrgUnit(TENANT, "old", "옛 팀", OrgUnitStatus.INACTIVE);
		when(orgUnits.findAll()).thenReturn(List.of(old, hr));
		WorkspaceNode root = WorkspaceNode.root(TENANT, "Root");
		WorkspaceNode common = WorkspaceNode.common(TENANT, root.getId(), root.getPath(), "Common");
		WorkspaceNode hrNode = WorkspaceNode.child(TENANT, root.getId(), root.getPath(), "hr", WorkspaceNodeKind.ORG, "인사팀",
				WorkspaceNodeStatus.ACTIVE);
		WorkspaceNode lead = WorkspaceNode.child(TENANT, hrNode.getId(), hrNode.getPath(), "hr-lead", WorkspaceNodeKind.WORK, "간부방",
				WorkspaceNodeStatus.ACTIVE);
		WorkspaceNode retired = WorkspaceNode.child(TENANT, root.getId(), root.getPath(), "retired", WorkspaceNodeKind.ORG, "Retired",
				WorkspaceNodeStatus.INACTIVE);
		when(nodes.findAll()).thenReturn(List.of(lead, retired, common, root, hrNode));
		when(members.findAll()).thenReturn(List.of(new OrgUnitMember(TENANT, hr.getId(), "sub-kim", Rank.K)));
		when(keycloak.listPeople(any(Integer.class))).thenReturn(Mono.just(List.of(
				new KeycloakPerson("sub-park", "demo7", "박", "p@example.com", true),
				new KeycloakPerson("sub-kim", "demo1", "김", "k@example.com", true))));
		when(authorizer.canView(any(), any())).thenReturn(Mono.just(false));
		when(authorizer.canView("sub-kim", hrNode.getId())).thenReturn(Mono.just(true));

		PermissionAdminService.Overview overview = service.overview().block();

		assertThat(overview.teams()).extracting(PermissionAdminService.Team::key).containsExactly("hr");
		assertThat(overview.workspaces()).extracting(PermissionAdminService.Workspace::key).containsExactly("hr", "hr-lead");
		assertThat(overview.workspaces()).extracting(PermissionAdminService.Workspace::depth).containsExactly(1, 2);
		assertThat(overview.ranks()).extracting(PermissionAdminService.RankOption::label).startsWith("팀장", "부장");
		assertThat(overview.people()).extracting(PermissionAdminService.Person::username).containsExactly("demo1", "demo7");
		PermissionAdminService.Person kim = overview.people().get(0);
		assertThat(kim.teamId()).isEqualTo(hr.getId());
		assertThat(kim.rank()).isEqualTo("K");
		assertThat(kim.visible()).containsExactly(hrNode.getId());
		PermissionAdminService.Person park = overview.people().get(1);
		assertThat(park.teamId()).isNull();
		assertThat(park.visible()).isEmpty();
	}

	@Test
	void changingAnAssignmentGoesThroughTheAssignmentServiceAsTheScreenUser() {
		UUID actor = UUID.randomUUID();
		UUID team = UUID.randomUUID();
		when(memberService.apply(any(), any())).thenReturn(new OrgUnitMemberService.Result("sub-kim", OrgUnitMemberService.Outcome.CHANGED));

		assertThat(service.assign("sub-kim", team, Rank.B, actor).block()).isEqualTo(OrgUnitMemberService.Outcome.CHANGED);
		verify(memberService).apply(eq(OrgUnitMemberService.Change.assign("sub-kim", team, Rank.B)),
				eq(OrgUnitMemberService.Actor.user(actor, "admin-screen")));

		service.assign("sub-kim", null, null, actor).block();
		verify(memberService).apply(eq(OrgUnitMemberService.Change.unassign("sub-kim")), any());
	}
}
