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
}
