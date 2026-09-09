package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.ThrInv;
import com.onggijonggi.common.chat.domain.ThrInvStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : ThrInvRepository.java
 * Description : thr_inv JPA 레포지토리(이슈 #127). 조회는 전부 status를 조건에 넣는다 — 끝난
 *               초대 행이 같은 (thr_id, subj)로 함께 남아 있어서, 빼면 이미 거둬진 초대도
 *               대기 중으로 잡힌다(ThrMbrRepository와 같은 이유).
 */
public interface ThrInvRepository extends JpaRepository<ThrInv, UUID> {

	/** 초대 멱등 판정. 대기 중 부분 유니크 인덱스가 있어 조건에 맞는 행은 최대 하나다. */
	Optional<ThrInv> findByThrIdAndSubjAndStatus(UUID thrId, String subj, ThrInvStatus status);

	/** 첫 로그인 전환 — 그 사람 앞으로 온 대기 초대를 모두 찾는다. 방이 여럿일 수 있다. */
	List<ThrInv> findBySubjAndStatus(String subj, ThrInvStatus status);

	/** 초대자가 퇴사하면 그가 보낸 대기 초대를 거둔다(#127 결정). */
	List<ThrInv> findByCreatedByUserIdAndStatus(UUID createdByUserId, ThrInvStatus status);

}
