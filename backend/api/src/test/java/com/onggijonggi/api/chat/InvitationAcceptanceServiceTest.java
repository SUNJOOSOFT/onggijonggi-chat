package com.onggijonggi.api.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.ThrInv;
import com.onggijonggi.common.chat.domain.ThrInvStatus;
import com.onggijonggi.common.chat.persistence.ThrInvRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Class Name : InvitationAcceptanceServiceTest.java
 * Description : 첫 로그인 때 대기 초대를 건별로 전환하는 루프와 경합 재시도(이슈 #127).
 *               전환 자체는 PendingInvitationAcceptanceTest가 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class InvitationAcceptanceServiceTest {

	@Mock
	private ThrInvRepository thrInvRepository;

	@Mock
	private PendingInvitationAcceptance pendingInvitationAcceptance;

	@Mock
	private MembershipNoticeService membershipNoticeService;

	private InvitationAcceptanceService service;

	@BeforeEach
	void setUp() {
		service = new InvitationAcceptanceService(thrInvRepository, pendingInvitationAcceptance,
				membershipNoticeService);
	}

	/** 방이 여럿이면 초대도 여럿이다 — 건별로 각자의 트랜잭션에서 전환한다. */
	@Test
	void convertsEveryPendingInvitationOneByOne() {
		UUID userId = UUID.randomUUID();
		UUID inviterId = UUID.randomUUID();
		ThrInv first = new ThrInv(UUID.randomUUID(), "multi-sub", inviterId);
		ThrInv second = new ThrInv(UUID.randomUUID(), "multi-sub", inviterId);

		when(thrInvRepository.findBySubjAndStatus("multi-sub", ThrInvStatus.PENDING))
				.thenReturn(List.of(first, second));

		service.acceptPendingBlocking(userId, "multi-sub");

		verify(pendingInvitationAcceptance).acceptOneBlocking(first.getId(), userId);
		verify(pendingInvitationAcceptance).acceptOneBlocking(second.getId(), userId);
	}

	/**
	 * 활성 참가자 유니크 경합으로 한 건이 되돌아가면 그 건만 다시 시도한다. 그냥 넘기면 실제로는
	 * 참가해 있는데 초대만 영구히 PENDING으로 남는다 — 전환 시도는 첫 로그인 한 번뿐이다.
	 */
	@Test
	void retriesTheRacedInvitationOnce() {
		UUID userId = UUID.randomUUID();
		ThrInv invitation = new ThrInv(UUID.randomUUID(), "raced-sub", UUID.randomUUID());

		when(thrInvRepository.findBySubjAndStatus("raced-sub", ThrInvStatus.PENDING))
				.thenReturn(List.of(invitation));
		doThrow(new DataIntegrityViolationException("ux_thr_mbr_active_participant"))
				.doReturn(false)
				.when(pendingInvitationAcceptance)
				.acceptOneBlocking(invitation.getId(), userId);

		service.acceptPendingBlocking(userId, "raced-sub");

		verify(pendingInvitationAcceptance, times(2)).acceptOneBlocking(invitation.getId(), userId);
	}

	/** 한 방의 경합이 다른 방의 전환을 막지 않는다 — 경계가 배치 전체가 아니라 초대 한 건이다. */
	@Test
	void keepsConvertingOtherRoomsAfterOneRaces() {
		UUID userId = UUID.randomUUID();
		UUID inviterId = UUID.randomUUID();
		ThrInv raced = new ThrInv(UUID.randomUUID(), "mixed-sub", inviterId);
		ThrInv healthy = new ThrInv(UUID.randomUUID(), "mixed-sub", inviterId);

		when(thrInvRepository.findBySubjAndStatus("mixed-sub", ThrInvStatus.PENDING))
				.thenReturn(List.of(raced, healthy));
		doThrow(new DataIntegrityViolationException("ux_thr_mbr_active_participant"))
				.doReturn(false)
				.when(pendingInvitationAcceptance)
				.acceptOneBlocking(eq(raced.getId()), eq(userId));
		doReturn(false).when(pendingInvitationAcceptance)
				.acceptOneBlocking(eq(healthy.getId()), eq(userId));

		service.acceptPendingBlocking(userId, "mixed-sub");

		verify(pendingInvitationAcceptance, times(2)).acceptOneBlocking(raced.getId(), userId);
		verify(pendingInvitationAcceptance).acceptOneBlocking(healthy.getId(), userId);
	}

	/** 참가자가 되면 그 방에 입장 줄을 남긴다. 이미 참가해 있어 전환만 한 경우는 남기지 않는다. */
	@Test
	void recordsAJoinLineOnlyWhenTheInvitationCreatedAParticipant() {
		UUID userId = UUID.randomUUID();
		ThrInv joined = new ThrInv(UUID.randomUUID(), "join-sub", UUID.randomUUID());
		ThrInv alreadyIn = new ThrInv(UUID.randomUUID(), "join-sub", UUID.randomUUID());

		when(pendingInvitationAcceptance.acceptOneBlocking(joined.getId(), userId)).thenReturn(true);
		when(pendingInvitationAcceptance.acceptOneBlocking(alreadyIn.getId(), userId)).thenReturn(false);
		when(thrInvRepository.findById(joined.getId())).thenReturn(Optional.of(joined));

		service.acceptOneBlocking(joined.getId(), userId);
		service.acceptOneBlocking(alreadyIn.getId(), userId);

		verify(membershipNoticeService).recordBlocking(joined.getThrId(), MembershipNotice.Event.JOINED, "join-sub");
		verify(membershipNoticeService, never()).recordBlocking(eq(alreadyIn.getThrId()), any(), any());
	}

}
