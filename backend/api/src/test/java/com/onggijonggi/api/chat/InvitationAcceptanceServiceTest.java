package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrInv;
import com.onggijonggi.common.chat.domain.ThrInvStatus;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.ThrInvRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Class Name : InvitationAcceptanceServiceTest.java
 * Description : 첫 로그인 때 대기 초대가 실제 참가로 바뀌는 경로(이슈 #127).
 */
@ExtendWith(MockitoExtension.class)
class InvitationAcceptanceServiceTest {

	@Mock
	private ThrInvRepository thrInvRepository;

	@Mock
	private ThrMbrRepository thrMbrRepository;

	@Mock
	private ThrRepository thrRepository;

	private InvitationAcceptanceService service;

	@BeforeEach
	void setUp() {
		service = new InvitationAcceptanceService(thrInvRepository, thrMbrRepository, thrRepository);
	}

	@Test
	void turnsAPendingInvitationIntoParticipation() {
		UUID inviterId = UUID.randomUUID();
		UUID newUserId = UUID.randomUUID();
		Thr room = Thr.collab(inviterId, "초대받은 방");
		ThrInv invitation = new ThrInv(room.getId(), "first-login-sub", inviterId);

		when(thrInvRepository.findBySubjAndStatus("first-login-sub", ThrInvStatus.PENDING))
				.thenReturn(List.of(invitation));
		when(thrRepository.findById(room.getId())).thenReturn(Optional.of(room));
		when(thrMbrRepository.existsByThrIdAndUserIdAndStatus(room.getId(), newUserId,
				ThrMbrStatus.ACTIVE)).thenReturn(false);

		service.acceptPendingBlocking(newUserId, "first-login-sub");

		ArgumentCaptor<ThrMbr> saved = ArgumentCaptor.forClass(ThrMbr.class);
		verify(thrMbrRepository).save(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo(newUserId);
		assertThat(saved.getValue().getRole()).isEqualTo(ThrMbrRole.MEMBER);
		// "누가 이 사람을 들였나"의 답은 초대한 사람이어야 한다.
		assertThat(saved.getValue().getCreatedByUserId()).isEqualTo(inviterId);

		assertThat(invitation.getStatus()).isEqualTo(ThrInvStatus.ACCEPTED);
		assertThat(invitation.getEndRsn()).isEqualTo("FIRST_LOGIN");
		verify(thrInvRepository).save(invitation);
	}

	@Test
	void closesTheInvitationWithoutASecondRowWhenAlreadyJoined() {
		UUID inviterId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		Thr room = Thr.collab(inviterId, "이미 들어간 방");
		ThrInv invitation = new ThrInv(room.getId(), "already-in-sub", inviterId);

		when(thrInvRepository.findBySubjAndStatus("already-in-sub", ThrInvStatus.PENDING))
				.thenReturn(List.of(invitation));
		when(thrRepository.findById(room.getId())).thenReturn(Optional.of(room));
		// 초대를 받아둔 사이에 다른 경로로 이미 참가했다.
		when(thrMbrRepository.existsByThrIdAndUserIdAndStatus(room.getId(), userId,
				ThrMbrStatus.ACTIVE)).thenReturn(true);

		service.acceptPendingBlocking(userId, "already-in-sub");

		verify(thrMbrRepository, never()).save(any());
		assertThat(invitation.getStatus()).isEqualTo(ThrInvStatus.ACCEPTED);
	}

	/** 잠기거나 보관된 방은 초대 자체를 막는 requireWritableThread와 같은 기준으로 전환도 미룬다. */
	@Test
	void leavesTheInvitationPendingWhenTheRoomIsNotActive() {
		UUID inviterId = UUID.randomUUID();
		Thr room = Thr.collab(inviterId, "보관된 방");
		room.archive();
		ThrInv invitation = new ThrInv(room.getId(), "archived-sub", inviterId);

		when(thrInvRepository.findBySubjAndStatus("archived-sub", ThrInvStatus.PENDING))
				.thenReturn(List.of(invitation));
		when(thrRepository.findById(room.getId())).thenReturn(Optional.of(room));

		service.acceptPendingBlocking(UUID.randomUUID(), "archived-sub");

		verify(thrMbrRepository, never()).save(any());
		verify(thrInvRepository, never()).save(any());
		assertThat(invitation.getStatus()).isEqualTo(ThrInvStatus.PENDING);
	}

}
