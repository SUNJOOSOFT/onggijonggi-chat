package com.onggijonggi.api.authz;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.chat.PresenceParticipant;
import com.onggijonggi.api.chat.RoomSessionRegistry;
import com.onggijonggi.api.chat.RoomSessionRegistry.RoomMembership;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Class Name : RbacPolicyRefreshRoomTest.java
 * Description : 커밋된 Workspace·부여 변경(#260)이 실제 방 레지스트리의 구독을 어디까지 끊는지 검증한다 — 판정이 켜지면 영향받은
 *               노드의 협업방 구독만 해제하고 다른 노드의 방은 그대로 두며, 판정이 꺼진(절체 전) 동안은 아무 구독도 끊지 않는다.
 *               해제는 구독(kicked)만 끝내고 연결은 닫지 않는다 — 다시 구독하면 최신 권한으로 판정한다.
 */
class RbacPolicyRefreshRoomTest {

	private static final Duration QUIET = Duration.ofMillis(150);

	private final CasbinRuleLoader loader = mock(CasbinRuleLoader.class);
	private final ThrRepository threads = mock(ThrRepository.class);
	private final RbacProperties rbac = new RbacProperties();
	private final RoomSessionRegistry rooms = new RoomSessionRegistry(Duration.ofMillis(50));
	private final RbacPolicyRefresh refresh = new RbacPolicyRefresh(loader, rbac, threads, rooms);

	private final UUID tenantId = UUID.randomUUID();
	private final UUID changedNode = UUID.randomUUID();
	private final UUID otherNode = UUID.randomUUID();
	private final Thr changedRoom = Thr.collab(UUID.randomUUID(), "바뀐 노드의 방");
	private final Thr otherRoom = Thr.collab(UUID.randomUUID(), "다른 노드의 방");

	private RoomMembership inChanged;
	private RoomMembership inOther;

	@BeforeEach
	void setUp() {
		when(threads.findByWorkspaceNodeIdAndKind(changedNode, ThrKind.COLLAB)).thenReturn(List.of(changedRoom));
		when(threads.findByWorkspaceNodeIdAndKind(otherNode, ThrKind.COLLAB)).thenReturn(List.of(otherRoom));
		// 같은 사람이 한 연결로 두 방을 구독한다.
		UUID connection = UUID.randomUUID();
		PresenceParticipant kim = new PresenceParticipant("sub-kim", "김");
		inChanged = rooms.join(changedRoom.getId(), connection, kim);
		inOther = rooms.join(otherRoom.getId(), connection, kim);
	}

	@Test
	void afterCutoverOnlyTheChangedNodesRoomsAreEvicted() {
		rbac.setEnforce(true);

		refresh.publish(tenantId, Set.of(changedNode), null);

		StepVerifier.create(inChanged.kicked()).expectComplete().verify(Duration.ofSeconds(2));
		StepVerifier.create(inOther.kicked()).expectSubscription().expectNoEvent(QUIET).thenCancel().verify();
	}

	@Test
	void aMovedThreadIsEvictedEvenWithoutANodeChange() {
		rbac.setEnforce(true);

		refresh.publish(tenantId, Set.of(), otherRoom.getId());

		StepVerifier.create(inOther.kicked()).expectComplete().verify(Duration.ofSeconds(2));
		StepVerifier.create(inChanged.kicked()).expectSubscription().expectNoEvent(QUIET).thenCancel().verify();
	}

	@Test
	void beforeCutoverNothingIsEvicted() {
		rbac.setEnforce(false);

		refresh.publish(tenantId, Set.of(changedNode, otherNode), changedRoom.getId());

		StepVerifier.create(inChanged.kicked()).expectSubscription().expectNoEvent(QUIET).thenCancel().verify();
		StepVerifier.create(inOther.kicked()).expectSubscription().expectNoEvent(QUIET).thenCancel().verify();
	}
}
