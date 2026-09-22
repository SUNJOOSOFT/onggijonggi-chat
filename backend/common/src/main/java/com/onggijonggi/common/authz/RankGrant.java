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
 * Class Name : RankGrant.java
 * Description : 직급 서열 규칙. rank 이상의 직급이면 workspace 하나를 볼 수 있다. WorkspaceGrant(팀 규칙)와 짝이다.
 */
@Entity
@Table(name = "rank_grn")
public class RankGrant {

	@Id
	private UUID id;
	@Column(name = "tnn_id", nullable = false)
	private UUID tenantId;
	@Column(name = "wrk_node_id", nullable = false)
	private UUID workspaceNodeId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Rank rank;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected RankGrant() {
	}

	public RankGrant(UUID tenantId, UUID workspaceNodeId, Rank rank) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.workspaceNodeId = workspaceNodeId;
		this.rank = rank;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() { return id; }
	public UUID getTenantId() { return tenantId; }
	public UUID getWorkspaceNodeId() { return workspaceNodeId; }
	public Rank getRank() { return rank; }
}
