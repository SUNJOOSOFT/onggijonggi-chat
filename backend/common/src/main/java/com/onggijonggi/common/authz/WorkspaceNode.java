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
 * Class Name : WorkspaceNode.java
 * Description : Tenant-local ROOT/COMMON/ORG/WORK tree node in wrk_node.
 */
@Entity
@Table(name = "wrk_node")
public class WorkspaceNode {

	@Id
	private UUID id;
	@Column(name = "tnn_id", nullable = false)
	private UUID tenantId;
	@Column(name = "prn_id")
	private UUID parentId;
	@Column(name = "node_key", nullable = false, updatable = false)
	private String key;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private WorkspaceNodeKind kind;
	@Column(nullable = false)
	private String name;
	@Column(nullable = false)
	private UUID[] path;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private WorkspaceNodeStatus status;
	@Column(name = "inactive_at")
	private Instant inactiveAt;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected WorkspaceNode() {
	}

	private WorkspaceNode(UUID tenantId, UUID parentId, String key, WorkspaceNodeKind kind, String name,
			UUID[] path) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.parentId = parentId;
		this.key = key;
		this.kind = kind;
		this.name = name;
		this.path = path;
		this.status = WorkspaceNodeStatus.ACTIVE;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static WorkspaceNode root(UUID tenantId, String name) {
		WorkspaceNode root = new WorkspaceNode(tenantId, null, "root", WorkspaceNodeKind.ROOT, name, null);
		root.path = new UUID[] {root.id};
		return root;
	}

	public static WorkspaceNode common(UUID tenantId, UUID rootId, UUID[] rootPath, String name) {
		WorkspaceNode common = new WorkspaceNode(tenantId, rootId, "common", WorkspaceNodeKind.COMMON, name, null);
		common.path = new UUID[] {rootPath[0], common.id};
		return common;
	}

	public static WorkspaceNode child(UUID tenantId, UUID parentId, UUID[] parentPath, String key,
			WorkspaceNodeKind kind, String name, WorkspaceNodeStatus status) {
		WorkspaceNode child = new WorkspaceNode(tenantId, parentId, key, kind, name, null);
		child.path = java.util.Arrays.copyOf(parentPath, parentPath.length + 1);
		child.path[parentPath.length] = child.id;
		child.status = status;
		child.inactiveAt = status == WorkspaceNodeStatus.INACTIVE ? Instant.now() : null;
		return child;
	}

	public UUID getId() { return id; }
	public UUID getTenantId() { return tenantId; }
	public UUID getParentId() { return parentId; }
	public String getKey() { return key; }
	public WorkspaceNodeKind getKind() { return kind; }
	public String getName() { return name; }
	public UUID[] getPath() { return path.clone(); }
	public WorkspaceNodeStatus getStatus() { return status; }
	public Instant getInactiveAt() { return inactiveAt; }

	public void rename(String name) {
		this.name = name;
		this.updatedAt = Instant.now();
	}

	/** 새 부모 아래로 옮긴다. 자식이 없는 leaf만 옮길 수 있다(DB trigger가 강제한다). */
	public void moveTo(UUID newParentId, UUID[] newParentPath) {
		this.parentId = newParentId;
		this.path = java.util.Arrays.copyOf(newParentPath, newParentPath.length + 1);
		this.path[newParentPath.length] = this.id;
		this.updatedAt = Instant.now();
	}

	public void reconcileStatus(WorkspaceNodeStatus status) {
		this.status = status;
		this.inactiveAt = status == WorkspaceNodeStatus.INACTIVE ? Instant.now() : null;
		this.updatedAt = Instant.now();
	}
}
