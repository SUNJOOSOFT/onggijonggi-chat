package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.authz.WorkspaceRole;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : WorkspaceManagementQueryService.java
 * Description : 관리 화면용 최소 조회. 직접 MANAGE와 플랫폼 경계를 분리하고 숨겨진 노드·사람·방 내용을 반환하지 않는다.
 *               호출자는 JPA 조회를 boundedElastic에서 실행한다. 변경·감사 INSERT는 기존 관리 서비스가 담당한다.
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceManagementQueryService {
	private final WorkspaceNodeRepository nodes;
	private final TenantRepository tenants;
	private final OrgUnitRepository orgUnits;
	private final WorkspaceGrantRepository grants;
	private final ThrRepository threads;
	private final DirectManageAuthorizer manage;
	private final RbacBootstrapConfigReader config;

	public record NodeView(UUID id, UUID parentId, String name, WorkspaceNodeKind kind, WorkspaceNodeStatus status,
			boolean declared, List<String> actions) { }
	public record OrgView(UUID id, String name, OrgUnitStatus status) { }
	public record PlatformOrgView(UUID id, String key, String name, OrgUnitStatus status, boolean declared) { }
	public record GrantView(UUID id, UUID orgUnitId, String orgUnitName, OrgUnitStatus orgUnitStatus,
			WorkspaceRole role, boolean declared) { }
	public record ThreadView(UUID id, String title, com.onggijonggi.common.chat.domain.ThrStatus status) { }
	public record TenantView(UUID id, String key, String name, TenantStatus status) { }

	public WorkspaceManagementQueryService(WorkspaceNodeRepository nodes, TenantRepository tenants,
			OrgUnitRepository orgUnits, WorkspaceGrantRepository grants, ThrRepository threads,
			DirectManageAuthorizer manage, RbacBootstrapConfigReader config) {
		this.nodes = nodes; this.tenants = tenants; this.orgUnits = orgUnits; this.grants = grants;
		this.threads = threads; this.manage = manage; this.config = config;
	}

	public List<NodeView> workspaces(String subject) {
		List<WorkspaceNode> visible = nodes.findAll().stream().filter(node -> manage.canReadManagement(subject, node)).toList();
		Set<UUID> ids = new HashSet<>();
		visible.forEach(node -> ids.add(node.getId()));
		Map<UUID, Optional<RbacBootstrapSpec.TenantSpec>> specs = new HashMap<>();
		return visible.stream().sorted(Comparator.comparing(WorkspaceNode::getName).thenComparing(WorkspaceNode::getId))
				.map(node -> {
					Optional<RbacBootstrapSpec.TenantSpec> spec = specs.computeIfAbsent(node.getTenantId(), this::specification);
					boolean declared = spec.stream().flatMap(value -> value.nodes().stream()).anyMatch(value -> value.key().equals(node.getKey()));
					List<String> actions = new ArrayList<>();
					if (node.getStatus() == WorkspaceNodeStatus.ACTIVE) {
						actions.add("AUDIT");
						if (node.getKind() != WorkspaceNodeKind.ROOT) actions.add("GRANTS");
						if (node.getKind() != WorkspaceNodeKind.COMMON && node.getPath().length < 11) actions.add("CREATE");
						if (!declared && regular(node)) actions.addAll(List.of("RENAME", "REPARENT", "DEACTIVATE"));
					} else if (!declared && regular(node) && nodes.findById(node.getParentId())
							.filter(parent -> parent.getStatus() == WorkspaceNodeStatus.ACTIVE).isPresent()) actions.add("REACTIVATE");
					return new NodeView(node.getId(), ids.contains(node.getParentId()) ? node.getParentId() : null,
							node.getName(), node.getKind(), node.getStatus(), declared, List.copyOf(actions));
				}).toList();
	}

	public List<OrgView> organizations(String subject, UUID nodeId) {
		WorkspaceNode node = require(subject, nodeId);
		return orgUnits.findByTenantId(node.getTenantId()).stream().sorted(Comparator.comparing(OrgUnit::getName))
				.map(unit -> new OrgView(unit.getId(), unit.getName(), unit.getStatus())).toList();
	}

	public List<GrantView> grants(String subject, UUID nodeId) {
		WorkspaceNode node = require(subject, nodeId);
		Optional<RbacBootstrapSpec.TenantSpec> spec = specification(node.getTenantId());
		return grants.findByWorkspaceNodeId(nodeId).stream().filter(grant -> grant.getTenantId().equals(node.getTenantId()))
				.map(grant -> {
					OrgUnit unit = orgUnits.findById(grant.getOrgUnitId()).filter(value -> value.getTenantId().equals(node.getTenantId()))
							.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
					boolean declared = spec.stream().flatMap(value -> value.grants().stream()).anyMatch(value ->
							value.node().equals(node.getKey()) && value.orgUnit().equals(unit.getKey()) && value.role().equals(grant.getRole().name()));
					return new GrantView(grant.getId(), unit.getId(), unit.getName(), unit.getStatus(), grant.getRole(), declared);
				}).sorted(Comparator.comparing(GrantView::orgUnitName).thenComparing(GrantView::id)).toList();
	}

	public List<ThreadView> threads(String subject, UUID nodeId) {
		WorkspaceNode node = require(subject, nodeId);
		return threads.findByWorkspaceNodeIdAndKind(nodeId, ThrKind.COLLAB).stream()
				.filter(thread -> node.getTenantId().equals(thread.getTenantId()))
				.map(thread -> new ThreadView(thread.getId(), thread.getTitle(), thread.getStatus()))
				.sorted(Comparator.comparing(ThreadView::title).thenComparing(ThreadView::id)).toList();
	}

	public List<TenantView> tenants() {
		return tenants.findAll().stream().sorted(Comparator.comparing(Tenant::getKey))
				.map(tenant -> new TenantView(tenant.getId(), tenant.getKey(), tenant.getName(), tenant.getStatus())).toList();
	}

	public List<PlatformOrgView> platformOrganizations(String tenantKey) {
		Tenant tenant = tenants.findByKey(tenantKey).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Optional<RbacBootstrapSpec.TenantSpec> spec = specification(tenant.getId());
		return orgUnits.findByTenantId(tenant.getId()).stream().sorted(Comparator.comparing(OrgUnit::getName))
				.map(unit -> new PlatformOrgView(unit.getId(), unit.getKey(), unit.getName(), unit.getStatus(),
						spec.stream().flatMap(value -> value.orgUnits().stream()).anyMatch(value -> value.key().equals(unit.getKey())))).toList();
	}

	private WorkspaceNode require(String subject, UUID nodeId) {
		WorkspaceNode node = nodes.findById(nodeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (node.getStatus() != WorkspaceNodeStatus.ACTIVE || !manage.canReadManagement(subject, node)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
		return node;
	}
	private Optional<RbacBootstrapSpec.TenantSpec> specification(UUID tenantId) {
		String key = tenants.findById(tenantId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).getKey();
		return config.loadWithFingerprint().stream().flatMap(value -> value.spec().tenants().stream())
				.filter(value -> value.key().equals(key)).findFirst();
	}
	private static boolean regular(WorkspaceNode node) {
		return node.getKind() == WorkspaceNodeKind.ORG || node.getKind() == WorkspaceNodeKind.WORK;
	}
}
