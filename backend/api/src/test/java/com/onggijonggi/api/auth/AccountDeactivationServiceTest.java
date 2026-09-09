package com.onggijonggi.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

/**
 * Class Name : AccountDeactivationServiceTest.java
 * Description : AccountDeactivationService의 참여 정리·OWNER 처리 분기를 Mockito로 검증한다.
 *               ThreadLifecycleServiceTest와 같은 방식(전 의존성 목)으로 뗀다.
 */
@ExtendWith(MockitoExtension.class)
class AccountDeactivationServiceTest {

	@Mock
	private AppUserRepository appUserRepository;

	@Mock
	private ThrMbrRepository thrMbrRepository;

	@Mock
	private ThrRepository thrRepository;

	private AccountDeactivationService service;

	@BeforeEach
	void setUp() {
		service = new AccountDeactivationService(appUserRepository, thrMbrRepository, thrRepository);
	}

	@Test
	void deactivateEndsMemberParticipationsAndDeactivatesTheAccount() {
		AppUser user = new AppUser("member-sub");
		ThrMbr membership = new ThrMbr(UUID.randomUUID(), user.getId(), ThrMbrRole.MEMBER, user.getId());

		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
		when(thrMbrRepository.findByUserIdAndStatus(user.getId(), ThrMbrStatus.ACTIVE))
				.thenReturn(List.of(membership));

		StepVerifier.create(service.deactivate(user.getId())).verifyComplete();

		assertThat(membership.getStatus()).isEqualTo(ThrMbrStatus.REVOKED);
		assertThat(membership.getEndRsn()).isEqualTo("ACCOUNT_INACTIVE");
		assertThat(user.getStatus()).isEqualTo(AppUserStatus.INACTIVE);
		verify(thrMbrRepository).save(membership);
		verify(appUserRepository).save(user);
	}

