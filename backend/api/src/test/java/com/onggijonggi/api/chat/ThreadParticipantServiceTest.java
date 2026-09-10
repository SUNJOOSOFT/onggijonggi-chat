package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.common.chat.domain.ThrInv;
import com.onggijonggi.common.chat.domain.ThrInvStatus;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrInvRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Class Name : ThreadParticipantServiceTest.java
 * Description : ThreadParticipantService의 경합·순서 판정을 Mockito로 좁혀 검증한다. 둘 다 실 DB
 *               경합 없이는 통합 테스트로 결정적으로 재현하기 어려운 자리라 PersistingChatStreamServiceTest
 *               와 같은 방식(전 의존성 목)으로 뗀다.
 */
@ExtendWith(MockitoExtension.class)
class ThreadParticipantServiceTest {

	@Mock
	private ThrMbrRepository thrMbrRepository;

	@Mock
	private ThrRepository thrRepository;

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private RoomSessionRegistry roomSessionRegistry;

	@Mock
	private KeycloakAdminClient keycloakAdminClient;

	@Mock
	private ThrInvRepository thrInvRepository;

	@Mock
	private InvitationAcceptanceService invitationAcceptanceService;

	private ThreadParticipantService service;

	@BeforeEach
	void setUp() {
		service = new ThreadParticipantService(thrMbrRepository, thrRepository, appUserRepository,
				roomSessionRegistry, keycloakAdminClient, thrInvRepository, invitationAcceptanceService);
	}

