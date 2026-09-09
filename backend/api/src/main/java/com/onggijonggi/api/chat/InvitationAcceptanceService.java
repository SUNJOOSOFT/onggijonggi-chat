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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : InvitationAcceptanceService.java
 * Description : 첫 로그인 때 그 사람 앞으로 온 대기 초대를 실제 참가로 바꾼다(이슈 #127).
 *
 *               UserIdentityService가 app_user 행을 <b>새로 만든 경우에만</b> 부른다 — 그 순간이
 *               곧 첫 로그인이라, 매 요청마다 초대 테이블을 훑지 않는다. 그쪽에 로직을 직접 넣지
 *               않은 것은 UserIdentityService가 모든 인증 경로(HTTP·WS)가 지나는 자리이기 때문이다.
 *
 *               전환은 ACTIVE 방에만 한다. 잠기거나 보관된 방의 초대는 PENDING으로 남겨 둔다 —
 *               초대 자체를 막는 requireWritableThread와 같은 기준이고, 방이 다시 열리면 그때
 *               전환된다(다음 로그인이 아니라 이 사람의 첫 로그인 한 번뿐이라는 점은 한계로 남는다).
 */
@Service
public class InvitationAcceptanceService {

	/** end_rsn 고정 토큰 — ThreadParticipantService의 SELF_LEAVE·OWNER_REVOKED와 같은 자리다. */
	private static final String FIRST_LOGIN = "FIRST_LOGIN";

	private final ThrInvRepository thrInvRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final ThrRepository thrRepository;

	public InvitationAcceptanceService(ThrInvRepository thrInvRepository,
			ThrMbrRepository thrMbrRepository, ThrRepository thrRepository) {
		this.thrInvRepository = thrInvRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.thrRepository = thrRepository;
	}

	/**
	 * 대기 초대를 참가로 바꾼다. 초대가 여럿(방이 여럿)일 수 있어 한 트랜잭션으로 묶는다.
	 *
	 * 초대자(createdByUserId)를 그대로 참가 행의 created_by_user_id로 옮긴다 — 나중에 "누가 이
	 * 사람을 들였나"를 물으면 초대한 사람이 답이어야 한다.
	 *
	 * JPA는 블로킹이라 호출부가 이미 boundedElastic 위에 있어야 한다.
	 */
	@Transactional
	public void acceptPendingBlocking(UUID userId, String subject) {
		for (ThrInv invitation : thrInvRepository.findBySubjAndStatus(subject, ThrInvStatus.PENDING)) {
			if (!isOpen(invitation.getThrId())) {
				continue;
			}
			joinIfAbsent(invitation, userId);
			invitation.end(ThrInvStatus.ACCEPTED, FIRST_LOGIN);
			thrInvRepository.save(invitation);
		}
	}

	/** 방이 사라졌으면(cascade로 초대도 지워지므로 보통 여기 오지 않는다) 전환하지 않는다. */
	private boolean isOpen(UUID threadId) {
		return thrRepository.findById(threadId)
				.map(Thr::getStatus)
				.filter(status -> status == ThrStatus.ACTIVE)
				.isPresent();
	}

	/**
	 * 초대를 받아둔 사이에 다른 경로로 이미 참가했을 수 있다 — 그때는 참가 행을 더 만들지 않고
	 * 초대만 닫는다. 활성 참가자 부분 유니크 위반도 같은 뜻이라 성공으로 둔다.
	 */
	private void joinIfAbsent(ThrInv invitation, UUID userId) {
		if (thrMbrRepository.existsByThrIdAndUserIdAndStatus(invitation.getThrId(), userId,
				ThrMbrStatus.ACTIVE)) {
			return;
		}
		try {
			thrMbrRepository.save(new ThrMbr(invitation.getThrId(), userId, ThrMbrRole.MEMBER,
					invitation.getCreatedByUserId()));
		} catch (DataIntegrityViolationException raced) {
			// 이긴 쪽이 만든 활성 참가 행이 이미 있다.
		}
	}

}
