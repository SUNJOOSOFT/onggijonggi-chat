package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.authz.RbacProperties;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.chat.domain.Thr;
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
	private final ThreadWorkspaceService service = new ThreadWorkspaceService(rbac, authorizer, nodes, tenants);

	private Tenant tenant;
	private WorkspaceNode root;
	private WorkspaceNode common;
	private WorkspaceNode hr;
	private WorkspaceNode hrLead;

	@BeforeEach
	void setUp() {
		rbac.setEnforce(true);
		tenant = new Tenant("ogjg", "옹기종기", TenantStatus.ACTIVE);
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
	}

	// ------------------------------------------------------------------ 협업방

	@Test
	void collabThreadGoesIntoTheChosenVisibleWorkspace() {
		visible(hr);

		assertThat(service.collabPlacement(SUBJECT, hr.getId()).block()).contains(hr);
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
	void invisibleRootInactiveAndUnknownNodesAreAllForbiddenAlike() {
		visible(root);
		WorkspaceNode retired = WorkspaceNode.child(tenant.getId(), root.getId(), root.getPath(), "old",
				WorkspaceNodeKind.WORK, "옛 방", WorkspaceNodeStatus.INACTIVE);
		when(nodes.findById(retired.getId())).thenReturn(Optional.of(retired));
		visible(retired);

		assertStatus(service.collabPlacement(SUBJECT, hrLead.getId()), HttpStatus.FORBIDDEN);
		assertStatus(service.collabPlacement(SUBJECT, root.getId()), HttpStatus.FORBIDDEN);
		assertStatus(service.collabPlacement(SUBJECT, retired.getId()), HttpStatus.FORBIDDEN);
		assertStatus(service.collabPlacement(SUBJECT, UUID.randomUUID()), HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------ 1:1

	@Test
	void directThreadGoesIntoCommonOfTheOnlyActiveTenant() {
		when(tenants.findAll()).thenReturn(List.of(tenant, new Tenant("old", "옛 고객사", TenantStatus.INACTIVE)));
		when(nodes.findByTenantIdAndKey(tenant.getId(), "common")).thenReturn(Optional.of(common));

		assertThat(service.directPlacementBlocking()).contains(common);
	}

	/** Tenant가 여럿이면 사람이 어느 Tenant 소속인지 모른다(절체의 몫) — 추측하지 않고 비워 둔다. */
	@Test
	void directThreadStaysUnplacedWhenThereIsNoSingleActiveTenant() {
		when(tenants.findAll()).thenReturn(List.of(tenant, new Tenant("acme", "다른 고객사", TenantStatus.ACTIVE)));
		assertThat(service.directPlacementBlocking()).isEmpty();

		when(tenants.findAll()).thenReturn(List.of());
		assertThat(service.directPlacementBlocking()).isEmpty();
	}

	// ------------------------------------------------------------------ 목록

	@Test
	void visibleWorkspacesAreInTreeOrderWithCommonFirstAndNoRoot() {
		when(nodes.findAll()).thenReturn(List.of(hrLead, root, hr, common));
		visible(root, common, hr, hrLead);

		List<ThreadWorkspaceService.WorkspaceView> views = service.visibleWorkspaces(SUBJECT).block();

		assertThat(views).extracting(ThreadWorkspaceService.WorkspaceView::id)
				.containsExactly(common.getId(), hr.getId(), hrLead.getId());
		assertThat(views.get(1).parentId()).isNull();
		assertThat(views.get(2).parentId()).isEqualTo(hr.getId());
		assertThat(views.get(2).depth()).isEqualTo(2);
	}

	@Test
	void visibleWorkspacesLeaveOutWhatTheSubjectCannotSee() {
		when(nodes.findAll()).thenReturn(List.of(root, common, hr, hrLead));
		visible(common, hr);

		assertThat(service.visibleWorkspaces(SUBJECT).block()).extracting(ThreadWorkspaceService.WorkspaceView::id)
				.containsExactly(common.getId(), hr.getId());
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