	/**
	* transferOwnership의 UPDATE가 두 행을 못 맞추면(경합) 409여야 한다. 실제 경합은 두 요청이
	* 동시에 들어와야 생기는데, 그 순간을 통합 테스트로 결정적으로 잡을 수 없어 레포지토리 반환값을
	* 직접 통제해 이 분기만 좁혀 본다.
	*/
	@Test
	void transferOwnerFailsWithConflictWhenTheUpdateDidNotMatchTwoRows() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser targetAppUser = new AppUser("race-target");
		UUID targetUserId = targetAppUser.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(appUserRepository.findByKeycloakSubj("race-target")).thenReturn(Optional.of(targetAppUser));
		when(thrMbrRepository.findByThrIdAndUserIdAndRoleAndStatus(threadId, targetUserId, ThrMbrRole.MEMBER,
				ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, targetUserId, ThrMbrRole.MEMBER, actorUserId)));
		// 사전 확인 시점엔 조건이 맞았지만, 그 사이 다른 위임이 끝나 실제 UPDATE는 한 행만 맞춘다.
		when(thrMbrRepository.transferOwnership(threadId, actorUserId, targetUserId)).thenReturn(1);

		StepVerifier.create(service.transferOwner(threadId, actorUserId, "race-target"))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.CONFLICT));
	}

	/**
	* OWNER가 아닌 사람이 자기 자신이 아닌 대상을 제거하려 하면, 그 대상이 실존하는 계정인지
	* 조회하기 전에 403으로 끝나야 한다. 조회가 먼저 실행되면 존재하지 않는 subject는 404가
	* 나가서, 권한 없는 참가자가 "이 subject가 가입한 적 있는지"를 상태코드 차이로 알아낼 수
	* 있다. appUserRepository가 아예 호출되지 않는 것으로 순서를 확인한다.
	*/
	@Test
	void removeRejectsANonOwnerBeforeResolvingTheTargetSubject() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.MEMBER, actorUserId)));

		StepVerifier.create(service.remove(threadId, actorUserId, "actor-sub", "someone-else-entirely"))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.FORBIDDEN));
		verify(appUserRepository, never()).findByKeycloakSubj(any());
	}

	/** LOCKED·ARCHIVED로 바뀐 방은 새 참가자를 들이지 않는다(#131) — 대상 조회보다 먼저 막는다. */
	@Test
	void inviteFailsWithConflictWhenTheThreadIsNotWritable() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE)).thenReturn(false);

		StepVerifier.create(service.invite(threadId, actorUserId, "invitee-sub"))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.CONFLICT));
		verify(appUserRepository, never()).findByKeycloakSubj(any());
	}

	/** 초대가 실제로 새 참가 행을 만들면 그 방 구독자 전원에게 통지해야 한다(이슈 #129). */
	@Test
	void inviteNotifiesTheRoomWhenANewParticipantIsAdded() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser inviteeAppUser = new AppUser("invitee-sub");
		UUID inviteeUserId = inviteeAppUser.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE)).thenReturn(true);
		when(appUserRepository.findByKeycloakSubj("invitee-sub")).thenReturn(Optional.of(inviteeAppUser));
		when(thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, inviteeUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(false);
		when(keycloakAdminClient.displayName("invitee-sub")).thenReturn(Mono.just(Optional.of("Invitee")));

		StepVerifier.create(service.invite(threadId, actorUserId, "invitee-sub")).verifyComplete();

		verify(roomSessionRegistry).notifyIfListening(eq(threadId),
				eq(new ParticipantChangedFrame(threadId, ParticipantChangeAction.INVITED, "invitee-sub",
						"Invitee")));
	}

	/** 이미 ACTIVE인 사람을 다시 초대하면 명단이 바뀌지 않았으므로 통지하지 않는다(멱등, 이슈 #129). */
	@Test
	void inviteDoesNotNotifyWhenTheInviteeIsAlreadyActive() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser inviteeAppUser = new AppUser("invitee-sub");
		UUID inviteeUserId = inviteeAppUser.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE)).thenReturn(true);
		when(appUserRepository.findByKeycloakSubj("invitee-sub")).thenReturn(Optional.of(inviteeAppUser));
		when(thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, inviteeUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(true);

		StepVerifier.create(service.invite(threadId, actorUserId, "invitee-sub")).verifyComplete();

		verify(roomSessionRegistry, never()).notifyIfListening(any(), any());
	}

	/** OWNER가 다른 참가자를 제거하면 제거당한 본인을 포함해 그 방 전원에게 통지한다(이슈 #129). */
	@Test
	void removeByOwnerNotifiesTheRoomWithTheRemovedSubject() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser targetAppUser = new AppUser("target-sub");
		UUID targetUserId = targetAppUser.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(appUserRepository.findByKeycloakSubj("target-sub")).thenReturn(Optional.of(targetAppUser));
		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, targetUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, targetUserId, ThrMbrRole.MEMBER, actorUserId)));
		when(keycloakAdminClient.displayName("target-sub")).thenReturn(Mono.just(Optional.empty()));

		StepVerifier.create(service.remove(threadId, actorUserId, "actor-sub", "target-sub")).verifyComplete();

		verify(roomSessionRegistry).notifyIfListening(eq(threadId),
				eq(new ParticipantChangedFrame(threadId, ParticipantChangeAction.REMOVED, "target-sub",
						"target-sub")));
	}

	/** 자진 탈퇴도 참가자 명단이 바뀌는 일이라 남은 참가자에게 통지한다(이슈 #129). */
	@Test
	void selfLeaveNotifiesTheRoomWithTheActorsOwnSubject() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.MEMBER, actorUserId)));
		when(keycloakAdminClient.displayName("actor-sub")).thenReturn(Mono.just(Optional.of("Actor")));

		StepVerifier.create(service.remove(threadId, actorUserId, "actor-sub", "actor-sub")).verifyComplete();

		verify(roomSessionRegistry).notifyIfListening(eq(threadId),
				eq(new ParticipantChangedFrame(threadId, ParticipantChangeAction.REMOVED, "actor-sub", "Actor")));
	}

	/**
	* OWNER가 남을 제거하면 그 사람이 다른 탭으로 이미 연결돼 있어도 끊어야 한다(이슈 #135).
	* evict를 부르는지, 그리고 targetSubject(제거당한 쪽)를 넘기는지를 본다 — actorSubject가
	* 아니다.
	*/
	@Test
	void removeByOwnerEvictsTheRemovedSubjectsOpenConnections() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser targetAppUser = new AppUser("target-sub");
		UUID targetUserId = targetAppUser.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(appUserRepository.findByKeycloakSubj("target-sub")).thenReturn(Optional.of(targetAppUser));
		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, targetUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, targetUserId, ThrMbrRole.MEMBER, actorUserId)));
		when(keycloakAdminClient.displayName("target-sub")).thenReturn(Mono.just(Optional.empty()));

		StepVerifier.create(service.remove(threadId, actorUserId, "actor-sub", "target-sub")).verifyComplete();

		verify(roomSessionRegistry).evict(threadId, "target-sub");
	}

	/** 자진 탈퇴도 같은 이유로 자기 자신의 다른 탭 연결을 끊어야 한다(이슈 #135). */
	@Test
	void selfLeaveEvictsTheActorsOwnOpenConnections() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.MEMBER, actorUserId)));
		when(keycloakAdminClient.displayName("actor-sub")).thenReturn(Mono.just(Optional.of("Actor")));

		StepVerifier.create(service.remove(threadId, actorUserId, "actor-sub", "actor-sub")).verifyComplete();

		verify(roomSessionRegistry).evict(threadId, "actor-sub");
	}

	/**
	* Keycloak 조회 실패로 알림은 삼켜지지만(위 inviteSucceedsEvenWhenDisplayNameLookupFails와 같은
	* 원칙), evict는 보안에 관련된 동작이라 알림 성패와 무관하게 반드시 불려야 한다(이슈 #135).
	*/
	@Test
	void removeEvictsEvenWhenDisplayNameLookupFails() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser targetAppUser = new AppUser("target-sub");
		UUID targetUserId = targetAppUser.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(appUserRepository.findByKeycloakSubj("target-sub")).thenReturn(Optional.of(targetAppUser));
		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, targetUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, targetUserId, ThrMbrRole.MEMBER, actorUserId)));
		when(keycloakAdminClient.displayName("target-sub"))
				.thenReturn(Mono.error(new RuntimeException("keycloak down")));

		StepVerifier.create(service.remove(threadId, actorUserId, "actor-sub", "target-sub")).verifyComplete();

		verify(roomSessionRegistry).evict(threadId, "target-sub");
	}

	/** 위임이 성공하면 새 OWNER의 subject를 실어 통지한다(이슈 #129). */
	@Test
	void transferOwnerNotifiesTheRoomWithTheNewOwnerSubject() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser targetAppUser = new AppUser("new-owner-sub");
		UUID targetUserId = targetAppUser.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(appUserRepository.findByKeycloakSubj("new-owner-sub")).thenReturn(Optional.of(targetAppUser));
		when(thrMbrRepository.findByThrIdAndUserIdAndRoleAndStatus(threadId, targetUserId, ThrMbrRole.MEMBER,
				ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, targetUserId, ThrMbrRole.MEMBER, actorUserId)));
		when(thrMbrRepository.transferOwnership(threadId, actorUserId, targetUserId)).thenReturn(2);
		when(keycloakAdminClient.displayName("new-owner-sub")).thenReturn(Mono.just(Optional.of("New Owner")));

		StepVerifier.create(service.transferOwner(threadId, actorUserId, "new-owner-sub")).verifyComplete();

		verify(roomSessionRegistry).notifyIfListening(eq(threadId),
				eq(new ParticipantChangedFrame(threadId, ParticipantChangeAction.OWNER_TRANSFERRED,
						"new-owner-sub", "New Owner")));
	}

	/**
	* Keycloak 조회가 실패해도 참여자 변경 자체는 이미 커밋돼 있으므로 호출자에게는 성공을
	* 돌려줘야 한다(이슈 #129) — 통지 실패가 초대 응답까지 5xx로 끌고 가면 실제로는 성공한
	* 작업이 실패로 보인다.
	*/
	@Test
	void inviteSucceedsEvenWhenDisplayNameLookupFails() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser inviteeAppUser = new AppUser("invitee-sub");
		UUID inviteeUserId = inviteeAppUser.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE)).thenReturn(true);
		when(appUserRepository.findByKeycloakSubj("invitee-sub")).thenReturn(Optional.of(inviteeAppUser));
		when(thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, inviteeUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(false);
		when(keycloakAdminClient.displayName("invitee-sub"))
				.thenReturn(Mono.error(new RuntimeException("keycloak down")));

		StepVerifier.create(service.invite(threadId, actorUserId, "invitee-sub")).verifyComplete();

		verify(roomSessionRegistry, never()).notifyIfListening(any(), any());
	}

	/**
	* 대상이 초대 처리 도중에 첫 로그인을 마친 경우다. 첫 조회는 미스라 대기 초대로 가지만, 그
	* 사이에 app_user 행이 생기면 전환을 도는 쪽(첫 로그인 경로)은 아직 커밋되지 않은 우리 초대를
	* 보지 못한다 — 초대 행을 남긴 뒤 재확인해 우리가 전환해야 한다. 재확인이 없으면 그 초대는
	* 아무 오류 없이 영구히 대기한다.
	*/
	@Test
	void inviteConvertsThePendingRowWhenTheInviteeLoggedInMeanwhile() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();
		AppUser invitee = new AppUser("late-sub");

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE)).thenReturn(true);
		// 첫 조회는 미스, 재확인에서는 방금 만들어진 행이 보인다.
		when(appUserRepository.findByKeycloakSubj("late-sub"))
				.thenReturn(Optional.empty(), Optional.of(invitee));
		when(keycloakAdminClient.exists("late-sub")).thenReturn(Mono.just(true));
		ThrInv pending = new ThrInv(threadId, "late-sub", actorUserId);
		when(thrInvRepository.findByThrIdAndSubjAndStatus(threadId, "late-sub", ThrInvStatus.PENDING))
				.thenReturn(Optional.empty(), Optional.of(pending));

		StepVerifier.create(service.invite(threadId, actorUserId, "late-sub")).verifyComplete();

		verify(thrInvRepository).save(any(ThrInv.class));
		verify(invitationAcceptanceService).acceptOneBlocking(pending.getId(), invitee.getId());
	}

	/** 대상이 아직 로그인하지 않았으면 전환을 돌리지 않는다 — 대기 초대로만 남는다. */
	@Test
	void inviteLeavesTheRowPendingWhenTheInviteeStillHasNoAccount() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE)).thenReturn(true);
		when(appUserRepository.findByKeycloakSubj("never-sub")).thenReturn(Optional.empty());
		when(keycloakAdminClient.exists("never-sub")).thenReturn(Mono.just(true));
		when(thrInvRepository.findByThrIdAndSubjAndStatus(threadId, "never-sub", ThrInvStatus.PENDING))
				.thenReturn(Optional.empty());

		StepVerifier.create(service.invite(threadId, actorUserId, "never-sub")).verifyComplete();

		verify(thrInvRepository).save(any(ThrInv.class));
		verify(invitationAcceptanceService, never()).acceptOneBlocking(any(), any());
	}

}
