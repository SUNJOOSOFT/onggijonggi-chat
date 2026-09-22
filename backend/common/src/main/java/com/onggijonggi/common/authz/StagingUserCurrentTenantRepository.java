package com.onggijonggi.common.authz;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : StagingUserCurrentTenantRepository.java
 * Description : Observed Keycloak user tenant staging persistence.
 */
public interface StagingUserCurrentTenantRepository extends JpaRepository<StagingUserCurrentTenant, UUID> {
}