	/**
	* transferOwnership()은 원자적 UPDATE라, 호출 전에 들고 있던 ownerMembership 객체는 role이
	* 실제로 바뀐 상태를 반영하지 못한다(#132 리뷰 발견). 그 객체를 그대로 end()해 저장하면 merge가
	* role을 OWNER로 되돌리므로, 서비스가 위임 뒤 재조회한 새 엔티티에 end()하는지를 검증한다.
	*/
	@Test
	void deactivateTransfersOwnershipWhenASuccessorExists() {
		AppUser owner = new AppUser("owner-sub");
		UUID threadId = UUID.randomUUID();
		UUID successorId = UUID.randomUUID();
		ThrMbr ownerMembership = new ThrMbr(threadId, owner.getId(), ThrMbrRole.OWNER, owner.getId());
		ThrMbr successor = new ThrMbr(threadId, successorId, ThrMbrRole.MEMBER, owner.getId());
		ThrMbr ownerMembershipAfterTransfer = new ThrMbr(threadId, owner.getId(), ThrMbrRole.MEMBER, owner.getId());

		when(appUserRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
		when(thrMbrRepository.findByUserIdAndStatus(owner.getId(), ThrMbrStatus.ACTIVE))
				.thenReturn(List.of(ownerMembership));
		when(thrMbrRepository.findFirstByThrIdAndRoleAndStatus(threadId, ThrMbrRole.MEMBER, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(successor));
		when(thrMbrRepository.transferOwnership(threadId, owner.getId(), successorId)).thenReturn(2);
		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, owner.getId(), ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(ownerMembershipAfterTransfer));

		StepVerifier.create(service.deactivate(owner.getId())).verifyComplete();

		verify(thrMbrRepository).transferOwnership(threadId, owner.getId(), successorId);
		assertThat(ownerMembershipAfterTransfer.getEndRsn()).isEqualTo("ACCOUNT_INACTIVE");
		assertThat(ownerMembershipAfterTransfer.getRole()).isEqualTo(ThrMbrRole.MEMBER);
		verify(thrMbrRepository).save(ownerMembershipAfterTransfer);
		verify(thrMbrRepository, never()).save(ownerMembership);
		verify(thrRepository, never()).save(any());
	}

	@Test
	void deactivateArchivesTheThreadWhenNoSuccessorExists() {
		AppUser owner = new AppUser("lonely-owner-sub");
		Thr thr = Thr.collab(owner.getId(), "혼자 남은 방");
		ThrMbr ownerMembership = new ThrMbr(thr.getId(), owner.getId(), ThrMbrRole.OWNER, owner.getId());

		when(appUserRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
		when(thrMbrRepository.findByUserIdAndStatus(owner.getId(), ThrMbrStatus.ACTIVE))
				.thenReturn(List.of(ownerMembership));
		when(thrMbrRepository.findFirstByThrIdAndRoleAndStatus(thr.getId(), ThrMbrRole.MEMBER, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.empty());
		when(thrRepository.findById(thr.getId())).thenReturn(Optional.of(thr));

		StepVerifier.create(service.deactivate(owner.getId())).verifyComplete();

		assertThat(thr.getStatus()).isEqualTo(com.onggijonggi.common.chat.domain.ThrStatus.ARCHIVED);
		verify(thrRepository).save(thr);
		assertThat(ownerMembership.getEndRsn()).isEqualTo("ACCOUNT_INACTIVE");
	}

	/** 이미 보관된 방을 다시 archive()하지 않는다 — archived_at을 불필요하게 갱신하지 않기 위해서다. */
	@Test
	void deactivateDoesNotReArchiveAnAlreadyArchivedThread() {
		AppUser owner = new AppUser("already-archived-owner-sub");
		Thr thr = Thr.collab(owner.getId(), "이미 보관된 방");
		thr.archive();
		ThrMbr ownerMembership = new ThrMbr(thr.getId(), owner.getId(), ThrMbrRole.OWNER, owner.getId());

		when(appUserRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
		when(thrMbrRepository.findByUserIdAndStatus(owner.getId(), ThrMbrStatus.ACTIVE))
				.thenReturn(List.of(ownerMembership));
		when(thrMbrRepository.findFirstByThrIdAndRoleAndStatus(thr.getId(), ThrMbrRole.MEMBER, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.empty());
		when(thrRepository.findById(thr.getId())).thenReturn(Optional.of(thr));

		StepVerifier.create(service.deactivate(owner.getId())).verifyComplete();

		verify(thrRepository, never()).save(any());
	}

	@Test
	void deactivateFailsWithConflictWhenTheTransferRaced() {
		AppUser owner = new AppUser("racing-owner-sub");
		UUID threadId = UUID.randomUUID();
		UUID successorId = UUID.randomUUID();
		ThrMbr ownerMembership = new ThrMbr(threadId, owner.getId(), ThrMbrRole.OWNER, owner.getId());
		ThrMbr successor = new ThrMbr(threadId, successorId, ThrMbrRole.MEMBER, owner.getId());

		when(appUserRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
		when(thrMbrRepository.findByUserIdAndStatus(owner.getId(), ThrMbrStatus.ACTIVE))
				.thenReturn(List.of(ownerMembership));
		when(thrMbrRepository.findFirstByThrIdAndRoleAndStatus(threadId, ThrMbrRole.MEMBER, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(successor));
		when(thrMbrRepository.transferOwnership(threadId, owner.getId(), successorId)).thenReturn(1);

		StepVerifier.create(service.deactivate(owner.getId()))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void deactivateFailsWithConflictWhenTheAccountIsAlreadyInactive() {
		AppUser user = new AppUser("already-inactive-sub");
		user.deactivate();

		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

		StepVerifier.create(service.deactivate(user.getId()))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.CONFLICT));
		verify(thrMbrRepository, never()).findByUserIdAndStatus(any(), any());
	}

	@Test
	void deactivateFailsWithNotFoundWhenTheAccountDoesNotExist() {
		UUID userId = UUID.randomUUID();
		when(appUserRepository.findById(userId)).thenReturn(Optional.empty());

		StepVerifier.create(service.deactivate(userId))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void reactivateTurnsAnInactiveAccountBackToActive() {
		AppUser user = new AppUser("reactivate-sub");
		user.deactivate();

		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

		StepVerifier.create(service.reactivate(user.getId())).verifyComplete();

		assertThat(user.getStatus()).isEqualTo(AppUserStatus.ACTIVE);
		assertThat(user.getInactiveAt()).isNull();
		verify(appUserRepository).save(user);
	}

	@Test
	void reactivateFailsWithConflictWhenTheAccountIsAlreadyActive() {
		AppUser user = new AppUser("already-active-sub");

		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));

		StepVerifier.create(service.reactivate(user.getId()))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.CONFLICT));
	}

}
