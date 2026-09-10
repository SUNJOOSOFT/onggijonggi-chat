package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrInv;
import com.onggijonggi.common.chat.domain.ThrInvStatus;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrInvRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : PendingInvitationAcceptance.java
 * Description : 대기 초대 <b>하나</b>를 참가로 바꾸는 트랜잭션 단위(이슈 #127).
 *
 *               InvitationAcceptanceService의 루프에서 초대 한 건마다 부른다. 별 빈으로 뺀 것은
 *               Spring AOP가 자기호출(this.method())에는 프록시를 적용하지 않아, 같은 클래스
 *               안에서는 REQUIRES_NEW가 아예 걸리지 않기 때문이다.
 *
 *               트랜잭션 경계가 초대 <b>한 건</b>인 이유. 참가 행 INSERT와 초대의 ACCEPTED
 *               갱신은 함께 커밋되어야 한다 — 한쪽만 남으면 참가 없이 수락된 초대나 수락되지
 *               않은 참가가 된다. 반대로 경계를 배치 전체로 넓히면 한 방의 유니크 경합이 나머지
 *               방들의 갱신까지 되돌린다. 그 사이가 이 단위다.
 *
 *               활성 참가자 유니크(ux_thr_mbr_active_participant) 위반은 여기서 잡지 않고 그대로
 *               올려보낸다. 제약 위반 후의 세션은 rollback-only라 같은 트랜잭션에서 계속 진행할
 *               수 없어, 잡을 자리는 이 경계 밖이다.
 *
 *               JPA는 블로킹이라 호출부가 이미 boundedElastic 위에 있어야 한다.
 */
@Service
public class PendingInvitationAcceptance {

	/** end_rsn 고정 토큰 — ThreadParticipantService의 SELF_LEAVE·OWNER_REVOKED와 같은 자리다. */
	static final String FIRST_LOGIN = "FIRST_LOGIN";

	private final ThrInvRepository thrInvRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final ThrRepository thrRepository;

	public PendingInvitationAcceptance(ThrInvRepository thrInvRepository,
			ThrMbrRepository thrMbrRepository, ThrRepository thrRepository) {
		this.thrInvRepository = thrInvRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.thrRepository = thrRepository;
	}

	/**
	 * 초대 한 건을 참가로 바꾼다. 초대자(createdByUserId)를 그대로 참가 행의 created_by_user_id로
	 * 옮긴다 — 나중에 "누가 이 사람을 들였나"를 물으면 초대한 사람이 답이어야 한다.
	 *
	 * 초대는 id로 다시 읽는다. 루프가 넘겨준 엔티티는 이 트랜잭션의 영속성 컨텍스트 밖이라,
	 * 여기서 건 변경이 flush되지 않는다.
	 *
	 * @param invitationId 전환할 대기 초대의 id
	 * @param userId 방금 만들어진 app_user 행의 id
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void acceptOneBlocking(UUID invitationId, UUID userId) {
		ThrInv invitation = thrInvRepository.findById(invitationId).orElse(null);
		if (invitation == null || invitation.getStatus() != ThrInvStatus.PENDING) {
			return;
		}
		if (!isOpen(invitation.getThrId())) {
			return;
		}
		if (!thrMbrRepository.existsByThrIdAndUserIdAndStatus(invitation.getThrId(), userId,
				ThrMbrStatus.ACTIVE)) {
			thrMbrRepository.save(new ThrMbr(invitation.getThrId(), userId, ThrMbrRole.MEMBER,
					invitation.getCreatedByUserId()));
		}
		invitation.end(ThrInvStatus.ACCEPTED, FIRST_LOGIN);
		thrInvRepository.save(invitation);
	}

	/** 방이 사라졌으면(cascade로 초대도 지워지므로 보통 여기 오지 않는다) 전환하지 않는다. */
	private boolean isOpen(UUID threadId) {
		return thrRepository.findById(threadId)
				.map(Thr::getStatus)
				.filter(status -> status == ThrStatus.ACTIVE)
				.isPresent();
	}

}
