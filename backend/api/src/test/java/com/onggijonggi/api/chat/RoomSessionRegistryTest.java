package com.onggijonggi.api.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class Name : RoomSessionRegistryTest.java
 * Description : 방 단위 in-memory 방송(`RoomSessionRegistry`, 이슈 #16)을 검증한다. 같은 방
 *               구독자에게만(다른 방은 제외) 방송되는지, 동시 방송이 모든 구독자에게 같은
 *               순서로 도달하는지, 느린 연결의 outbound 버퍼가 넘쳐도 그 연결만 신호를 받고
 *               다른 연결은 영향받지 않는지, 마지막 멤버가 나간 뒤 새 방 상태가 정상 동작하는지
 *               확인한다. 입퇴장 통보(이슈 #25)는 연결이 아니라 사용자 단위라, 한 사람이 탭을
 *               여럿 열었을 때 통보가 새지 않는지도 함께 본다. 방이 비었다 다시 생긴 뒤 옛 세대로는
 *               방송이 들어가지 않는지도 확인한다(이슈 #102). 참가자 제거 시 그 사람의 연결(탭이
 *               여럿이어도 전부)에만 강제 종료 신호가 가고 다른 연결은 영향받지 않는지, 그리고
 *               evict가 같은 연결의 leave와 동시에 실행돼도 방 상태가 깨지지 않는지도
 *               확인한다(이슈 #135).
 */
class RoomSessionRegistryTest {

	private final RoomSessionRegistry registry = new RoomSessionRegistry();

	/** 사람을 subject로 가리키게 되면서(이슈 #130) 테스트도 subject·표시 이름 쌍을 다룬다. */
	private static PresenceParticipant participant(String subject) {
		return new PresenceParticipant(subject, subject + " 님");
	}

	/** 누구인지가 그 테스트의 관심사가 아닐 때 쓰는 익명 참가자. */
	private static PresenceParticipant anyone() {
		return participant(UUID.randomUUID().toString());
	}

	@Test
	void broadcastsToSenderAndPeersButNotOtherRooms() {
		UUID roomId = UUID.randomUUID();
		UUID otherRoomId = UUID.randomUUID();
		PresenceParticipant sender = participant("sender");
		PresenceParticipant secondUser = participant("second");
		List<WsFrame> first = new CopyOnWriteArrayList<>();
		List<WsFrame> second = new CopyOnWriteArrayList<>();
		List<WsFrame> other = new CopyOnWriteArrayList<>();

		RoomSessionRegistry.RoomMembership firstMembership =
				registry.join(roomId, UUID.randomUUID(), anyone());
		Disposable firstSubscription = firstMembership.frames().subscribe(first::add);
		Disposable secondSubscription = registry.join(roomId, UUID.randomUUID(), secondUser)
				.frames().subscribe(second::add);
		Disposable otherSubscription = registry.join(otherRoomId, UUID.randomUUID(), anyone())
				.frames().subscribe(other::add);

		ChatMessageFrame expected = new ChatMessageFrame(roomId, sender.subject(), sender.displayName(), "hello");
		assertThat(registry.broadcastIfCurrent(roomId, firstMembership.generation(), expected)).isTrue();
		// 먼저 들어와 있던 first만 두 번째 입장을 통보받는다 — second는 자기 입장을 받지 않는다.
		assertThat(first).containsExactly(new PresenceJoinFrame(roomId, secondUser.subject(), secondUser.displayName()), expected);
		assertThat(second).containsExactly(expected);
		assertThat(other).isEmpty();

		firstSubscription.dispose();
		secondSubscription.dispose();
		otherSubscription.dispose();
	}

	/** notifyIfListening은 room generation을 몰라도 지금 붙어 있는 구독자 전원에게 전달된다(#28). */
	@Test
	void notifyIfListeningDeliversToEveryoneCurrentlyInTheRoom() {
		UUID roomId = UUID.randomUUID();
		List<WsFrame> received = new CopyOnWriteArrayList<>();
		Disposable subscription = registry.join(roomId, UUID.randomUUID(), anyone())
				.frames().subscribe(received::add);

		SystemNoticeFrame notice = new SystemNoticeFrame(roomId, "warning", "RISKY_CONTENT", "위험 감지", "trace-1");
		registry.notifyIfListening(roomId, notice);

		assertThat(received).containsExactly(notice);
		subscription.dispose();
	}

	/** 방이 없거나(아무도 접속한 적 없거나 이미 비어 사라진 경우) 조용히 버려진다 — 예외를 던지지 않는다. */
	@Test
	void notifyIfListeningIsANoOpWhenTheRoomDoesNotExist() {
		registry.notifyIfListening(UUID.randomUUID(),
				new SystemNoticeFrame(UUID.randomUUID(), "warning", "RISKY_CONTENT", "위험 감지", "trace-2"));
	}

	@Test
	void concurrentBroadcastsHaveTheSameOrderForEverySubscriber() throws Exception {
		UUID roomId = UUID.randomUUID();
		List<WsFrame> first = new CopyOnWriteArrayList<>();
		List<WsFrame> second = new CopyOnWriteArrayList<>();
		RoomSessionRegistry.RoomMembership firstMembership =
				registry.join(roomId, UUID.randomUUID(), anyone());
		Disposable firstSubscription = firstMembership.frames().subscribe(first::add);
		Disposable secondSubscription = registry.join(roomId, UUID.randomUUID(), anyone())
				.frames().subscribe(second::add);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			UUID generation = firstMembership.generation();
			Future<?> left = executor.submit(() -> broadcastRange(roomId, generation, "left", start));
			Future<?> right = executor.submit(() -> broadcastRange(roomId, generation, "right", start));
			start.countDown();
			left.get(5, TimeUnit.SECONDS);
			right.get(5, TimeUnit.SECONDS);

			// first는 second의 입장 통보를 하나 더 갖고 있다 — 순서 비교 대상은 메시지뿐이다.
			List<WsFrame> firstMessages = first.stream().filter(ChatMessageFrame.class::isInstance).toList();
			assertThat(firstMessages).hasSize(200);
			assertThat(second).containsExactlyElementsOf(firstMessages);
		} finally {
			executor.shutdownNow();
			firstSubscription.dispose();
			secondSubscription.dispose();
		}
	}

	@Test
	void overflowingConnectionBufferSignalsOnlyThatSubscriber() {
		UUID roomId = UUID.randomUUID();
		UUID slowConnectionId = UUID.randomUUID();
		UUID fastConnectionId = UUID.randomUUID();
		Sinks.One<Void> slowOverflow = Sinks.one();
		Sinks.One<Void> fastOverflow = Sinks.one();
		AtomicBoolean slowOverflowed = new AtomicBoolean();
		AtomicBoolean fastOverflowed = new AtomicBoolean();
		List<WsFrame> fastFrames = new CopyOnWriteArrayList<>();

		slowOverflow.asMono().doOnSuccess(ignored -> slowOverflowed.set(true)).subscribe();
		fastOverflow.asMono().doOnSuccess(ignored -> fastOverflowed.set(true)).subscribe();

		BaseSubscriber<WsFrame> slowSubscriber = new BaseSubscriber<>() {
			@Override
			protected void hookOnSubscribe(Subscription subscription) {
				// 연결별 버퍼를 채우기 위해 의도적으로 demand를 요청하지 않는다.
			}
		};
		RoomSessionRegistry.RoomMembership slowMembership =
				registry.join(roomId, slowConnectionId, anyone());
		RoomSessionRegistry.RoomMembership fastMembership =
				registry.join(roomId, fastConnectionId, anyone());
		CollabWebSocketHandler.bufferForConnection(
				slowMembership.frames(), slowOverflow).subscribe(slowSubscriber);
		Disposable fastSubscription = CollabWebSocketHandler.bufferForConnection(
				fastMembership.frames(), fastOverflow)
				.subscribe(fastFrames::add);

		for (int i = 0; i < 257; i++) {
			registry.broadcastIfCurrent(roomId, slowMembership.generation(),
					new ChatMessageFrame(roomId, "someone", "누군가", "message-" + i));
		}

		assertThat(slowOverflowed).isTrue();
		assertThat(fastOverflowed).isFalse();
		assertThat(fastFrames).hasSize(257);

		slowSubscriber.cancel();
		fastSubscription.dispose();
		registry.leave(roomId, slowConnectionId, anyone());
		registry.leave(roomId, fastConnectionId, anyone());
	}

	@Test
	void aNewRoomStateSurvivesAfterThePreviousLastMemberLeaves() {
		UUID roomId = UUID.randomUUID();
		UUID oldConnectionId = UUID.randomUUID();
		Disposable oldSubscription = registry.join(roomId, oldConnectionId, anyone()).frames().subscribe();

		registry.leave(roomId, oldConnectionId, anyone());
		oldSubscription.dispose();

		UUID newConnectionId = UUID.randomUUID();
		List<WsFrame> received = new CopyOnWriteArrayList<>();
		RoomSessionRegistry.RoomMembership newMembership =
				registry.join(roomId, newConnectionId, anyone());
		Disposable newSubscription = newMembership.frames().subscribe(received::add);
		assertThat(registry.broadcastIfCurrent(roomId, newMembership.generation(),
				new ChatMessageFrame(roomId, "someone", "누군가", "new room"))).isTrue();

		assertThat(received).hasSize(1);

		newSubscription.dispose();
		registry.leave(roomId, newConnectionId, anyone());
	}

	@Test
	void announcesJoinToExistingMembersOnlyAndSkipsTheFirstConnection() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant firstUser = participant("first");
		PresenceParticipant secondUser = participant("second");
		List<WsFrame> first = new CopyOnWriteArrayList<>();
		List<WsFrame> second = new CopyOnWriteArrayList<>();

		Disposable firstSubscription = registry.join(roomId, UUID.randomUUID(), firstUser)
				.frames().subscribe(first::add);
		// 첫 입장은 알릴 상대가 없어 아무것도 내지 않는다 — 빈 방의 warm-up 버퍼를 쓰지 않는다.
		assertThat(first).isEmpty();

		UUID secondConnectionId = UUID.randomUUID();
		Disposable secondSubscription = registry.join(roomId, secondConnectionId, secondUser)
				.frames().subscribe(second::add);

		assertThat(first).containsExactly(new PresenceJoinFrame(roomId, secondUser.subject(), secondUser.displayName()));
		assertThat(second).isEmpty();

		firstSubscription.dispose();
		secondSubscription.dispose();
		registry.leave(roomId, secondConnectionId, secondUser);
	}

	@Test
	void announcesLeaveToTheRemainingMembers() {
		UUID roomId = UUID.randomUUID();
		UUID stayingConnectionId = UUID.randomUUID();
		UUID leavingConnectionId = UUID.randomUUID();
		PresenceParticipant leavingUser = participant("leaving");
		List<WsFrame> staying = new CopyOnWriteArrayList<>();

		Disposable stayingSubscription = registry.join(roomId, stayingConnectionId, anyone())
				.frames().subscribe(staying::add);
		registry.join(roomId, leavingConnectionId, leavingUser).frames().subscribe();
		staying.clear();

		registry.leave(roomId, leavingConnectionId, leavingUser);

		assertThat(staying).containsExactly(new PresenceLeaveFrame(roomId, leavingUser.subject(), leavingUser.displayName()));

		stayingSubscription.dispose();
	}

	@Test
	void doesNotAnnounceLeaveWhenTheLastMemberLeaves() {
		UUID roomId = UUID.randomUUID();
		UUID onlyConnectionId = UUID.randomUUID();
		PresenceParticipant onlyUser = participant("only");
		List<WsFrame> received = new CopyOnWriteArrayList<>();

		Disposable subscription = registry.join(roomId, onlyConnectionId, onlyUser)
				.frames().subscribe(received::add);

		// 마지막 퇴장이면 방이 사라진다. 사라진 방에 방송하면 예외이므로 아무것도 내지 않아야 한다.
		registry.leave(roomId, onlyConnectionId, onlyUser);

		assertThat(received).isEmpty();
		subscription.dispose();
	}

	@Test
	void doesNotAnnounceJoinForTheSameUsersSecondConnection() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant twoTabUser = participant("twoTab");
		UUID firstTabId = UUID.randomUUID();
		List<WsFrame> watcher = new CopyOnWriteArrayList<>();

		Disposable watcherSubscription = registry.join(roomId, UUID.randomUUID(), anyone())
				.frames().subscribe(watcher::add);
		Disposable firstTab = registry.join(roomId, firstTabId, twoTabUser).frames().subscribe();
		watcher.clear();

		Disposable secondTab = registry.join(roomId, UUID.randomUUID(), twoTabUser).frames().subscribe();

		// 이미 방에 있는 사람이 탭을 하나 더 연 것은 입장이 아니다.
		assertThat(watcher).isEmpty();

		watcherSubscription.dispose();
		firstTab.dispose();
		secondTab.dispose();
	}

	@Test
	void announcesLeaveOnlyWhenTheSameUsersLastConnectionGoes() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant twoTabUser = participant("twoTab");
		UUID firstTabId = UUID.randomUUID();
		UUID secondTabId = UUID.randomUUID();
		List<WsFrame> watcher = new CopyOnWriteArrayList<>();

		Disposable watcherSubscription = registry.join(roomId, UUID.randomUUID(), anyone())
				.frames().subscribe(watcher::add);
		Disposable firstTab = registry.join(roomId, firstTabId, twoTabUser).frames().subscribe();
		Disposable secondTab = registry.join(roomId, secondTabId, twoTabUser).frames().subscribe();
		watcher.clear();

		registry.leave(roomId, secondTabId, twoTabUser);

		// 탭 하나가 닫혀도 다른 탭이 남아 있으면 그 사람은 아직 방에 있다. 재연결도 같은 모양이다 —
		// 새 연결이 먼저 등록되고 죽은 옛 연결의 doFinally가 뒤늦게 도는 순서라, 여기서 통보가
		// 나가면 남은 사람 목록에서 그 사람이 사라진 채로 남는다.
		assertThat(watcher).isEmpty();

		registry.leave(roomId, firstTabId, twoTabUser);

		assertThat(watcher).containsExactly(new PresenceLeaveFrame(roomId, twoTabUser.subject(), twoTabUser.displayName()));

		watcherSubscription.dispose();
		firstTab.dispose();
		secondTab.dispose();
	}

	@Test
	void staleGenerationCannotDeliverIntoARecreatedRoom() {
		UUID roomId = UUID.randomUUID();
		UUID oldConnectionId = UUID.randomUUID();
		PresenceParticipant oldUser = participant("old");
		RoomSessionRegistry.RoomMembership oldMembership = registry.join(roomId, oldConnectionId, oldUser);
		Disposable oldSubscription = oldMembership.frames().subscribe();

		assertThat(registry.leave(roomId, oldConnectionId, oldUser)).contains(oldMembership.generation());
		oldSubscription.dispose();

		UUID newConnectionId = UUID.randomUUID();
		PresenceParticipant newUser = participant("new");
		List<WsFrame> received = new CopyOnWriteArrayList<>();
		RoomSessionRegistry.RoomMembership newMembership = registry.join(roomId, newConnectionId, newUser);
		Disposable newSubscription = newMembership.frames().subscribe(received::add);
		try {
			assertThat(newMembership.generation()).isNotEqualTo(oldMembership.generation());
			assertThat(registry.broadcastIfCurrent(roomId, oldMembership.generation(),
					new ChatMessageFrame(roomId, "someone", "누군가", "stale"))).isFalse();
			assertThat(received).isEmpty();
		} finally {
			newSubscription.dispose();
			registry.leave(roomId, newConnectionId, newUser);
		}
	}

	@Test
	void concurrentLastLeaveAndBroadcastNeverDeliverTheOldGenerationToANewRoom() throws Exception {
		UUID roomId = UUID.randomUUID();
		UUID oldConnectionId = UUID.randomUUID();
		PresenceParticipant oldUser = participant("old");
		RoomSessionRegistry.RoomMembership oldMembership = registry.join(roomId, oldConnectionId, oldUser);
		Disposable oldSubscription = oldMembership.frames().subscribe();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<?> leave = executor.submit(() -> {
				await(start);
				registry.leave(roomId, oldConnectionId, oldUser);
			});
			Future<Boolean> broadcast = executor.submit(() -> {
				await(start);
				return registry.broadcastIfCurrent(roomId, oldMembership.generation(),
						new ChatMessageFrame(roomId, "someone", "누군가", "racing"));
			});
			start.countDown();
			leave.get(5, TimeUnit.SECONDS);
			broadcast.get(5, TimeUnit.SECONDS);

			UUID newConnectionId = UUID.randomUUID();
			PresenceParticipant newUser = participant("new");
			List<WsFrame> received = new CopyOnWriteArrayList<>();
			RoomSessionRegistry.RoomMembership newMembership =
					registry.join(roomId, newConnectionId, newUser);
			Disposable newSubscription = newMembership.frames().subscribe(received::add);
			try {
				assertThat(registry.broadcastIfCurrent(roomId, oldMembership.generation(),
						new ChatMessageFrame(roomId, "someone", "누군가", "stale"))).isFalse();
				assertThat(received).isEmpty();
			} finally {
				newSubscription.dispose();
				registry.leave(roomId, newConnectionId, newUser);
			}
		} finally {
			executor.shutdownNow();
			oldSubscription.dispose();
		}
	}

	@Test
	void snapshotHoldsEveryoneInTheRoomIncludingTheJoiner() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant firstUser = participant("first");
		PresenceParticipant secondUser = participant("second");

		RoomSessionRegistry.RoomMembership first = registry.join(roomId, UUID.randomUUID(), firstUser);
		RoomSessionRegistry.RoomMembership second = registry.join(roomId, UUID.randomUUID(), secondUser);

		// 혼자 들어온 첫 입장자도 자기 자신을 받는다 — 자기 입장 통보는 오지 않으므로 이 명단이
		// 클라이언트가 스스로를 목록에 넣을 유일한 근거다(이슈 #26).
		assertThat(first.snapshot().sessionId()).isEqualTo(roomId);
		assertThat(first.snapshot().participants()).containsExactly(firstUser);
		// 뒤에 온 사람은 입장 순서대로 방 전원을 받는다.
		assertThat(second.snapshot().participants()).containsExactly(firstUser, secondUser);
	}

	@Test
	void snapshotCountsATwoTabUserOnce() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant twoTabUser = participant("twoTab");
		PresenceParticipant watcherUser = participant("watcher");
		registry.join(roomId, UUID.randomUUID(), twoTabUser);
		registry.join(roomId, UUID.randomUUID(), twoTabUser);

		RoomSessionRegistry.RoomMembership watcher = registry.join(roomId, UUID.randomUUID(), watcherUser);

		// 명단은 연결이 아니라 사람이다 — 탭을 두 개 연 사람이 두 명으로 보이면 안 된다.
		assertThat(watcher.snapshot().participants()).containsExactly(twoTabUser, watcherUser);
	}

	@Test
	void snapshotLeavesOutWhoeverAlreadyWent() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant stayingUser = participant("staying");
		PresenceParticipant leavingUser = participant("leaving");
		UUID leavingConnectionId = UUID.randomUUID();
		registry.join(roomId, UUID.randomUUID(), stayingUser);
		registry.join(roomId, leavingConnectionId, leavingUser);

		registry.leave(roomId, leavingConnectionId, leavingUser);
		RoomSessionRegistry.RoomMembership late = registry.join(roomId, UUID.randomUUID(), anyone());

		// 늦게 온 사람은 이미 나간 사람의 퇴장 통보를 받을 수 없다. 명단에서도 빠져 있어야
		// 모르는 사람이 목록에 남지 않는다.
		assertThat(late.snapshot().participants()).doesNotContain(leavingUser);
		assertThat(late.snapshot().participants()).startsWith(stayingUser);
	}

	/** evict는 kicked 신호만 완료시킨다 — 실제 연결 제거는 CollabWebSocketHandler가 leave를 불러 한다(이슈 #135). */
	@Test
	void evictCompletesTheKickedSignalForTheMatchingSubject() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant removedUser = participant("removed");
		PresenceParticipant stayingUser = participant("staying");

		RoomSessionRegistry.RoomMembership removed = registry.join(roomId, UUID.randomUUID(), removedUser);
		RoomSessionRegistry.RoomMembership staying = registry.join(roomId, UUID.randomUUID(), stayingUser);
		AtomicBoolean removedKicked = new AtomicBoolean();
		AtomicBoolean stayingKicked = new AtomicBoolean();
		removed.kicked().doOnSuccess(ignored -> removedKicked.set(true)).subscribe();
		staying.kicked().doOnSuccess(ignored -> stayingKicked.set(true)).subscribe();

		assertThat(registry.evict(roomId, removedUser.subject())).isTrue();

		assertThat(removedKicked).isTrue();
		assertThat(stayingKicked).isFalse();
	}

	/** 같은 사람이 탭을 여럿 열었으면 evict가 그 전부의 kicked 신호를 완료시킨다. */
	@Test
	void evictKicksEveryConnectionOfTheSameSubject() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant twoTabUser = participant("twoTab");

		RoomSessionRegistry.RoomMembership firstTab = registry.join(roomId, UUID.randomUUID(), twoTabUser);
		RoomSessionRegistry.RoomMembership secondTab = registry.join(roomId, UUID.randomUUID(), twoTabUser);
		AtomicBoolean firstTabKicked = new AtomicBoolean();
		AtomicBoolean secondTabKicked = new AtomicBoolean();
		firstTab.kicked().doOnSuccess(ignored -> firstTabKicked.set(true)).subscribe();
		secondTab.kicked().doOnSuccess(ignored -> secondTabKicked.set(true)).subscribe();

		assertThat(registry.evict(roomId, twoTabUser.subject())).isTrue();

		assertThat(firstTabKicked).isTrue();
		assertThat(secondTabKicked).isTrue();
	}

	/** 방이 없거나(접속한 적 없음) 그 subject의 연결이 없으면 조용히 false만 돌려준다 — 예외를 던지지 않는다. */
	@Test
	void evictIsANoOpWhenThereIsNoMatchingConnection() {
		UUID roomId = UUID.randomUUID();

		assertThat(registry.evict(roomId, "nobody-here")).isFalse();

		registry.join(roomId, UUID.randomUUID(), anyone());
		assertThat(registry.evict(roomId, "still-nobody")).isFalse();
	}

	/**
	* RoomState.add/remove/evict는 각각 synchronized라 상호 배제는 걸려 있지만, 서로 다른
	* public 메서드(evict·leave)가 같은 연결을 동시에 건드리는 조합은 별도로 검증된 적이 없었다
	* (이슈 #135 PR 코멘트). 어느 쪽이 먼저 실행되든 예외 없이 끝나고, 방이 정상 상태로
	* 남는지(마지막 연결이 빠졌다는 신호)를 CyclicBarrier로 실제 동시 실행을 만들어 본다.
	*/
	@Test
	void evictAndLeaveRunningConcurrentlyDoNotCorruptRoomState() throws Exception {
		UUID roomId = UUID.randomUUID();
		UUID connectionId = UUID.randomUUID();
		PresenceParticipant participant = participant("racing");
		RoomSessionRegistry.RoomMembership membership = registry.join(roomId, connectionId, participant);
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);

		try {
			Future<Boolean> evicted = pool.submit(() -> {
				barrier.await();
				return registry.evict(roomId, "racing");
			});
			Future<Optional<UUID>> emptiedGeneration = pool.submit(() -> {
				barrier.await();
				return registry.leave(roomId, connectionId, participant);
			});

			// 순서와 무관하게 예외 없이 끝나야 한다 — evict가 이겨서 kicked를 완료시켰든, leave가
			// 이겨서 evict가 연결을 못 찾고 false를 돌려줬든 둘 다 정상 결과다.
			evicted.get(5, TimeUnit.SECONDS);
			// leave는 이 연결이 방의 유일한 연결이라 방이 비었다는 세대를 항상 돌려준다 — evict와의
			// 실행 순서가 이 결과를 바꾸지 않는다(evict는 leave가 지우는 연결 목록에 영향을 주지 않는다).
			assertThat(emptiedGeneration.get(5, TimeUnit.SECONDS)).contains(membership.generation());
		} finally {
			pool.shutdownNow();
		}
	}

	private void broadcastRange(UUID roomId, UUID roomGeneration, String prefix, CountDownLatch start) {
		await(start);
		for (int i = 0; i < 100; i++) {
			registry.broadcastIfCurrent(roomId, roomGeneration,
					new ChatMessageFrame(roomId, "someone", "누군가", prefix + i));
		}
	}

	private static void await(CountDownLatch start) {
		try {
			start.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

}
