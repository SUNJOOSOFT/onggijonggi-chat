package com.onggijonggi.common.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Class Name : StagingThreadTenant.java
 * Description : Explicit tenant assignment used by the phase 2 thread backfill.
 */
@Entity
@Table(name = "stg_thr_tnn")
public class StagingThreadTenant {

	@Id
	@Column(name = "thr_id")
	private UUID threadId;
	@Column(name = "tnn_key")
	private String tenantKey;

	protected StagingThreadTenant() {
	}

	public StagingThreadTenant(UUID threadId, String tenantKey) {
		this.threadId = threadId;
		this.tenantKey = tenantKey;
	}

	public UUID getThreadId() { return threadId; }
	public String getTenantKey() { return tenantKey; }
}
