package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
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
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : WorkspaceManagementServiceTest.java
 * Description : 관리 연산의 선행 조건(하위 트리 전체의 직접 MANAGE, 마지막 직접 ADMIN 보호)이 DB 쓰기와 커밋 뒤 반영 전에 거부되는지 검증한다.
 */
class WorkspaceManagementServiceTest {
	private final Tenant tenant = new Tenant("acme", "Acme", TenantStatus.ACTIVE);
	private final WorkspaceNode root = WorkspaceNode.root(tenant.getId(), "Root");
	private final WorkspaceNode parent = WorkspaceNode.child(tenant.getId(), root.getId(), root.getPath(),
			"parent", WorkspaceNodeKind.ORG, "Parent", WorkspaceNodeStatus.ACTIVE);
	private final WorkspaceNode child = WorkspaceNode.child(tenant.getId(), parent.getId(), parent.getPath(),
			"child", WorkspaceNodeKind.WORK, "Child", WorkspaceNodeStatus.ACTIVE);
	private final WorkspaceManagementService.Actor actor = new WorkspaceManagementService.Actor(
			UUID.randomUUID(), "actor", List.of("ADMIN"), "request-1");
	private final TenantRepository tenants = mock(TenantRepository.class);
	private final WorkspaceNodeRepository nodes = mock(WorkspaceNodeRepository.class);
	private final WorkspaceGrantRepository grants = mock(WorkspaceGrantRepository.class);
	private final OrgUnitRepository units = mock(OrgUnitRepository.class);
	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final ThrRepository threads = mock(ThrRepository.class);
	private final AuthorizationAuditRepository audits = mock(AuthorizationAuditRepository.class);
	private final DirectManageAuthorizer manage = mock(DirectManageAuthorizer.class);
	private final RbacBootstrapConfigReader config = mock(RbacBootstrapConfigReader.class);
	private final RbacPolicyRefresh refresh = mock(RbacPolicyRefresh.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
	private final WorkspaceManagementService service = new WorkspaceManagementService(tenants, nodes, grants, units,
			members, users, threads, audits, manage, config, refresh, entityManager, new JsonMapper(), manager);

	@BeforeEach
	void setUp() {
		when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
		when(tenants.findByKeyForUpdate(tenant.getKey())).thenReturn(Optional.of(tenant));
		when(nodes.findById(parent.getId())).thenReturn(Optional.of(parent));
		when(nodes.findById(child.getId())).thenReturn(Optional.of(child));
		when(config.loadWithFingerprint()).thenReturn(Optional.empty());
	}

	@Test
	void subtreeNeedsDirectManageOnEveryActiveDescendant() {
		when(nodes.findByTenantId(tenant.getId())).thenReturn(List.of(root, parent, child));
		doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(manage).require("actor", child);
		assertThatThrownBy(() -> service.deactivateSubtree(actor, parent.getId()))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(nodes, never()).saveAndFlush(any(WorkspaceNode.class));
		verify(audits, never()).save(any());
		verify(refresh, never()).publish(any(), any(), any());
	}

	@Test
	void lastDirectAdminGrantCannotBeRemoved() {
		WorkspaceGrant grant = new WorkspaceGrant(tenant.getId(), UUID.randomUUID(), child.getId(), WorkspaceRole.ADMIN);
		when(grants.findById(grant.getId())).thenReturn(Optional.of(grant));
		when(grants.findByTenantId(tenant.getId())).thenReturn(List.of(grant));
		assertThatThrownBy(() -> service.removeGrant(actor, grant.getId()))
				.isInstanceOfSatisfying(RbacStateConflictException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
		verify(grants, never()).delete(grant);
		verify(audits, never()).save(any());
	}
}
