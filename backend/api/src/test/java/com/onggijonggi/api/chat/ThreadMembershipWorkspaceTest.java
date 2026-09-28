package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.authz.RbacProperties;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Class Name : ThreadMembershipWorkspaceTest.java
 * Description : 방 입장의 워크스페이스 판정(ThreadMembershipService.canEnterWorkspace)을 검증한다 — 협업방만 워크스페이스를
 *               보고, 1:1은 늘 들어가며, 판정이 꺼지면 DB도 보지 않는다.
 */
class ThreadMembershipWorkspaceTest {

	private static final String SUBJECT = "sub-kim";

	private final RbacProperties rbac = new RbacProperties();
	private final ThrRepository threads = mock(ThrRepository.class);
	private final WorkspaceAuthorizer authorizer = mock(WorkspaceAuthorizer.class);
	private final ThreadMembershipService service =
			new ThreadMembershipService(mock(ThrMbrRepository.class), threads, authorizer, rbac);

	@BeforeEach
	void setUp() {
		rbac.setEnforce(true);
	}

	@Test
	void collabThreadFollowsTheWorkspaceDecision() {
		UUID nodeId = UUID.randomUUID();
		Thr thread = Thr.collab(UUID.randomUUID(), "방");
		thread.placeIn(UUID.randomUUID(), nodeId);
		when(threads.findById(thread.getId())).thenReturn(Optional.of(thread));

		when(authorizer.canView(SUBJECT, nodeId)).thenReturn(Mono.just(false));
		assertThat(service.canEnterWorkspace(thread.getId(), SUBJECT).block()).isFalse();

		when(authorizer.canView(SUBJECT, nodeId)).thenReturn(Mono.just(true));
		assertThat(service.canEnterWorkspace(thread.getId(), SUBJECT).block()).isTrue();
	}

	/** 워크스페이스가 정해지지 않은 협업방은 판정(canView(null))이 거부한다. */
	@Test
	void unplacedCollabThreadIsAskedWithANullWorkspace() {
		Thr thread = Thr.collab(UUID.randomUUID(), "옛 방");
		when(threads.findById(thread.getId())).thenReturn(Optional.of(thread));
		when(authorizer.canView(SUBJECT, null)).thenReturn(Mono.just(false));

		assertThat(service.canEnterWorkspace(thread.getId(), SUBJECT).block()).isFalse();
	}

	@Test
	void directThreadIsAlwaysEnterableWithoutAskingTheWorkspace() {
		Thr thread = Thr.direct(UUID.randomUUID(), UUID.randomUUID(), "1:1");
		when(threads.findById(thread.getId())).thenReturn(Optional.of(thread));

		assertThat(service.canEnterWorkspace(thread.getId(), SUBJECT).block()).isTrue();
		verifyNoInteractions(authorizer);
	}

	@Test
	void nothingIsLookedUpWhenEnforcementIsOff() {
		rbac.setEnforce(false);

		assertThat(service.canEnterWorkspace(UUID.randomUUID(), SUBJECT).block()).isTrue();
		verifyNoInteractions(threads, authorizer);
	}
}
