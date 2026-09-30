package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.authz.RbacProperties;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
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
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Class Name : ThreadWorkspaceServiceTest.java
 * Description : 방을 어느 워크스페이스에 두는지(협업방은 고른 곳, 1:1은 common)와 목록 거르기를 검증한다. 판정 자체는
 *               WorkspaceAuthorizer의 몫이라 여기서는 그 결과를 정해 두고 쓴다.
 */
class ThreadWorkspaceServiceTest {

	private static final String SUBJECT = "sub-kim";

	private final RbacProperties rbac = new RbacProperties();
	private final WorkspaceAuthorizer authorizer = mock(WorkspaceAuthorizer.class);
	private final WorkspaceNodeRepository nodes = mock(WorkspaceNodeRepository.class);
	private final TenantRepository tenants = mock(TenantRepository.class);
	private final AppUserRepository appUsers = mock(AppUserRepository.class);
	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final ThreadWorkspaceService service = new ThreadWorkspaceService(rbac, authorizer, nodes, tenants, appUsers,
			members, orgUnits);

	private Tenant tenant;
	private WorkspaceNode root;
	private WorkspaceNode common;
	private WorkspaceNode hr;
	private WorkspaceNode hrLead;

	@BeforeEach
	void setUp() {
		rbac.setEnforce(true);
		tenant = new Tenant("ogjg", "ACME", TenantStatus.ACTIVE);
		root = WorkspaceNode.root(tenant.getId(), "Root");
		common = WorkspaceNode.common(tenant.getId(), root.getId(), root.getPath(), "공용");
		hr = WorkspaceNode.child(tenant.getId(), root.getId(), root.getPath(), "hr", WorkspaceNodeKind.ORG, "인사팀",
				WorkspaceNodeStatus.ACTIVE);
		hrLead = WorkspaceNode.child(tenant.getId(), hr.getId(), hr.getPath(), "hr-lead", WorkspaceNodeKind.WORK,
				"인사팀 관리자방", WorkspaceNodeStatus.ACTIVE);
		for (WorkspaceNode node : List.of(root, common, hr, hrLead)) {
			when(nodes.findById(node.getId())).thenReturn(Optional.of(node));
		}
		when(authorizer.canView(any(), any())).thenReturn(Mono.just(false));
		when(authorizer.canCreateThread(any(), any())).thenReturn(Mono.just(false));
	}

	// ------------------------------------------------------------------ 협업방

	@Test
	void collabThreadGoesIntoTheChosenWorkspaceWhereTheSubjectCanCreateThreads() {
		creatable(hr);

		assertThat(service.collabPlacement(SUBJECT, hr.getId()).block()).contains(hr);
	}

	/** 보기(VIEWER)만 있으면 방을 만들 수 없다 — 협업방 생성은 THREAD_CREATE다(#299). */
	@Test
	void seeingAWorkspaceIsNotEnoughToCreateAThreadInIt() {
		visible(hr);

		assertStatus(service.collabPlacement(SUBJECT, hr.getId()), HttpStatus.FORBIDDEN);
	}

	@Test
	void workspaceIsRequiredWhenEnforcementIsOn() {
		assertStatus(service.collabPlacement(SUBJECT, null), HttpStatus.BAD_REQUEST);
	}

	@Test
	void workspaceIsOptionalWhenEnforcementIsOff() {
		rbac.setEnforce(false);

		assertThat(service.collabPlacement(SUBJECT, null).block()).isEmpty();
	}

