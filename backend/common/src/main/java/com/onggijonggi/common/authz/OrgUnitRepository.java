package com.onggijonggi.common.authz;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : OrgUnitRepository.java
 * Description : org_unit 레포지토리.
 */
public interface OrgUnitRepository extends JpaRepository<OrgUnit, UUID> {

	Optional<OrgUnit> findByTenantIdAndKey(UUID tenantId, String key);

	List<OrgUnit> findByTenantId(UUID tenantId);
}
