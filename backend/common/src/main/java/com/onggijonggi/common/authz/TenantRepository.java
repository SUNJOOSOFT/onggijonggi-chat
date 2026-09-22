package com.onggijonggi.common.authz;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Class Name : TenantRepository.java
 * Description : tnn 레포지토리.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

	Optional<Tenant> findByKey(String key);

	/**
	 * Tenant 단위 잠금이다. 권한 변경 트랜잭션은 첫 문장에서 이 메서드로 `tnn` 행을 `SELECT … FOR UPDATE` 잠근다(0001 7.0).
	 * JPA 잠금 힌트(PESSIMISTIC_WRITE)는 PostgreSQL 방언에서 `FOR NO KEY UPDATE`로 나갈 수 있어 설계의 문장이 그대로 나가도록
	 * 네이티브 쿼리로 고정한다. 잠금은 트랜잭션과 함께 풀리므로 advisory lock을 쓰지 않는다.
	 */
	@Query(value = "select * from tnn where tnn_key = :key for update", nativeQuery = true)
	Optional<Tenant> findByKeyForUpdate(@Param("key") String key);
}
