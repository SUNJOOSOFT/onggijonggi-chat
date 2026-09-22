package com.onggijonggi.common.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Class Name : StagingUserCurrentTenant.java
 * Description : Last observed enabled Keycloak user tenant for cutover validation.
 */
@Entity
@Table(name = "stg_user_cur_tnn")
public class StagingUserCurrentTenant {

	@Id
	@Column(name = "user_id")
	private UUID userId;
	@Column(name = "tnn_key")
	private String tenantKey;

	protected StagingUserCurrentTenant() {
	}

	public StagingUserCurrentTenant(UUID userId, String tenantKey) {
		this.userId = userId;
		this.tenantKey = tenantKey;
	}

	public UUID getUserId() { return userId; }
	public String getTenantKey() { return tenantKey; }
}
