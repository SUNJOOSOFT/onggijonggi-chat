package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.onggijonggi.common.authz.*;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : WorkspaceManagementQueryServiceTest.java
 * Description : 관리용 조회의 숨겨진 부모·Tenant 격리, 비활성 복구와 플랫폼 최소 응답을 검증한다.
 */
class WorkspaceManagementQueryServiceTest {
	private final WorkspaceNodeRepository nodes = mock(WorkspaceNodeRepository.class);
	private final TenantRepository tenants = mock(TenantRepository.class);
	private final OrgUnitRepository units = mock(OrgUnitRepository.class);
	private final WorkspaceGrantRepository grants = mock(WorkspaceGrantRepository.class);
	private final ThrRepository threads = mock(ThrRepository.class);
	private final DirectManageAuthorizer manage = mock(DirectManageAuthorizer.class);
	private final RbacBootstrapConfigReader config = mock(RbacBootstrapConfigReader.class);
	private final WorkspaceManagementQueryService service = new WorkspaceManagementQueryService(nodes, tenants, units, grants, threads, manage, config);
	private final Tenant tenant = new Tenant("default", "기본", TenantStatus.ACTIVE);
	private WorkspaceNode root;
	private WorkspaceNode work;
	@BeforeEach void setup() {
		root = WorkspaceNode.root(tenant.getId(), "숨겨진 부모");
		work = WorkspaceNode.child(tenant.getId(), root.getId(), root.getPath(), "work", WorkspaceNodeKind.WORK, "업무", WorkspaceNodeStatus.ACTIVE);
		when(nodes.findAll()).thenReturn(List.of(root, work));
		when(nodes.findById(work.getId())).thenReturn(Optional.of(work));
		when(nodes.findById(root.getId())).thenReturn(Optional.of(root));
		when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
		when(manage.canReadManagement("manager", work)).thenReturn(true);
		when(config.loadWithFingerprint()).thenReturn(Optional.empty());
	}
	@Test void hidesUnmanagedAncestorsAndDoesNotExposePathOrKey() {
		var result = service.workspaces("manager");
		assertThat(result).hasSize(1);
		assertThat(result.get(0).parentId()).isNull();
		assertThat(result.get(0).name()).isEqualTo("업무");
		assertThat(result.get(0).actions()).contains("AUDIT", "RENAME");
	}
	@Test void inactiveNodeIsARecoveryTargetButDetailReadsAreForbidden() {
		work.reconcileStatus(WorkspaceNodeStatus.INACTIVE);
		assertThat(service.workspaces("manager").get(0).actions()).containsExactly("REACTIVATE");
		assertThatThrownBy(() -> service.grants("manager", work.getId())).isInstanceOf(ResponseStatusException.class)
				.satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));
	}
	@Test void cannotReadGrantsWithoutDirectManage() {
		assertThat(service.workspaces("user")).isEmpty();
		assertThatThrownBy(() -> service.grants("user", work.getId())).isInstanceOf(ResponseStatusException.class);
		verifyNoInteractions(grants);
	}
	@Test void organizationReadUsesOnlyNodeTenant() {
		OrgUnit unit = new OrgUnit(tenant.getId(), "team", "팀", OrgUnitStatus.INACTIVE);
		when(units.findByTenantId(tenant.getId())).thenReturn(List.of(unit));
		var result = service.organizations("manager", work.getId());
		assertThat(result).containsExactly(new WorkspaceManagementQueryService.OrgView(unit.getId(), "팀", OrgUnitStatus.INACTIVE));
		verify(units).findByTenantId(tenant.getId());
	}
	@Test void roomReadHasOnlyCollabMetadataAndFiltersForeignTenant() {
		Thr room = Thr.collab(UUID.randomUUID(), "비참여 방"); room.placeIn(tenant.getId(), work.getId());
		Thr foreign = Thr.collab(UUID.randomUUID(), "다른 Tenant"); foreign.placeIn(UUID.randomUUID(), work.getId());
		when(threads.findByWorkspaceNodeIdAndKind(work.getId(), ThrKind.COLLAB)).thenReturn(List.of(room, foreign));
		assertThat(service.threads("manager", work.getId())).extracting(WorkspaceManagementQueryService.ThreadView::title).containsExactly("비참여 방");
	}
	@Test void platformCanSelectInactiveTenantAndReadDeclaredOrganization() {
		tenant.reconcileStatus(TenantStatus.INACTIVE);
		when(tenants.findAll()).thenReturn(List.of(tenant));
		when(tenants.findByKey("default")).thenReturn(Optional.of(tenant));
		when(units.findByTenantId(tenant.getId())).thenReturn(List.of());
		assertThat(service.tenants().get(0).status()).isEqualTo(TenantStatus.INACTIVE);
		assertThat(service.platformOrganizations("default")).isEmpty();
		verifyNoInteractions(manage);
	}
}
