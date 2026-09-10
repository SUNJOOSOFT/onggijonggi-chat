package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.ThrInv;
import com.onggijonggi.common.chat.domain.ThrInvStatus;
import com.onggijonggi.common.chat.persistence.ThrInvRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Class Name : InvitationAcceptanceService.java
 * Description : 첫 로그인 때 그 사람 앞으로 온 대기 초대를 실제 참가로 바꾼다(이슈 #127).
 *
 *               UserIdentityService가 app_user 행을 <b>새로 만든 경우에만</b> 부른다 — 그 순간이
 *               곧 첫 로그인이라, 매 요청마다 초대 테이블을 훑지 않는다. 그쪽에 로직을 직접 넣지
 *               않은 것은 UserIdentityService가 모든 인증 경로(HTTP·WS)가 지나는 자리이기 때문이다.
 *
 *               이 클래스는 <b>트랜잭션을 열지 않는다.</b> 초대 한 건이 트랜잭션 단위고, 그
 *               경계는 PendingInvitationAcceptance가 REQUIRES_NEW로 갖는다 — 한 방의 유니크
 *               경합이 다른 방들의 전환까지 되돌리지 않게 하려는 것이다. 여기서 @Transactional을
 *               다시 걸면 그 격리가 무의미해진다.
 *
 *               전환은 ACTIVE 방에만 한다. 잠기거나 보관된 방의 초대는 PENDING으로 남겨 둔다 —
 *               초대 자체를 막는 requireWritableThread와 같은 기준이다. 전환 시도가 이 사람의 첫
 *               로그인 한 번뿐이고 Thr에는 ACTIVE로 되돌아오는 경로가 없어(lock()·archive()만
 *               있다), 그 초대는 사실상 계속 대기한다 — 초대 수명 관리는 후속 몫이다.
 */
@Service
public class InvitationAcceptanceService {

	private final ThrInvRepository thrInvRepository;
	private final PendingInvitationAcceptance pendingInvitationAcceptance;

	public InvitationAcceptanceService(ThrInvRepository thrInvRepository,
			PendingInvitationAcceptance pendingInvitationAcceptance) {
		this.thrInvRepository = thrInvRepository;
		this.pendingInvitationAcceptance = pendingInvitationAcceptance;
	}

	/**
	 * 대기 초대를 참가로 바꾼다. 초대가 여럿(방이 여럿)일 수 있어 건별로 돌린다.
	 *
	 * 유니크 위반이 올라오면 한 번 다시 시도한다. 그 위반은 "다른 경로가 이 사람을 같은 방에 먼저
	 * 넣었다"는 뜻인데, 위반이 난 트랜잭션은 통째로 되돌아가 초대가 PENDING으로 남는다 — 그냥
	 * 넘기면 실제로는 참가해 있는데 초대만 영구히 대기하는 상태가 된다. 재시도의 존재 검사는
	 * 이긴 쪽이 만든 행을 보므로 INSERT를 건너뛰고 초대만 닫는다.
	 *
	 * JPA는 블로킹이라 호출부가 이미 boundedElastic 위에 있어야 한다.
	 */
	public void acceptPendingBlocking(UUID userId, String subject) {
		for (ThrInv invitation : thrInvRepository.findBySubjAndStatus(subject, ThrInvStatus.PENDING)) {
			acceptOneBlocking(invitation.getId(), userId);
		}
	}

	/**
	 * 초대 한 건만 전환한다. 첫 로그인 경로 외에 ThreadParticipantService의 초대 경로도 부른다 —
	 * 초대를 남기는 사이에 대상이 이미 첫 로그인을 마쳤다면 아무도 전환하지 않기 때문이다.
	 *
	 * 재시도가 이 자리에 있는 이유는 REQUIRES_NEW 경계 <b>밖</b>이어야 하기 때문이다. 제약 위반이
	 * 난 트랜잭션은 rollback-only라 그 안에서는 이어서 진행할 수 없다.
	 */
	public void acceptOneBlocking(UUID invitationId, UUID userId) {
		try {
			pendingInvitationAcceptance.acceptOneBlocking(invitationId, userId);
		} catch (DataIntegrityViolationException raced) {
			pendingInvitationAcceptance.acceptOneBlocking(invitationId, userId);
		}
	}

}
