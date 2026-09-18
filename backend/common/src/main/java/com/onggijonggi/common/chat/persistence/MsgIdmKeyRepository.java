package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.MsgIdmKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : MsgIdmKeyRepository.java
 * Description : msg_idm_key JPA 레포지토리.
 */
public interface MsgIdmKeyRepository extends JpaRepository<MsgIdmKey, UUID> {

	Optional<MsgIdmKey> findByUserIdAndKey(UUID userId, String key);

	/**
	* 만료된 키를 지우고 곧바로 같은 트랜잭션에서 같은 (user_id, idm_key)로 새 행을 저장해야
	* 하는데(이슈 #233), 영속성 컨텍스트의 일반 delete()는 지금 당장 SQL을 내지 않고 flush
	* 시점까지 미룬다 — Hibernate의 기본 flush 순서가 "모든 INSERT 먼저, DELETE는 나중"이라
	* 자바 코드 순서와 무관하게 새 INSERT가 옛 행이 아직 남은 채로 나가 유니크 인덱스와
	* 충돌한다. `@Modifying` 벌크 쿼리는 영속성 컨텍스트를 거치지 않고 `executeUpdate()`로
	* 즉시 SQL을 내므로 이 순서 문제를 피한다(ThrMbrRepository.transferOwnership과 같은
	* 이유로 같은 패턴을 쓴다).
	*/
	@Modifying(clearAutomatically = true)
	@Transactional
	@Query("delete from MsgIdmKey k where k.userId = :userId and k.key = :key")
	void deleteImmediatelyByUserIdAndKey(@Param("userId") UUID userId, @Param("key") String key);

}
