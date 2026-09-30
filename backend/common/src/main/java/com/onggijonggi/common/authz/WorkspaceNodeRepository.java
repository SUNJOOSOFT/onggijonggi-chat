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

	/** 한 노드의 바로 아래 노드들(상태 무관). 형제 이름 중복 확인에 쓴다(#260). */
	List<WorkspaceNode> findByParentId(UUID parentId);

	/** 자식이 하나라도 있나(상태 무관). reparent는 leaf만 옮긴다(#260). */
	boolean existsByParentId(UUID parentId);
}
