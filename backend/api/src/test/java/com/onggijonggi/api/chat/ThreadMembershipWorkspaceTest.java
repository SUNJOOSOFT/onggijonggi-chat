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
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
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
	private final AppUserRepository appUsers = mock(AppUserRepository.class);
	private final AppUser user = new AppUser(SUBJECT);
	private final ThreadMembershipService service =
			new ThreadMembershipService(mock(ThrMbrRepository.class), threads, authorizer, rbac, appUsers);

	@BeforeEach
	void setUp() {
		rbac.setEnforce(true);
		when(appUsers.findByKeycloakSubj(SUBJECT)).thenReturn(Optional.of(user));
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

	/** 1:1도 그 방의 common을 볼 수 있어야 연다(#299) — 배정을 잃으면 자기 1:1도 열 수 없다. */
	@Test
	void directThreadAlsoRequiresSeeingItsCommonWorkspace() {
		UUID commonId = UUID.randomUUID();
		Thr thread = Thr.direct(UUID.randomUUID(), UUID.randomUUID(), "1:1");
		thread.placeIn(UUID.randomUUID(), commonId);
		when(threads.findById(thread.getId())).thenReturn(Optional.of(thread));

		when(authorizer.canView(SUBJECT, commonId)).thenReturn(Mono.just(false));
		assertThat(service.canEnterWorkspace(thread.getId(), SUBJECT).block()).isFalse();

		when(authorizer.canView(SUBJECT, commonId)).thenReturn(Mono.just(true));
		assertThat(service.canEnterWorkspace(thread.getId(), SUBJECT).block()).isTrue();
	}

	/** 워크스페이스가 없는 옛 1:1(절체 전)은 판정이 켜지면 열 수 없다 — 옛 방을 위한 우회는 두지 않는다. */
	@Test
	void unplacedDirectThreadIsClosedWhenEnforcementIsOn() {
		Thr thread = Thr.direct(UUID.randomUUID(), UUID.randomUUID(), "옛 1:1");
		when(threads.findById(thread.getId())).thenReturn(Optional.of(thread));
		when(authorizer.canView(SUBJECT, null)).thenReturn(Mono.just(false));

		assertThat(service.canEnterWorkspace(thread.getId(), SUBJECT).block()).isFalse();
	}

	@Test
	void nothingIsLookedUpWhenEnforcementIsOff() {
		rbac.setEnforce(false);

		assertThat(service.canEnterWorkspace(UUID.randomUUID(), SUBJECT).block()).isTrue();
		verifyNoInteractions(threads, authorizer);
	}

	@Test
	void inactiveUserCannotRejoinEvenWhenEnforcementIsOff() {
		rbac.setEnforce(false);
		user.deactivate();

		assertThat(service.canEnterWorkspace(UUID.randomUUID(), SUBJECT).block()).isFalse();
		verifyNoInteractions(threads, authorizer);
	}

	@Test
	void notYetProvisionedInviteeCanStillPassWorkspaceEligibility() {
		UUID nodeId = UUID.randomUUID();
		Thr thread = Thr.collab(UUID.randomUUID(), "초대할 방");
		thread.placeIn(UUID.randomUUID(), nodeId);
		when(threads.findById(thread.getId())).thenReturn(Optional.of(thread));
		when(appUsers.findByKeycloakSubj("new-invitee")).thenReturn(Optional.empty());
		when(authorizer.canView("new-invitee", nodeId)).thenReturn(Mono.just(true));

		assertThat(service.canEnterWorkspace(thread.getId(), "new-invitee").block()).isTrue();
	}
}
