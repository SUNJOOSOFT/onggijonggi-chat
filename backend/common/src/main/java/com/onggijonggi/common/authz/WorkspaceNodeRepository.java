package com.onggijonggi.common.authz;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : WorkspaceNodeRepository.java
 * Description : wrk_node 레포지토리.
 */
public interface WorkspaceNodeRepository extends JpaRepository<WorkspaceNode, UUID> {

	Optional<WorkspaceNode> findByTenantIdAndKey(UUID tenantId, String key);

	List<WorkspaceNode> findByTenantId(UUID tenantId);
}
