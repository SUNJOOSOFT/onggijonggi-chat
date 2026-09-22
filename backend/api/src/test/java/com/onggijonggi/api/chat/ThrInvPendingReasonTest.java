package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.common.chat.domain.ThrInv;
import com.onggijonggi.common.chat.domain.ThrInvStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Class Name : ThrInvPendingReasonTest.java
 * Description : 초대가 PENDING을 벗어날 때 대기 사유(pnd_rsn)가 함께 비워지는지 검증한다(DB-TST-065 ③).
 *               DB CHECK가 "PENDING일 때만 pnd_rsn을 가질 수 있다"를 강제하므로, 수락·철회가 이 값을 남기면
 *               저장이 CHECK 위반으로 실패한다. 세 종료 경로(첫 로그인 수락, 초대자 퇴사, OWNER 철회)가 모두
 *               ThrInv.end()를 거친다.
 */
class ThrInvPendingReasonTest {

	@Test
	void acceptingAnInvitationClearsThePendingReason() {
		ThrInv invitation = invitationWaitingWithReason();

		invitation.end(ThrInvStatus.ACCEPTED, "FIRST_LOGIN");

		assertThat(invitation.getPendingReason()).isNull();
		assertThat(invitation.getStatus()).isEqualTo(ThrInvStatus.ACCEPTED);
	}

	@Test
	void revokingAnInvitationClearsThePendingReason() {
		ThrInv invitation = invitationWaitingWithReason();

		invitation.end(ThrInvStatus.REVOKED, "OWNER_REVOKED");

		assertThat(invitation.getPendingReason()).isNull();
		assertThat(invitation.getStatus()).isEqualTo(ThrInvStatus.REVOKED);
	}

	private ThrInv invitationWaitingWithReason() {
		ThrInv invitation = new ThrInv(UUID.randomUUID(), "invitee-subject", UUID.randomUUID());
		ReflectionTestUtils.setField(invitation, "pendingReason", "NO_ACCESS");
		return invitation;
	}

}
