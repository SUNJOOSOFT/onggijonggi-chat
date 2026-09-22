package com.onggijonggi.common.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Class Name : StagingUserTenant.java
 * Description : Explicit user-to-tenant mapping retained before the phase 2 cutover.
 */
@Entity
@Table(name = "stg_user_tnn")
@IdClass(StagingUserTenantId.class)
public class StagingUserTenant {

	@Id
	@Column(name = "user_id")
	private UUID userId;
	@Id
	@Column(name = "tnn_key")
	private String tenantKey;

	protected StagingUserTenant() {
	}

	public StagingUserTenant(UUID userId, String tenantKey) {
		this.userId = userId;
		this.tenantKey = tenantKey;
	}

	public UUID getUserId() { return userId; }
	public String getTenantKey() { return tenantKey; }
}
