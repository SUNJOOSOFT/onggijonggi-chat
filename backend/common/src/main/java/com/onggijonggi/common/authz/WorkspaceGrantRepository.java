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

	/** 한 노드에 직접 걸린 부여만. 부모 노드의 부여는 포함하지 않는다(권한은 상속되지 않는다, #299). */
	List<WorkspaceGrant> findByWorkspaceNodeId(UUID workspaceNodeId);

	/** 한 org-unit이 받은 부여들. org-unit 변경 뒤 영향받는 노드를 찾는다(#260). */
	List<WorkspaceGrant> findByOrgUnitId(UUID orgUnitId);
}
