package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
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
 * Class Name : ThreadLifecycleServiceTest.java
 * Description : ThreadLifecycleService의 권한 판정(404/403)과 상태 전이 판정(409)을 Mockito로
 *               검증한다. ThreadParticipantServiceTest와 같은 방식(전 의존성 목)으로 뗀다.
 */
@ExtendWith(MockitoExtension.class)
class ThreadLifecycleServiceTest {

	@Mock
	private ThrRepository thrRepository;

	@Mock
	private ThrMbrRepository thrMbrRepository;

	private ThreadLifecycleService service;

	@BeforeEach
	void setUp() {
		service = new ThreadLifecycleService(thrRepository, thrMbrRepository);
	}

	@Test
	void lockTurnsAnActiveThreadIntoLocked() {
		UUID actorUserId = UUID.randomUUID();
		Thr thr = Thr.collab(actorUserId, "room");
		UUID threadId = thr.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(thr));

		StepVerifier.create(service.lock(threadId, actorUserId)).verifyComplete();

		assertThat(thr.getStatus()).isEqualTo(ThrStatus.LOCKED);
		assertThat(thr.getLockedAt()).isNotNull();
		assertThat(thr.getArchivedAt()).isNull();
		verify(thrRepository).updateLifecycle(eq(threadId), eq(thr.getStatus()), any(), any(), any());
	}

	@Test
	void lockFailsWithNotFoundWhenActorIsNotAnActiveParticipant() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.empty());

		StepVerifier.create(service.lock(threadId, actorUserId))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.NOT_FOUND));
		verify(thrRepository, never()).findById(any());
	}

	@Test
	void lockFailsWithForbiddenWhenActorIsNotOwner() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.MEMBER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(Thr.collab(actorUserId, "room")));

		StepVerifier.create(service.lock(threadId, actorUserId))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void directOwnerCannotChangeLifecycleThroughCollabOperations() {
		UUID actorUserId = UUID.randomUUID();
		UUID threadId = UUID.randomUUID();
		Thr direct = Thr.direct(threadId, actorUserId, "1:1 대화");
		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(direct));

		assertNotFound(service.lock(threadId, actorUserId));
		assertNotFound(service.archive(threadId, actorUserId));
		assertNotFound(service.delete(threadId, actorUserId));

		assertThat(direct.getStatus()).isEqualTo(ThrStatus.ACTIVE);
		verify(thrRepository, never()).updateLifecycle(any(), any(), any(), any(), any());
		verify(thrRepository, never()).delete(any());
	}

	@Test
	void lockFailsWithConflictWhenTheThreadIsAlreadyLocked() {
		UUID actorUserId = UUID.randomUUID();
		Thr thr = Thr.collab(actorUserId, "room");
		thr.lock();
		UUID threadId = thr.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(thr));

		StepVerifier.create(service.lock(threadId, actorUserId))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.CONFLICT));
		verify(thrRepository, never()).updateLifecycle(any(), any(), any(), any(), any());
	}

	@Test
	void archiveTurnsAnActiveThreadIntoArchivedDirectly() {
		UUID actorUserId = UUID.randomUUID();
		Thr thr = Thr.collab(actorUserId, "room");
		UUID threadId = thr.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(thr));

		StepVerifier.create(service.archive(threadId, actorUserId)).verifyComplete();

		assertThat(thr.getStatus()).isEqualTo(ThrStatus.ARCHIVED);
		assertThat(thr.getArchivedAt()).isNotNull();
		assertThat(thr.getLockedAt()).isNull();
	}

	/** LOCKED를 거쳐 ARCHIVED가 되면 locked_at이 지워지지 않아야 한다(V8__thread.sql CHECK 제약). */
	@Test
	void archiveKeepsTheLockedAtTimestampWhenComingFromLocked() {
		UUID actorUserId = UUID.randomUUID();
		Thr thr = Thr.collab(actorUserId, "room");
		thr.lock();
		var lockedAt = thr.getLockedAt();
		UUID threadId = thr.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(thr));

		StepVerifier.create(service.archive(threadId, actorUserId)).verifyComplete();

		assertThat(thr.getStatus()).isEqualTo(ThrStatus.ARCHIVED);
		assertThat(thr.getLockedAt()).isEqualTo(lockedAt);
		assertThat(thr.getArchivedAt()).isNotNull();
	}

	@Test
	void archiveFailsWithConflictWhenTheThreadIsAlreadyArchived() {
		UUID actorUserId = UUID.randomUUID();
		Thr thr = Thr.collab(actorUserId, "room");
		thr.archive();
		UUID threadId = thr.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(thr));

		StepVerifier.create(service.archive(threadId, actorUserId))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.CONFLICT));
		verify(thrRepository, never()).updateLifecycle(any(), any(), any(), any(), any());
	}

	@Test
	void deleteRemovesTheThreadRegardlessOfItsStatus() {
		UUID actorUserId = UUID.randomUUID();
		Thr thr = Thr.collab(actorUserId, "room");
		thr.archive();
		UUID threadId = thr.getId();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.OWNER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(thr));

		StepVerifier.create(service.delete(threadId, actorUserId)).verifyComplete();

		verify(thrRepository).delete(thr);
	}

	@Test
	void deleteFailsWithForbiddenWhenActorIsNotOwner() {
		UUID threadId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(threadId, actorUserId, ThrMbrRole.MEMBER, actorUserId)));
		when(thrRepository.findById(threadId)).thenReturn(Optional.of(Thr.collab(actorUserId, "room")));

		StepVerifier.create(service.delete(threadId, actorUserId))
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.FORBIDDEN));
		verify(thrRepository, never()).delete(any());
	}

	private static void assertNotFound(reactor.core.publisher.Mono<?> operation) {
		StepVerifier.create(operation)
				.verifyErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(ResponseStatusException.class)
						.extracting(e -> ((ResponseStatusException) e).getStatusCode())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

}
