package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : ThrMbrRepository.java
 * Description : thr_mbr JPA 레포지토리. 조회는 전부 status를 조건에 넣는다 — 끝난 참가 행이 같은
 *               (thr_id, user_id)로 함께 남아 있어서, 빼면 나간 사람도 참가자로 잡힌다.
 */
public interface ThrMbrRepository extends JpaRepository<ThrMbr, UUID> {

	/** 방 입장 인가에 쓴다. 활성 참가자 부분 유니크 인덱스가 있어 조건에 맞는 행은 최대 하나다. */
	boolean existsByThrIdAndUserIdAndStatus(UUID thrId, UUID userId, ThrMbrStatus status);

	List<ThrMbr> findByUserIdAndStatus(UUID userId, ThrMbrStatus status);

	/**
	* 위 exists와 같은 이유로 결과는 최대 하나다. 역할 판정·종료 처리에 행 자체가 필요할 때 쓰고,
	* HUMAN 메시지의 thr_mbr_id를 채워 "그때 그 참여"를 가리키게 하는 데도 쓴다(msg 테이블 설계).
	*/
	Optional<ThrMbr> findByThrIdAndUserIdAndStatus(UUID thrId, UUID userId, ThrMbrStatus status);

	List<ThrMbr> findByThrIdAndStatus(UUID thrId, ThrMbrStatus status);

	/**
	* transferOwnership: 두 참가 행의 role을 한 UPDATE로 맞바꾼다. 서비스에서 두 번 저장하면
	* 그 사이에 OWNER가 0명이거나 2명인 상태가 보일 수 있다. 이 전이는 한 조건부 UPDATE로 경쟁 조건까지
	* 표현할 수 있으므로, 레포 메서드 하나를 원자 단위로 쓴다.
	* WHERE에 지금 role·status까지 넣는 것은, 다른 위임이 먼저 끝난 뒤에도 두 행이 그대로 갱신되면
	* 호출부의 행 수 검증이 경합을 못 잡기 때문이다.
	* @param thrId 대상 Thread
	* @param fromUserId 지금 OWNER인 사용자
	* @param toUserId OWNER가 될 ACTIVE MEMBER
	* @return 실제로 바뀐 행 수. 2가 아니면 경합으로 본다.
	*/
	@Modifying(clearAutomatically = true)
	@Transactional
	@Query("""
			update ThrMbr m
			   set m.role = case when m.userId = :fromUserId
			                     then com.onggijonggi.common.chat.domain.ThrMbrRole.MEMBER
			                     else com.onggijonggi.common.chat.domain.ThrMbrRole.OWNER end
			 where m.thrId = :thrId
			   and m.status = com.onggijonggi.common.chat.domain.ThrMbrStatus.ACTIVE
			   and ((m.userId = :fromUserId
			         and m.role = com.onggijonggi.common.chat.domain.ThrMbrRole.OWNER)
			     or (m.userId = :toUserId
			         and m.role = com.onggijonggi.common.chat.domain.ThrMbrRole.MEMBER))
			""")
	int transferOwnership(@Param("thrId") UUID thrId, @Param("fromUserId") UUID fromUserId,
			@Param("toUserId") UUID toUserId);

	/** 역할 조건이 붙는 조회. 위임 대상이 ACTIVE MEMBER인지 확인하는 데 쓴다. */
	Optional<ThrMbr> findByThrIdAndUserIdAndRoleAndStatus(UUID thrId, UUID userId, ThrMbrRole role,
			ThrMbrStatus status);

	/** 계정 비활성화로 OWNER 자리가 비게 될 때, 위임할 ACTIVE MEMBER가 있는지 찾는다(#132). */
	Optional<ThrMbr> findFirstByThrIdAndRoleAndStatus(UUID thrId, ThrMbrRole role, ThrMbrStatus status);

}