	@Test
	void unauthorizedRootInactiveAndUnknownNodesAreAllForbiddenAlike() {
		creatable(root);
		WorkspaceNode retired = WorkspaceNode.child(tenant.getId(), root.getId(), root.getPath(), "old",
				WorkspaceNodeKind.WORK, "옛 방", WorkspaceNodeStatus.INACTIVE);
		when(nodes.findById(retired.getId())).thenReturn(Optional.of(retired));
		creatable(retired);

		assertStatus(service.collabPlacement(SUBJECT, hrLead.getId()), HttpStatus.FORBIDDEN);
		assertStatus(service.collabPlacement(SUBJECT, root.getId()), HttpStatus.FORBIDDEN);
		assertStatus(service.collabPlacement(SUBJECT, retired.getId()), HttpStatus.FORBIDDEN);
		assertStatus(service.collabPlacement(SUBJECT, UUID.randomUUID()), HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------ 1:1

	/** 판정이 켜져 있으면 1:1은 요청자의 배정 Tenant의 common에 둔다(#299). 다른 Tenant가 있어도 헷갈리지 않는다. */
	@Test
	void directThreadGoesIntoCommonOfTheRequestersAssignedTenant() {
		UUID userId = assignedUser(tenant.getId(), OrgUnitStatus.ACTIVE);
		when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
		when(nodes.findByTenantIdAndKey(tenant.getId(), "common")).thenReturn(Optional.of(common));

		assertThat(service.directPlacementBlocking(userId)).contains(common);
	}

	/** 배정이 없으면 1:1도 만들 수 없다 — common도 배정이 있어야 본다(#299). */
	@Test
	void directThreadIsForbiddenWithoutAnAssignment() {
		UUID userId = UUID.randomUUID();
		AppUser user = loginUser();
		when(appUsers.findById(userId)).thenReturn(Optional.of(user));
		when(members.findBySubject(SUBJECT)).thenReturn(List.of());

		assertThatThrownBy(() -> service.directPlacementBlocking(userId))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void directThreadIsForbiddenWhenTheAssignedTenantOrTeamIsInactive() {
		UUID userId = assignedUser(tenant.getId(), OrgUnitStatus.ACTIVE);
		when(tenants.findById(tenant.getId())).thenReturn(Optional.of(new Tenant("ogjg", "ACME", TenantStatus.INACTIVE)));
		when(nodes.findByTenantIdAndKey(tenant.getId(), "common")).thenReturn(Optional.of(common));
		assertThatThrownBy(() -> service.directPlacementBlocking(userId)).isInstanceOf(ResponseStatusException.class);

		UUID other = assignedUser(tenant.getId(), OrgUnitStatus.INACTIVE);
		when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
		assertThatThrownBy(() -> service.directPlacementBlocking(other)).isInstanceOf(ResponseStatusException.class);
	}

	/** 판정이 꺼져 있으면 지금처럼 ACTIVE Tenant가 하나일 때만 그 common이고, 여럿이면 비워 둔다. */
	@Test
	void withEnforcementOffDirectThreadGoesIntoTheOnlyActiveTenantsCommonOrStaysUnplaced() {
		rbac.setEnforce(false);
		UUID userId = UUID.randomUUID();
		AppUser user = mock(AppUser.class);
		when(user.getStatus()).thenReturn(AppUserStatus.ACTIVE);
		when(appUsers.findById(userId)).thenReturn(Optional.of(user));
		when(tenants.findAll()).thenReturn(List.of(tenant, new Tenant("old", "옛 고객사", TenantStatus.INACTIVE)));
		when(nodes.findByTenantIdAndKey(tenant.getId(), "common")).thenReturn(Optional.of(common));
		assertThat(service.directPlacementBlocking(userId)).contains(common);

		when(tenants.findAll()).thenReturn(List.of(tenant, new Tenant("acme", "다른 고객사", TenantStatus.ACTIVE)));
		assertThat(service.directPlacementBlocking(userId)).isEmpty();
		verifyNoInteractions(members);
	}

	@Test
	void inactiveDirectOwnerCannotCreateATurnWithPreservedAssignment() {
		UUID userId = assignedUser(tenant.getId(), OrgUnitStatus.ACTIVE);
		AppUser inactive = new AppUser(SUBJECT);
		inactive.deactivate();
		when(appUsers.findById(userId)).thenReturn(Optional.of(inactive));

		assertStatus(Mono.fromCallable(() -> service.directPlacementBlocking(userId)), HttpStatus.FORBIDDEN);
		verifyNoInteractions(tenants);
	}

	/** SUBJECT로 로그인하는 사람을 만들고, 그 사람을 tenantId의 팀(상태 지정)에 배정한다. */
	private UUID assignedUser(UUID tenantId, OrgUnitStatus teamStatus) {
		UUID userId = UUID.randomUUID();
		OrgUnit team = new OrgUnit(tenantId, "team-" + userId.toString().substring(0, 6), "팀", teamStatus);
		AppUser user = loginUser();
		when(appUsers.findById(userId)).thenReturn(Optional.of(user));
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, team.getId(), SUBJECT, Rank.S)));
		when(orgUnits.findById(team.getId())).thenReturn(Optional.of(team));
		return userId;
	}

	private static AppUser loginUser() {
		AppUser user = mock(AppUser.class);
		when(user.getKeycloakSubj()).thenReturn(SUBJECT);
		when(user.getStatus()).thenReturn(AppUserStatus.ACTIVE);
		return user;
	}

	// ------------------------------------------------------------------ 목록

