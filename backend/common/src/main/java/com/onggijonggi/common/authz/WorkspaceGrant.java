package com.onggijonggi.common.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : WorkspaceGrant.java
 * Description : Authoritative org-unit and role grant scoped to one workspace.
 */
@Entity
@Table(name = "wrk_grn")
public class WorkspaceGrant {

	@Id
	private UUID id;
	@Column(name = "tnn_id", nullable = false)
	private UUID tenantId;
	@Column(name = "org_unit_id", nullable = false)
	private UUID orgUnitId;
	@Column(name = "wrk_node_id", nullable = false)
	private UUID workspaceNodeId;
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private WorkspaceRole role;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected WorkspaceGrant() {
	}

	public WorkspaceGrant(UUID tenantId, UUID orgUnitId, UUID workspaceNodeId, WorkspaceRole role) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.orgUnitId = orgUnitId;
		this.workspaceNodeId = workspaceNodeId;
		this.role = role;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() { return id; }
	public UUID getTenantId() { return tenantId; }
	public UUID getOrgUnitId() { return orgUnitId; }
	public UUID getWorkspaceNodeId() { return workspaceNodeId; }
	public WorkspaceRole getRole() { return role; }
}
