package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
	private ThrInvRepository thrInvRepository;

	@Mock
	private KeycloakAdminClient keycloakAdminClient;

	@Mock
	private InvitationAcceptanceService invitationAcceptanceService;

	private ThreadParticipantService service;

	@BeforeEach
	void setUp() {
		service = new ThreadParticipantService(thrMbrRepository, thrRepository, appUserRepository,
				thrInvRepository, keycloakAdminClient, invitationAcceptanceService);
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
		UUID inviteeUserId = UUID.randomUUID();
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