	/** 맨 위는 ROOT이고 이름은 "Root"가 아니라 Tenant 이름(고객사 이름)이다. 그 아래는 트리 순서, common 먼저. */
	@Test
	void visibleWorkspacesStartWithTheTenantNamedRootThenTreeOrderWithCommonFirst() {
		when(nodes.findAll()).thenReturn(List.of(hrLead, root, hr, common));
		when(tenants.findAllById(List.of(tenant.getId()))).thenReturn(List.of(tenant));
		visible(common, hr, hrLead);

		List<ThreadWorkspaceService.WorkspaceView> views = service.visibleWorkspaces(SUBJECT).block();

		assertThat(views).extracting(ThreadWorkspaceService.WorkspaceView::id)
				.containsExactly(root.getId(), common.getId(), hr.getId(), hrLead.getId());
		assertThat(views.get(0)).isEqualTo(new ThreadWorkspaceService.WorkspaceView(root.getId(), null, "ACME",
				WorkspaceNodeKind.ROOT, 0));
		assertThat(views.get(2).parentId()).isEqualTo(root.getId());
		assertThat(views.get(3).parentId()).isEqualTo(hr.getId());
		assertThat(views.get(3).depth()).isEqualTo(2);
	}

	@Test
	void visibleWorkspacesLeaveOutWhatTheSubjectCannotSee() {
		when(nodes.findAll()).thenReturn(List.of(root, common, hr, hrLead));
		when(tenants.findAllById(List.of(tenant.getId()))).thenReturn(List.of(tenant));
		visible(common, hr);

		assertThat(service.visibleWorkspaces(SUBJECT).block()).extracting(ThreadWorkspaceService.WorkspaceView::id)
				.containsExactly(root.getId(), common.getId(), hr.getId());
	}

	/** 부모를 볼 수 없는 노드는 볼 수 있는 가장 가까운 조상(여기선 ROOT) 아래로 온다. 못 보는 부모는 목록에 없다. */
	@Test
	void nodeWhoseParentIsHiddenHangsUnderTheNearestVisibleAncestor() {
		when(nodes.findAll()).thenReturn(List.of(root, common, hr, hrLead));
		when(tenants.findAllById(List.of(tenant.getId()))).thenReturn(List.of(tenant));
		visible(hrLead);

		List<ThreadWorkspaceService.WorkspaceView> views = service.visibleWorkspaces(SUBJECT).block();

		assertThat(views).extracting(ThreadWorkspaceService.WorkspaceView::id).containsExactly(root.getId(), hrLead.getId());
		assertThat(views.get(1).parentId()).isEqualTo(root.getId());
	}

	/** 아무것도 못 보면 ROOT도 없다 — 볼 게 없는 Tenant의 이름을 알릴 이유가 없다. */
	@Test
	void noRootWhenNothingIsVisible() {
		when(nodes.findAll()).thenReturn(List.of(root, common, hr));

		assertThat(service.visibleWorkspaces(SUBJECT).block()).isEmpty();
	}

	@Test
	void filterKeepsOnlyThreadsInVisibleWorkspacesAndDropsUnplacedOnes() {
		Thr inHr = placed(hr);
		Thr inLead = placed(hrLead);
		Thr unplaced = Thr.collab(UUID.randomUUID(), "옛 방");
		visible(hr);

		assertThat(service.filterVisible(List.of(inHr, inLead, unplaced), SUBJECT).block()).containsExactly(inHr);
	}

	@Test
	void filterLeavesTheListAsIsWhenEnforcementIsOff() {
		rbac.setEnforce(false);
		List<Thr> threads = List.of(placed(hrLead), Thr.collab(UUID.randomUUID(), "옛 방"));

		assertThat(service.filterVisible(threads, SUBJECT).block()).isEqualTo(threads);
		verifyNoInteractions(authorizer);
	}

	private void creatable(WorkspaceNode... workspaces) {
		for (WorkspaceNode node : workspaces) {
			when(authorizer.canCreateThread(eq(SUBJECT), eq(node.getId()))).thenReturn(Mono.just(true));
		}
	}

	private void visible(WorkspaceNode... workspaces) {
		for (WorkspaceNode node : workspaces) {
			when(authorizer.canView(eq(SUBJECT), eq(node.getId()))).thenReturn(Mono.just(true));
		}
	}

	private static Thr placed(WorkspaceNode node) {
		Thr thread = Thr.collab(UUID.randomUUID(), "방");
		thread.placeIn(node.getTenantId(), node.getId());
		return thread;
	}

	private static void assertStatus(Mono<?> result, HttpStatus status) {
		StepVerifier.create(result)
				.verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(status));
	}
}
