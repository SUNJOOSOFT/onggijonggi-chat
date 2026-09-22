package com.onggijonggi.common.authz;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : WorkspaceGrantRepository.java
 * Description : wrk_grn 레포지토리.
 */
public interface WorkspaceGrantRepository extends JpaRepository<WorkspaceGrant, UUID> {

	List<WorkspaceGrant> findByTenantId(UUID tenantId);
}
