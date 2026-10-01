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
 * Class Name : OrgUnit.java
 * Description : Tenant-local registry of immutable Keycloak org_units claim keys.
 */
@Entity
@Table(name = "org_unit")
public class OrgUnit {

	@Id
	private UUID id;
	@Column(name = "tnn_id", nullable = false)
	private UUID tenantId;
	@Column(name = "org_unit_key", nullable = false, updatable = false)
	private String key;
	@Column(nullable = false)
	private String name;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrgUnitStatus status;
	@Column(name = "inactive_at")
	private Instant inactiveAt;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected OrgUnit() {
	}

	public OrgUnit(UUID tenantId, String key, String name, OrgUnitStatus status) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.key = key;
		this.name = name;
		this.status = status;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
		this.inactiveAt = status == OrgUnitStatus.INACTIVE ? now : null;
	}

	public UUID getId() { return id; }
	public UUID getTenantId() { return tenantId; }
	public String getKey() { return key; }
	public String getName() { return name; }
	public OrgUnitStatus getStatus() { return status; }
	public Instant getInactiveAt() { return inactiveAt; }

	/**
	 * 이 팀이 그 Tenant에 속하고 ACTIVE인가. 배정이 가리키는 팀을 판정에 쓸 수 있는지 볼 때의 공통 조건이다 —
	 * 다른 Tenant의 팀이거나 비활성 팀이면 그 배정은 판정에 쓰지 않는다.
	 */
	public boolean isActiveIn(UUID tenantId) {
		return this.tenantId.equals(tenantId) && status == OrgUnitStatus.ACTIVE;
	}

	public void rename(String name) {
		this.name = name;
		this.updatedAt = Instant.now();
	}

	public void reconcileStatus(OrgUnitStatus status) {
		this.status = status;
		this.inactiveAt = status == OrgUnitStatus.INACTIVE ? Instant.now() : null;
		this.updatedAt = Instant.now();
	}
}
