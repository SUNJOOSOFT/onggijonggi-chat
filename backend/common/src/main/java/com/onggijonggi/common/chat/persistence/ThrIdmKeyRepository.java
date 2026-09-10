package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.ThrIdmKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : ThrIdmKeyRepository.java
 * Description : thr_idm_key JPA 레포지토리.
 */
public interface ThrIdmKeyRepository extends JpaRepository<ThrIdmKey, UUID> {

	Optional<ThrIdmKey> findByUserIdAndKey(UUID userId, String key);

}
