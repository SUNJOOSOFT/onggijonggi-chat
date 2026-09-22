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
 * Class Name : Tenant.java
 * Description : v0.3 tenant policy boundary stored in tnn.
 */
@Entity
@Table(name = "tnn")
public class Tenant {

	@Id
	private UUID id;

	@Column(name = "tnn_key", nullable = false, unique = true, updatable = false)
	private String key;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TenantStatus status;

	@Column(name = "inactive_at")
	private Instant inactiveAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Tenant() {
	}

	public Tenant(String key, String name, TenantStatus status) {
		this.id = UUID.randomUUID();
		this.key = key;
		this.name = name;
		this.status = status;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
		this.inactiveAt = status == TenantStatus.INACTIVE ? now : null;
	}

	public UUID getId() { return id; }
	public String getKey() { return key; }
	public String getName() { return name; }
	public TenantStatus getStatus() { return status; }
	public Instant getInactiveAt() { return inactiveAt; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }

	public void rename(String name) {
		this.name = name;
		this.updatedAt = Instant.now();
	}

	public void reconcileStatus(TenantStatus status) {
		this.status = status;
		this.inactiveAt = status == TenantStatus.INACTIVE ? Instant.now() : null;
		this.updatedAt = Instant.now();
	}
}
