package com.onggijonggi.common.authz;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : StagingThreadTenantRepository.java
 * Description : Staged thread-to-tenant assignment persistence.
 */
public interface StagingThreadTenantRepository extends JpaRepository<StagingThreadTenant, UUID> {
}
