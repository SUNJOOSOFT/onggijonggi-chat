package com.onggijonggi.common.authz;

import java.io.Serializable;
import java.util.UUID;

/**
 * Class Name : StagingUserTenantId.java
 * Description : Composite identifier for a staged user-to-tenant mapping.
 */
public record StagingUserTenantId(UUID userId, String tenantKey) implements Serializable {
}
