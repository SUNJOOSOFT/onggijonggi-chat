package com.onggijonggi.common.authz;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : RankGrantRepository.java
 * Description : rank_grn 레포지토리.
 */
public interface RankGrantRepository extends JpaRepository<RankGrant, UUID> {

	List<RankGrant> findByTenantId(UUID tenantId);

	/** 한 노드에 직접 걸린 부여만. 부모 노드의 부여는 포함하지 않는다(권한은 상속되지 않는다, #299). */
	List<RankGrant> findByWorkspaceNodeId(UUID workspaceNodeId);
}
