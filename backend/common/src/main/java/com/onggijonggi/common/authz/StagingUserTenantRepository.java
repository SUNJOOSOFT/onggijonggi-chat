package com.onggijonggi.common.authz;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : StagingUserTenantRepository.java
 * Description : Staged user-to-tenant mapping persistence.
 */
public interface StagingUserTenantRepository extends JpaRepository<StagingUserTenant, StagingUserTenantId> {
}
