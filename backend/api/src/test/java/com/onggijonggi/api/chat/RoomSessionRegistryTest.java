package com.onggijonggi.api.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Sinks;

import static com.onggijonggi.api.chat.Eventually.awaitIdle;
import static com.onggijonggi.api.chat.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 *               확인한다(이슈 #135). 퇴장 통보가 유예 시간만큼 미뤄지고, 그 안에 재연결하면
 *               퇴장·입장이 둘 다 무산되는지도 확인한다(이슈 #198) — 실제 운영 유예(수 초)를
 *               기다리지 않도록 테스트는 짧은 유예로 생성한 registry를 쓴다. 내보내기는 방별 대기열을
 *               비우는 스레드가 하므로(이슈 #405) 도착은 기다려서(Eventually) 본다.
 */
class RoomSessionRegistryTest {

	private static final Duration TEST_LEAVE_DEBOUNCE = Duration.ofMillis(50);

	private final RoomSessionRegistry registry = new RoomSessionRegistry(TEST_LEAVE_DEBOUNCE);

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

		ChatMessageFrame expected = new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, sender.subject(), sender.displayName(), "hello", List.of());
		assertThat(registry.broadcastIfCurrent(roomId, firstMembership.generation(), expected)).isTrue();
		// 먼저 들어와 있던 first는 두 번째 입장을 통보받는다. second는 그 통보가 대기열에 든 뒤 구독했으므로
		// 받을 수도 있다(이슈 #405, 내보내기가 비동기) — 스냅샷이 먼저 가고 이미 아는 사람의 join은 화면이
		// 무시하므로 해가 없다. second에게 중요한 것은 방송을 받는 것이다.
		eventually(() -> assertThat(first).containsExactly(
				new PresenceJoinFrame(roomId, secondUser.subject(), secondUser.displayName()), expected));
		eventually(() -> assertThat(second.stream().filter(ChatMessageFrame.class::isInstance)).containsExactly(expected));
		awaitIdle(registry, roomId, otherRoomId);
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

		eventually(() -> assertThat(received).containsExactly(notice));
		subscription.dispose();
	}

	/** 방이 없거나(아무도 접속한 적 없거나 이미 비어 사라진 경우) 조용히 버려진다 — 예외를 던지지 않는다. */
	/** 입력 중 알림은 그 방을 구독한 연결이 협업방에서 낼 때만 방송한다. */
	@Test
	void notifyTypingBroadcastsOnlyFromASubscribedConnectionInACollabRoom() {
		UUID collabId = UUID.randomUUID();
		UUID directId = UUID.randomUUID();
		UUID typistId = UUID.randomUUID();
		UUID directTypistId = UUID.randomUUID();
		List<WsFrame> collab = new CopyOnWriteArrayList<>();
		List<WsFrame> direct = new CopyOnWriteArrayList<>();
		Disposable collabSubscription = registry.join(collabId, typistId, participant("typist"))
				.frames().subscribe(collab::add);
		Disposable directSubscription = registry.join(directId, directTypistId, participant("owner"), false)
				.frames().subscribe(direct::add);

		ChatTypingFrame typing = new ChatTypingFrame(collabId, "typist", "typist", true);
		assertThat(registry.notifyTyping(typistId, typing)).isTrue();
		assertThat(registry.notifyTyping(UUID.randomUUID(), typing)).isFalse();
		assertThat(registry.notifyTyping(directTypistId, new ChatTypingFrame(directId, "owner", "owner", true)))
				.isFalse();

		eventually(() -> assertThat(collab).containsExactly(typing));
		awaitIdle(registry, directId);
		assertThat(direct).isEmpty();
		collabSubscription.dispose();
		directSubscription.dispose();
	}

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

			// first는 second의 입장 통보를 하나 더 갖고 있고, second도 자기 입장 통보를 받을 수 있다(이슈 #405, 내보내기가
			// 비동기) — 순서 비교 대상은 메시지뿐이다.
			eventually(() -> assertThat(first.stream().filter(ChatMessageFrame.class::isInstance)).hasSize(200));
			eventually(() -> assertThat(second.stream().filter(ChatMessageFrame.class::isInstance)).hasSize(200));
			List<WsFrame> firstMessages = first.stream().filter(ChatMessageFrame.class::isInstance).toList();
			assertThat(second.stream().filter(ChatMessageFrame.class::isInstance).toList())
					.containsExactlyElementsOf(firstMessages);
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
		ThreadWebSocketHandler.bufferForRoom(
				slowMembership.frames(), slowOverflow).subscribe(slowSubscriber);
		Disposable fastSubscription = ThreadWebSocketHandler.bufferForRoom(
				fastMembership.frames(), fastOverflow)
				.subscribe(fastFrames::add);

		for (int i = 0; i < 257; i++) {
			registry.broadcastIfCurrent(roomId, slowMembership.generation(),
					new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, "someone", "누군가", "message-" + i, List.of()));
		}

		eventually(() -> assertThat(slowOverflowed).isTrue());
		// fast는 먼저 구독한 쪽이 있으면 자기 입장 통보도 받을 수 있다(이슈 #405, 내보내기가 비동기) — 세는 것은 방송뿐이다.
		eventually(() -> assertThat(fastFrames.stream().filter(ChatMessageFrame.class::isInstance)).hasSize(257));
		assertThat(fastOverflowed).isFalse();

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
				new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, "someone", "누군가", "new room", List.of()))).isTrue();

		eventually(() -> assertThat(received).hasSize(1));

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

		eventually(() -> assertThat(first).containsExactly(
				new PresenceJoinFrame(roomId, secondUser.subject(), secondUser.displayName())));
		awaitIdle(registry, roomId);
		// second는 자기 입장 통보가 대기열에 든 뒤 구독했으므로 그것을 받을 수는 있다(이슈 #405, 내보내기가 비동기) — 스냅샷이
		// 먼저 가고 화면은 이미 아는 사람의 join을 무시한다. 그 밖의 것(다른 사람의 입장 등)은 받지 않는다.
		assertThat(second).allMatch(frame -> frame.equals(
				new PresenceJoinFrame(roomId, secondUser.subject(), secondUser.displayName())));

		firstSubscription.dispose();
		secondSubscription.dispose();
		registry.leave(roomId, secondConnectionId, secondUser);
	}

	/** DIRECT 방(presenceEnabled=false, 이슈 #162)은 두 번째 연결이 들어와도 입장을 방송하지
	 * 않는다 — presence 자체가 없는 방이다. */
	@Test
	void presenceDisabledJoinNeverAnnouncesJoin() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant firstUser = participant("first");
		PresenceParticipant secondUser = participant("second");
		List<WsFrame> first = new CopyOnWriteArrayList<>();
		List<WsFrame> second = new CopyOnWriteArrayList<>();

		Disposable firstSubscription = registry.join(roomId, UUID.randomUUID(), firstUser, false)
				.frames().subscribe(first::add);
		UUID secondConnectionId = UUID.randomUUID();
		Disposable secondSubscription = registry.join(roomId, secondConnectionId, secondUser, false)
				.frames().subscribe(second::add);

		awaitIdle(registry, roomId);
		assertThat(first).noneMatch(PresenceJoinFrame.class::isInstance);
		assertThat(second).noneMatch(PresenceJoinFrame.class::isInstance);

		firstSubscription.dispose();
		secondSubscription.dispose();
		registry.leave(roomId, secondConnectionId, secondUser);
	}

	/** presence 없는 방은 마지막이 아닌 연결이 빠져도 퇴장 유예를 걸지 않는다 — 걸어봤자 나중에
	 * 아무도 못 볼 통지라 낭비다(이슈 #162). */
	@Test
	void presenceDisabledLeaveNeverAnnouncesLeave() throws Exception {
		UUID roomId = UUID.randomUUID();
		UUID stayingConnectionId = UUID.randomUUID();
		UUID leavingConnectionId = UUID.randomUUID();
		PresenceParticipant stayingUser = participant("staying");
		PresenceParticipant leavingUser = participant("leaving");
		List<WsFrame> staying = new CopyOnWriteArrayList<>();

		registry.join(roomId, stayingConnectionId, stayingUser, false).frames().subscribe(staying::add);
		registry.join(roomId, leavingConnectionId, leavingUser, false).frames().subscribe();

		registry.leave(roomId, leavingConnectionId, leavingUser);
		Thread.sleep(TEST_LEAVE_DEBOUNCE.toMillis() * 4);

		assertThat(staying).noneMatch(PresenceLeaveFrame.class::isInstance);
	}

	/** 퇴장 통보는 유예(이슈 #198) 뒤에 도착하므로 리스트를 바로 보지 않고 도착을 기다린다. */
	@Test
	void announcesLeaveToTheRemainingMembers() throws Exception {
		UUID roomId = UUID.randomUUID();
		UUID stayingConnectionId = UUID.randomUUID();
		UUID leavingConnectionId = UUID.randomUUID();
		PresenceParticipant leavingUser = participant("leaving");
		List<WsFrame> staying = new CopyOnWriteArrayList<>();
		CountDownLatch leaveReceived = new CountDownLatch(1);

		Disposable stayingSubscription = registry.join(roomId, stayingConnectionId, anyone())
				.frames().subscribe(frame -> {
					staying.add(frame);
					if (frame instanceof PresenceLeaveFrame) {
						leaveReceived.countDown();
					}
				});
		registry.join(roomId, leavingConnectionId, leavingUser).frames().subscribe();
		eventually(() -> assertThat(staying).hasSize(1)); // 입장 통보가 도착한 뒤에 비운다
		staying.clear();

		registry.leave(roomId, leavingConnectionId, leavingUser);

		assertThat(leaveReceived.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(staying).containsExactly(new PresenceLeaveFrame(roomId, leavingUser.subject(), leavingUser.displayName()));

		stayingSubscription.dispose();
	}

	/**
	 * 유예 시간 안에 같은 사람이 재연결하면 퇴장도 입장도 방송되지 않는다 — 화면에 아무것도
	 * 보이지 않아야 한다는 이슈 #198의 핵심 요구사항. 유예가 지나고도 한참 더 기다려 스케줄이
	 * 실제로 dispose됐는지(타이밍 우연이 아닌지) 확인한다.
	 */
	@Test
	void cancelsTheLeaveAndJoinAnnouncementsWhenTheSameUserReconnectsWithinTheDebounce() throws Exception {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant flappingUser = participant("flapping");
		UUID oldConnectionId = UUID.randomUUID();
		List<WsFrame> watcher = new CopyOnWriteArrayList<>();

		Disposable watcherSubscription = registry.join(roomId, UUID.randomUUID(), anyone())
				.frames().subscribe(watcher::add);
		registry.join(roomId, oldConnectionId, flappingUser).frames().subscribe();
		eventually(() -> assertThat(watcher).hasSize(1)); // 입장 통보가 도착한 뒤에 비운다
		watcher.clear();

		registry.leave(roomId, oldConnectionId, flappingUser);
		Disposable reconnected = registry.join(roomId, UUID.randomUUID(), flappingUser).frames().subscribe();

		Thread.sleep(TEST_LEAVE_DEBOUNCE.toMillis() * 4);
		assertThat(watcher).isEmpty();

		watcherSubscription.dispose();
		reconnected.dispose();
	}

	/**
	 * 유예 중에도 그 사람은 아직 방을 나간 게 아니므로, 그사이 새로 들어온 사람의 스냅샷에도
	 * 보여야 한다 — 안 그러면 기존 참여자 화면과 어긋난 채로, 그것도 재연결이 성공하면 입장
	 * 방송조차 나가지 않아(위 취소 테스트) 새로 들어온 사람만 영영 그 사람을 모르게 된다.
	 */
	@Test
	void includesAUserWithAPendingDepartureInANewJoinersSnapshot() {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant staying = participant("staying");
		PresenceParticipant flapping = participant("flapping");
		UUID flappingConnectionId = UUID.randomUUID();

		registry.join(roomId, UUID.randomUUID(), staying).frames().subscribe();
		registry.join(roomId, flappingConnectionId, flapping).frames().subscribe();

		registry.leave(roomId, flappingConnectionId, flapping);

		RoomSessionRegistry.RoomMembership lateJoiner = registry.join(roomId, UUID.randomUUID(), anyone());
		assertThat(lateJoiner.snapshot().participants()).contains(flapping);
	}

	/** 유예 안에 재연결이 없으면 그대로 퇴장이 방송된다 — 취소 로직이 진짜로 지연시킬 뿐 억누르지 않는지 확인한다. */
	@Test
	void announcesLeaveAfterTheDebounceElapsesWithoutReconnect() throws Exception {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant leavingUser = participant("no-return");
		UUID connectionId = UUID.randomUUID();
		List<WsFrame> watcher = new CopyOnWriteArrayList<>();
		CountDownLatch leaveReceived = new CountDownLatch(1);

		Disposable watcherSubscription = registry.join(roomId, UUID.randomUUID(), anyone())
				.frames().subscribe(frame -> {
					watcher.add(frame);
					if (frame instanceof PresenceLeaveFrame) {
						leaveReceived.countDown();
					}
				});
		registry.join(roomId, connectionId, leavingUser).frames().subscribe();
		eventually(() -> assertThat(watcher).hasSize(1)); // 입장 통보가 도착한 뒤에 비운다
		watcher.clear();

		registry.leave(roomId, connectionId, leavingUser);

		assertThat(leaveReceived.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(watcher).containsExactly(new PresenceLeaveFrame(roomId, leavingUser.subject(), leavingUser.displayName()));

		watcherSubscription.dispose();
	}

	@Test
	void doesNotAnnounceLeaveWhenTheLastMemberLeaves() throws Exception {
		UUID roomId = UUID.randomUUID();
		UUID onlyConnectionId = UUID.randomUUID();
		PresenceParticipant onlyUser = participant("only");
		List<WsFrame> received = new CopyOnWriteArrayList<>();

		Disposable subscription = registry.join(roomId, onlyConnectionId, onlyUser)
				.frames().subscribe(received::add);

		// 마지막 퇴장이면 방이 사라진다. 사라진 방에 방송하면 예외이므로 아무것도 내지 않아야 한다.
		registry.leave(roomId, onlyConnectionId, onlyUser);

		Thread.sleep(TEST_LEAVE_DEBOUNCE.toMillis() * 4);
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
		eventually(() -> assertThat(watcher).hasSize(1)); // 입장 통보가 도착한 뒤에 비운다
		watcher.clear();

		Disposable secondTab = registry.join(roomId, UUID.randomUUID(), twoTabUser).frames().subscribe();

		// 이미 방에 있는 사람이 탭을 하나 더 연 것은 입장이 아니다.
		awaitIdle(registry, roomId);
		assertThat(watcher).isEmpty();

		watcherSubscription.dispose();
		firstTab.dispose();
		secondTab.dispose();
	}

	@Test
	void announcesLeaveOnlyWhenTheSameUsersLastConnectionGoes() throws Exception {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant twoTabUser = participant("twoTab");
		UUID firstTabId = UUID.randomUUID();
		UUID secondTabId = UUID.randomUUID();
		List<WsFrame> watcher = new CopyOnWriteArrayList<>();
		CountDownLatch leaveReceived = new CountDownLatch(1);

		Disposable watcherSubscription = registry.join(roomId, UUID.randomUUID(), anyone())
				.frames().subscribe(frame -> {
					watcher.add(frame);
					if (frame instanceof PresenceLeaveFrame) {
						leaveReceived.countDown();
					}
				});
		Disposable firstTab = registry.join(roomId, firstTabId, twoTabUser).frames().subscribe();
		Disposable secondTab = registry.join(roomId, secondTabId, twoTabUser).frames().subscribe();
		eventually(() -> assertThat(watcher).hasSize(1)); // 첫 탭의 입장 통보가 도착한 뒤에 비운다
		watcher.clear();

		registry.leave(roomId, secondTabId, twoTabUser);

		// 탭 하나가 닫혀도 다른 탭이 남아 있으면 그 사람은 아직 방에 있다. 재연결도 같은 모양이다 —
		// 새 연결이 먼저 등록되고 죽은 옛 연결의 doFinally가 뒤늦게 도는 순서라, 여기서 통보가
		// 나가면 남은 사람 목록에서 그 사람이 사라진 채로 남는다.
		Thread.sleep(TEST_LEAVE_DEBOUNCE.toMillis() * 4);
		assertThat(watcher).isEmpty();

		registry.leave(roomId, firstTabId, twoTabUser);

		assertThat(leaveReceived.await(1, TimeUnit.SECONDS)).isTrue();
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
					new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, "someone", "누군가", "stale", List.of()))).isFalse();
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
						new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, "someone", "누군가", "racing", List.of()));
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
						new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, "someone", "누군가", "stale", List.of()))).isFalse();
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
		assertThat(first.snapshot().threadId()).isEqualTo(roomId);
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

	/**
	 * 퇴장 유예(이슈 #198)가 끝나 그 사람이 정말로 나갔다고 확정된 뒤의 얘기다 — 유예 중에는
	 * 아직 나간 게 아니라서 늦게 온 사람의 명단에도 보인다(별도로
	 * includesAUserWithAPendingDepartureInANewJoinersSnapshot이 검증한다). 그래서 유예가
	 * 지나가길 기다린 뒤에 늦게 join한다.
	 */
	@Test
	void snapshotLeavesOutWhoeverAlreadyWent() throws Exception {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant stayingUser = participant("staying");
		PresenceParticipant leavingUser = participant("leaving");
		UUID leavingConnectionId = UUID.randomUUID();
		registry.join(roomId, UUID.randomUUID(), stayingUser);
		registry.join(roomId, leavingConnectionId, leavingUser);

		registry.leave(roomId, leavingConnectionId, leavingUser);
		Thread.sleep(TEST_LEAVE_DEBOUNCE.toMillis() * 4);
		RoomSessionRegistry.RoomMembership late = registry.join(roomId, UUID.randomUUID(), anyone());

		// 늦게 온 사람은 이미 나간 사람의 퇴장 통보를 받을 수 없다. 명단에서도 빠져 있어야
		// 모르는 사람이 목록에 남지 않는다.
		assertThat(late.snapshot().participants()).doesNotContain(leavingUser);
		assertThat(late.snapshot().participants()).startsWith(stayingUser);
	}

	/** evict는 kicked 신호만 완료시킨다 — 실제 연결 제거는 ThreadWebSocketHandler가 leave를 불러 한다(이슈 #135). */
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

		eventually(() -> assertThat(removedKicked).isTrue());
		awaitIdle(registry, roomId);
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

		eventually(() -> assertThat(firstTabKicked).isTrue());
		eventually(() -> assertThat(secondTabKicked).isTrue());
	}

	/** 방이 없거나(접속한 적 없음) 그 subject의 연결이 없으면 조용히 false만 돌려준다 — 예외를 던지지 않는다. */
	@Test
	void evictIsANoOpWhenThereIsNoMatchingConnection() {
		UUID roomId = UUID.randomUUID();

		assertThat(registry.evict(roomId, "nobody-here")).isFalse();

		registry.join(roomId, UUID.randomUUID(), anyone());
		assertThat(registry.evict(roomId, "still-nobody")).isFalse();
	}

	@Test
	void evictCollabSubscriptionsLeavesDirectSubscriptionsUntouched() {
		String subject = "same-subject";
		UUID directRoomId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership collab = registry.join(UUID.randomUUID(), UUID.randomUUID(), participant(subject));
		RoomSessionRegistry.RoomMembership direct = registry.join(directRoomId, UUID.randomUUID(), participant(subject), false);
		AtomicBoolean collabKicked = new AtomicBoolean();
		AtomicBoolean directKicked = new AtomicBoolean();
		collab.kicked().doOnSuccess(ignored -> collabKicked.set(true)).subscribe();
		direct.kicked().doOnSuccess(ignored -> directKicked.set(true)).subscribe();

		assertThat(registry.evictCollabSubscriptions(subject)).isTrue();

		eventually(() -> assertThat(collabKicked).isTrue());
		awaitIdle(registry, directRoomId);
		assertThat(directKicked).isFalse();
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

	/** 한 커넥션이 여러 방에 들 수 있고, 방마다 그 방의 세대를 따로 돌려받는다(이슈 #161). */
	@Test
	void oneConnectionJoinsSeveralRoomsAndLooksUpEachGeneration() {
		UUID connectionId = UUID.randomUUID();
		UUID firstRoomId = UUID.randomUUID();
		UUID secondRoomId = UUID.randomUUID();
		PresenceParticipant user = participant("multi-room");

		RoomSessionRegistry.RoomMembership first = registry.join(firstRoomId, connectionId, user);
		RoomSessionRegistry.RoomMembership second = registry.join(secondRoomId, connectionId, user);

		assertThat(registry.generationFor(firstRoomId, connectionId)).contains(first.generation());
		assertThat(registry.generationFor(secondRoomId, connectionId)).contains(second.generation());
		assertThat(registry.generationFor(firstRoomId, UUID.randomUUID())).isEmpty();

		registry.leave(firstRoomId, connectionId, user);

		// 한 방에서 빠져도 다른 방 구독은 그대로다.
		assertThat(registry.generationFor(firstRoomId, connectionId)).isEmpty();
		assertThat(registry.generationFor(secondRoomId, connectionId)).contains(second.generation());
		registry.leave(secondRoomId, connectionId, user);
	}

	/**
	 * 커넥션이 끊기면 그 커넥션이 든 방 전부에서 빠진다(이슈 #161). 비어서 사라진 방의 세대만
	 * 돌려준다 — 다른 사람이 남은 방은 닫히지 않는다.
	 */
	@Test
	void leaveAllRemovesTheConnectionFromEveryRoomAndReportsOnlyEmptiedRooms() {
		UUID connectionId = UUID.randomUUID();
		UUID aloneRoomId = UUID.randomUUID();
		UUID sharedRoomId = UUID.randomUUID();
		PresenceParticipant user = participant("closing");
		UUID otherConnectionId = UUID.randomUUID();

		RoomSessionRegistry.RoomMembership alone = registry.join(aloneRoomId, connectionId, user);
		registry.join(sharedRoomId, connectionId, user);
		RoomSessionRegistry.RoomMembership shared = registry.join(sharedRoomId, otherConnectionId, anyone());

		assertThat(registry.leaveAll(connectionId, user)).containsExactly(Map.entry(aloneRoomId, alone.generation()));

		assertThat(registry.generationFor(aloneRoomId, connectionId)).isEmpty();
		assertThat(registry.generationFor(sharedRoomId, connectionId)).isEmpty();
		assertThat(registry.generationFor(sharedRoomId, otherConnectionId)).contains(shared.generation());
		// 이미 다 빠진 커넥션을 다시 부르면 할 일이 없다.
		assertThat(registry.leaveAll(connectionId, user)).isEmpty();
		registry.leave(sharedRoomId, otherConnectionId, anyone());
	}

	/**
	 * left는 그 연결이 방에서 빠지는 순간 완료된다(이슈 #161) — 방에 남은 사람이 있어 frames가
	 * 끝나지 않아도 구독을 끊을 수 있어야 한다. 다른 연결의 left는 건드리지 않는다.
	 */
	@Test
	void leftCompletesForTheLeavingConnectionOnly() {
		UUID roomId = UUID.randomUUID();
		UUID leavingConnectionId = UUID.randomUUID();
		PresenceParticipant leavingUser = participant("leaving");
		RoomSessionRegistry.RoomMembership leaving = registry.join(roomId, leavingConnectionId, leavingUser);
		RoomSessionRegistry.RoomMembership staying = registry.join(roomId, UUID.randomUUID(), anyone());
		AtomicBoolean leavingLeft = new AtomicBoolean();
		AtomicBoolean stayingLeft = new AtomicBoolean();
		leaving.left().doOnSuccess(ignored -> leavingLeft.set(true)).subscribe();
		staying.left().doOnSuccess(ignored -> stayingLeft.set(true)).subscribe();

		registry.leave(roomId, leavingConnectionId, leavingUser);

		eventually(() -> assertThat(leavingLeft).isTrue());
		awaitIdle(registry, roomId);
		assertThat(stayingLeft).isFalse();
	}

	/**
	* 방 대기열(이슈 #405)은 넣은 순서대로 나간다 — 한 차례에 다 못 비워 다음 차례로 넘어가도(DRAIN_BATCH)
	* 순서가 지켜지고, left는 그보다 먼저 넣은 방송 뒤에, 방 완료는 맨 마지막에 온다.
	*/
	@Test
	void outboxDeliversFramesThenLeftThenCompletionInOrder() {
		UUID roomId = UUID.randomUUID();
		UUID connectionId = UUID.randomUUID();
		PresenceParticipant user = anyone();
		RoomSessionRegistry.RoomMembership membership = registry.join(roomId, connectionId, user);
		List<String> events = new CopyOnWriteArrayList<>();
		membership.frames().subscribe(frame -> events.add(((ChatMessageFrame) frame).content()), null,
				() -> events.add("complete"));
		membership.left().subscribe(null, null, () -> events.add("left"));
		List<String> expected = new ArrayList<>();
		int count = RoomSessionRegistry.DRAIN_BATCH * 3 + 1;

		for (int i = 0; i < count; i++) {
			assertThat(registry.broadcastIfCurrent(roomId, membership.generation(), message(roomId, "m" + i))).isTrue();
			expected.add("m" + i);
		}
		registry.leave(roomId, connectionId, user);
		expected.add("left");
		expected.add("complete");

		eventually(() -> assertThat(events).containsExactlyElementsOf(expected));
		assertThat(registry.broadcastIfCurrent(roomId, membership.generation(), message(roomId, "late"))).isFalse();
	}

	/**
	* 내보내기가 실패하면(여기서는 듣는 사람 없이 warm-up 버퍼 한 칸을 넘김) registry가 그 방·세대를 실패 알림으로 보낸다 —
	* dispatcher가 그 세대 AI 작업을 닫는 신호다(이슈 #405). 실패를 그 방의 다음 방송에 예외로 떠넘기지 않으므로 다음 방송은
	* 그대로 들어간다. 실패한 뒤에도 left는 나간다.
	*/
	@Test
	void aFailedEmissionIsReportedForItsRoomAndGenerationWithoutFailingTheNextBroadcast() {
		UUID roomId = UUID.randomUUID();
		UUID connectionId = UUID.randomUUID();
		PresenceParticipant user = anyone();
		List<List<UUID>> failures = new CopyOnWriteArrayList<>();
		registry.onBroadcastFailure((threadId, generation) -> failures.add(List.of(threadId, generation)));
		RoomSessionRegistry.RoomMembership membership = registry.join(roomId, connectionId, user);
		AtomicBoolean left = new AtomicBoolean();
		membership.left().subscribe(null, null, () -> left.set(true));

		assertThat(registry.broadcastIfCurrent(roomId, membership.generation(), message(roomId, "buffered"))).isTrue();
		assertThat(registry.broadcastIfCurrent(roomId, membership.generation(), message(roomId, "overflow"))).isTrue();

		eventually(() -> assertThat(failures).containsExactly(List.of(roomId, membership.generation())));
		assertThat(registry.broadcastIfCurrent(roomId, membership.generation(), message(roomId, "next"))).isTrue();
		registry.leave(roomId, connectionId, user);
		eventually(() -> assertThat(left).isTrue());
	}

	/**
	* 대기열 상한: 구독자가 멈춰 비우기가 밀리면 방송은 MAX_PENDING개까지만 들어가고 그 다음 방송은 예외를 받는다. 입력 중
	* 같은 통보는 따로 세어(MAX_PENDING_NOTICES) 넘친 것만 조용히 버려지고 방송 자리를 먹지 않는다. notifyIfListening
	* 통보(참여자 변경 등)는 상한으로 버리지 않는다. left는 상한과 상관없이 들어가 풀린 뒤 전달되고, 대기열이 빠지면 방송이
	* 다시 된다.
	*/
	@Test
	void aFullOutboxRejectsBroadcastsUntilItDrainsWhileNoticesHaveTheirOwnLimit() throws Exception {
		UUID roomId = UUID.randomUUID();
		PresenceParticipant leavingUser = participant("leaving");
		UUID leavingConnectionId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership watching = registry.join(roomId, UUID.randomUUID(), participant("watching"));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<String> received = new CopyOnWriteArrayList<>();
		AtomicInteger typings = new AtomicInteger();
		Disposable subscription = watching.frames().subscribe(frame -> {
			if (frame instanceof ChatTypingFrame) {
				typings.incrementAndGet();
			}
			if (frame instanceof ChatMessageFrame chat) {
				received.add(chat.content());
				if (entered.getCount() > 0) {
					entered.countDown();
					await(release);
				}
			}
		});
		RoomSessionRegistry.RoomMembership leaving = registry.join(roomId, leavingConnectionId, leavingUser);
		AtomicBoolean left = new AtomicBoolean();
		leaving.left().subscribe(null, null, () -> left.set(true));

		try {
			registry.broadcastIfCurrent(roomId, watching.generation(), message(roomId, "hold"));
			assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
			for (int i = 0; i < RoomSessionRegistry.MAX_PENDING_NOTICES + 10; i++) {
				assertThat(registry.notifyTyping(leavingConnectionId,
						new ChatTypingFrame(roomId, leavingUser.subject(), leavingUser.displayName(), true))).isTrue();
			}
			registry.notifyIfListening(roomId, message(roomId, "listening"));
			for (int i = 0; i < RoomSessionRegistry.MAX_PENDING; i++) {
				assertThat(registry.broadcastIfCurrent(roomId, watching.generation(), message(roomId, "q" + i))).isTrue();
			}
			assertThatThrownBy(() -> registry.broadcastIfCurrent(roomId, watching.generation(), message(roomId, "over")))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("queue full");

			registry.leave(roomId, leavingConnectionId, leavingUser);
		} finally {
			release.countDown();
		}
		eventually(() -> assertThat(left).isTrue());
		awaitIdle(registry, roomId);
		assertThat(typings).hasValue(RoomSessionRegistry.MAX_PENDING_NOTICES);
		assertThat(received).contains("listening", "q0", "q" + (RoomSessionRegistry.MAX_PENDING - 1));
		assertThat(registry.broadcastIfCurrent(roomId, watching.generation(), message(roomId, "after drain"))).isTrue();
		eventually(() -> assertThat(received).contains("after drain"));
		subscription.dispose();
	}

	/**
	* 부가 통보를 보낼지는 내보내는 순간에 정한다(이슈 #102·#405) — 넣을 때는 듣는 사람이 있었어도 내보내기 전에
	* 다 빠졌으면 버린다. 버리지 않으면 warm-up 버퍼에 남아, 다음에 붙은 사람이 그 통보를 첫 프레임으로 받는다.
	*/
	@Test
	void aNoticeIsDroppedWhenTheLastListenerLeftBeforeItWasSent() throws Exception {
		UUID roomId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership membership = registry.join(roomId, UUID.randomUUID(), anyone());
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Disposable listening = membership.frames().subscribe(frame -> {
			entered.countDown();
			await(release);
		});
		registry.broadcastIfCurrent(roomId, membership.generation(), message(roomId, "hold"));
		assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

		registry.notifyIfListening(roomId, message(roomId, "notice"));
		listening.dispose();
		release.countDown();
		awaitIdle(registry, roomId);

		List<WsFrame> received = new CopyOnWriteArrayList<>();
		Disposable next = membership.frames().subscribe(received::add);
		ChatMessageFrame first = message(roomId, "first");
		assertThat(registry.broadcastIfCurrent(roomId, membership.generation(), first)).isTrue();
		eventually(() -> assertThat(received).containsExactly(first));
		next.dispose();
	}

	/** 구독자가 받은 자리에서 같은 방에 다시 방송해도 된다 — 다음 차례에 나간다. 예전에는 sink에 겹쳐 내보내 실패했다. */
	@Test
	void aSubscriberCanBroadcastToTheSameRoomFromItsCallback() {
		UUID roomId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership membership = registry.join(roomId, UUID.randomUUID(), anyone());
		List<String> received = new CopyOnWriteArrayList<>();
		Disposable subscription = membership.frames().subscribe(frame -> {
			String content = ((ChatMessageFrame) frame).content();
			received.add(content);
			if ("ping".equals(content)) {
				registry.broadcastIfCurrent(roomId, membership.generation(), message(roomId, "pong"));
			}
		});

		registry.broadcastIfCurrent(roomId, membership.generation(), message(roomId, "ping"));

		eventually(() -> assertThat(received).containsExactly("ping", "pong"));
		subscription.dispose();
	}

	/**
	* 화면 힌트(notifyIfCurrent, 이슈 #397)는 그 세대에만 가고, 전송이 실패해도 방송 실패로 알리지 않는다 — 힌트 하나 때문에
	* 답변 세대가 닫히지 않게. 옛 세대로는 들어가지 않는다.
	*/
	@Test
	void currentGenerationHintsReachListenersButNeverReportABroadcastFailure() {
		UUID roomId = UUID.randomUUID();
		List<List<UUID>> failures = new CopyOnWriteArrayList<>();
		registry.onBroadcastFailure((threadId, generation) -> failures.add(List.of(threadId, generation)));
		RoomSessionRegistry.RoomMembership membership = registry.join(roomId, UUID.randomUUID(), anyone());
		List<WsFrame> received = new CopyOnWriteArrayList<>();
		Disposable subscription = membership.frames().subscribe(received::add);

		ChatMessageFrame hint = message(roomId, "hint");
		registry.notifyIfCurrent(roomId, membership.generation(), hint);
		registry.notifyIfCurrent(roomId, UUID.randomUUID(), message(roomId, "stale"));
		eventually(() -> assertThat(received).containsExactly(hint));

		// 듣는 사람 없이 warm-up 버퍼를 넘겨도(방송이면 실패 알림이 갈 상황) 힌트는 조용히 버려진다.
		subscription.dispose();
		registry.notifyIfCurrent(roomId, membership.generation(), message(roomId, "a"));
		registry.notifyIfCurrent(roomId, membership.generation(), message(roomId, "b"));
		awaitIdle(registry, roomId);
		assertThat(failures).isEmpty();
	}

	private static ChatMessageFrame message(UUID roomId, String content) {
		return new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, "someone", "누군가", content, List.of());
	}

	private void broadcastRange(UUID roomId, UUID roomGeneration, String prefix, CountDownLatch start) {
		await(start);
		for (int i = 0; i < 100; i++) {
			registry.broadcastIfCurrent(roomId, roomGeneration,
					new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, "someone", "누군가", prefix + i, List.of()));
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
