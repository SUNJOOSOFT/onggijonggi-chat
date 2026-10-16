package com.onggijonggi.api.chat;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Class Name : RoomSessionRegistryDeadlockTest.java
 * Description : 방 접속 관리(RoomSessionRegistry)가 구독자 코드를 락을 쥔 채로 부르지 않는지 내보내는 경로마다
 *               확인한다(이슈 #405). 예전에는 방송·입퇴장 통보·left·kicked·방 완료를 락 안에서, 그것도 부른 쪽
 *               스레드에서 내보냈다 — 그 순간 닫히는 연결의 종료 처리(leaveAll → leave)가 같은 스레드에서 돌며
 *               맵 항목 락을 요청하고, 다른 스레드의 leave는 맵 항목 락을 쥔 채 방 상태 락을 기다려 교착했다.
 *
 *               경로마다 구독자 콜백을 래치로 멈춰 두고, 그동안 다른 스레드의 퇴장·입장·방송이 바로 끝나는지
 *               (= 콜백이 registry 락을 쥐고 있지 않은지), 그리고 내보내기를 부른 스레드가 콜백을 기다리지 않고
 *               돌아왔는지를 본다. 풀어 준 뒤에는 콜백 안에서 registry를 다시 불러도 끝나는지 본다. 예전 코드에서는
 *               이 테스트들이 모두 시간 제한에 걸린다.
 */
class RoomSessionRegistryDeadlockTest {

	private static final Duration LIMIT = Duration.ofSeconds(3);

	private final RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));

	/** 이슈 #405의 재현 그대로 — 방송을 받은 구독자가 그 자리에서 방을 나가는 동안 다른 연결도 나간다. */
	@Test
	void broadcastDoesNotHoldTheRoomLockWhileASubscriberLeaves() throws Exception {
		Room room = room();
		Gate gate = room.holdFramesWhen(frame -> frame instanceof ChatMessageFrame,
				() -> registry.leave(room.id, room.subscriber, participant("subscriber")));

		assertCalledBackWithoutLocks(room, gate,
				() -> registry.broadcastIfCurrent(room.id, room.generation, chat(room.id, "hello")));
	}

	@Test
	void notifyIfListeningDoesNotHoldTheRoomLockWhileASubscriberLeaves() throws Exception {
		Room room = room();
		Gate gate = room.holdFramesWhen(frame -> frame instanceof SystemNoticeFrame,
				() -> registry.leave(room.id, room.subscriber, participant("subscriber")));

		assertCalledBackWithoutLocks(room, gate, () -> registry.notifyIfListening(room.id,
				new SystemNoticeFrame(room.id, "info", "TEST", "notice", null)));
	}

	/** 입장 통보는 맵 항목 락(rooms.compute) 안에서 정해진다 — 예전에는 그 락을 쥔 채 내보냈다. */
	@Test
	void joinAnnouncementDoesNotHoldTheMapLockWhileASubscriberLeaves() throws Exception {
		Room room = room();
		Gate gate = room.holdFramesWhen(frame -> frame instanceof PresenceJoinFrame,
				() -> registry.leave(room.id, room.subscriber, participant("subscriber")));

		assertCalledBackWithoutLocks(room, gate,
				() -> registry.join(room.id, UUID.randomUUID(), participant("newcomer")));
	}

	@Test
	void typingNoticeDoesNotHoldTheRoomLockWhileASubscriberLeaves() throws Exception {
		Room room = room();
		Gate gate = room.holdFramesWhen(frame -> frame instanceof ChatTypingFrame,
				() -> registry.leave(room.id, room.subscriber, participant("subscriber")));

		assertCalledBackWithoutLocks(room, gate, () -> registry.notifyTyping(room.typist,
				new ChatTypingFrame(room.id, "typist", "typist 님", true)));
	}

	/** 퇴장 통보는 유예가 끝난 타이머 스레드에서 확정된다 — 예전에는 방 상태 락을 쥔 채 내보냈다. */
	@Test
	void confirmedDepartureDoesNotHoldTheRoomLockWhileASubscriberLeaves() throws Exception {
		Room room = room();
		Gate gate = room.holdFramesWhen(frame -> frame instanceof PresenceLeaveFrame,
				() -> registry.leave(room.id, room.subscriber, participant("subscriber")));

		assertCalledBackWithoutLocks(room, gate, () -> registry.leave(room.id, room.typist, participant("typist")));
	}

	/** 핸들러는 left를 받으면 그 방 구독을 정리한다 — 예전에는 leave가 맵 항목 락을 쥔 채 left를 냈다. */
	@Test
	void leftSignalDoesNotHoldTheMapLockWhileTheSubscriberRejoins() throws Exception {
		Room room = room();
		Gate gate = new Gate(() -> registry.join(room.id, UUID.randomUUID(), participant("subscriber")));
		room.membership.left().subscribe(null, null, gate::hold);

		assertCalledBackWithoutLocks(room, gate,
				() -> registry.leave(room.id, room.subscriber, participant("subscriber")));
	}

	/** 핸들러는 kicked를 받으면 그 방에서 나간다(leave) — 예전에는 evict가 방 상태 락을 쥔 채 kicked를 냈다. */
	@Test
	void kickedSignalDoesNotHoldTheRoomLockWhileTheSubscriberLeaves() throws Exception {
		Room room = room();
		Gate gate = new Gate(() -> registry.leave(room.id, room.subscriber, participant("subscriber")));
		room.membership.kicked().subscribe(null, null, gate::hold);

		assertCalledBackWithoutLocks(room, gate, () -> registry.evict(room.id, "subscriber"));
	}

	/** 마지막 연결이 나가며 방이 완료된다 — 예전에는 leave가 맵 항목 락을 쥔 채 완료 신호를 냈다. */
	@Test
	void roomCompletionDoesNotHoldTheMapLockWhileSomeoneJoins() throws Exception {
		UUID roomId = UUID.randomUUID();
		UUID last = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership membership = registry.join(roomId, last, participant("last"));
		Gate gate = new Gate(() -> registry.join(roomId, UUID.randomUUID(), participant("comer")));
		membership.frames().subscribe(null, null, gate::hold);

		Thread caller = start("leave last", () -> registry.leave(roomId, last, participant("last")));
		assertThat(gate.entered.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS)).as("완료 콜백 진입").isTrue();
		within("빈 방에 새 입장", () -> registry.join(roomId, UUID.randomUUID(), participant("other")));
		assertReturnedWithoutWaitingForTheCallback(caller, gate);
		gate.release.countDown();
		assertThat(gate.done.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS)).as("콜백 안의 재입장").isTrue();
		assertNoDeadlock();
	}

	/** 방 X 구독자가 방 Y에서, 방 Y 구독자가 방 X에서 동시에 나간다 — 예전에는 두 방 락을 엇갈려 잡았다. */
	@Test
	void subscribersOfTwoRoomsLeavingEachOthersRoomDoNotDeadlock() throws Exception {
		Room x = room();
		Room y = room();
		CountDownLatch bothEntered = new CountDownLatch(2);
		CountDownLatch bothDone = new CountDownLatch(2);
		x.membership.frames().subscribe(frame -> {
			if (frame instanceof ChatMessageFrame) {
				crossLeave(bothEntered, bothDone, y);
			}
		});
		y.membership.frames().subscribe(frame -> {
			if (frame instanceof ChatMessageFrame) {
				crossLeave(bothEntered, bothDone, x);
			}
		});

		Thread toX = start("broadcast X", () -> registry.broadcastIfCurrent(x.id, x.generation, chat(x.id, "x")));
		Thread toY = start("broadcast Y", () -> registry.broadcastIfCurrent(y.id, y.generation, chat(y.id, "y")));

		assertThat(bothDone.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS)).as("두 콜백의 교차 퇴장").isTrue();
		joined(toX);
		joined(toY);
		assertNoDeadlock();
	}

	/**
	 * 방송을 부른 쪽이 자기 락을 쥔 채 부르는 경우(dispatcher가 방 상태를 잠근 채 방송한다). 방 X 구독자가 L2를,
	 * 방 Y 구독자가 L1을 필요로 하는데 L1·L2를 쥔 두 스레드가 각각 X·Y에 방송한다 — 구독자 코드가 부른 쪽
	 * 스레드에서 돌면 서로의 락을 기다려 교착한다.
	 */
	@Test
	void callersHoldingTheirOwnLocksDoNotDeadlockWithSubscribersNeedingThem() throws Exception {
		Room x = room();
		Room y = room();
		Object l1 = new Object();
		Object l2 = new Object();
		CountDownLatch bothDone = new CountDownLatch(2);
		x.membership.frames().subscribe(frame -> {
			if (frame instanceof ChatMessageFrame) {
				synchronized (l2) {
					bothDone.countDown();
				}
			}
		});
		y.membership.frames().subscribe(frame -> {
			if (frame instanceof ChatMessageFrame) {
				synchronized (l1) {
					bothDone.countDown();
				}
			}
		});
		CyclicBarrier bothLocked = new CyclicBarrier(2);

		Thread toX = start("broadcast X holding L1", () -> {
			synchronized (l1) {
				awaitBarrier(bothLocked);
				registry.broadcastIfCurrent(x.id, x.generation, chat(x.id, "x"));
			}
		});
		Thread toY = start("broadcast Y holding L2", () -> {
			synchronized (l2) {
				awaitBarrier(bothLocked);
				registry.broadcastIfCurrent(y.id, y.generation, chat(y.id, "y"));
			}
		});

		joined(toX);
		joined(toY);
		assertThat(bothDone.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS)).as("두 콜백").isTrue();
		assertNoDeadlock();
	}

	/**
	 * 콜백이 멈춰 있는 동안 다른 스레드의 퇴장·입장·방송이 바로 끝나는지, 내보내기를 부른 스레드가 콜백을 기다리지
	 * 않고 돌아왔는지 본다. 풀어 준 뒤에는 콜백 안의 registry 재호출이 끝나는지 본다.
	 */
	private void assertCalledBackWithoutLocks(Room room, Gate gate, Runnable trigger) throws Exception {
		Thread caller = start("trigger", trigger);
		assertThat(gate.entered.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS)).as("구독자 콜백 진입").isTrue();

		within("다른 연결 퇴장", () -> registry.leave(room.id, room.bystander, participant("bystander")));
		within("새 연결 입장", () -> registry.join(room.id, UUID.randomUUID(), participant("late")));
		within("방송", () -> registry.broadcastIfCurrent(room.id, room.generation, chat(room.id, "during")));
		assertReturnedWithoutWaitingForTheCallback(caller, gate);

		gate.release.countDown();
		assertThat(gate.done.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS)).as("콜백 안의 registry 재호출").isTrue();
		assertNoDeadlock();
	}

	private static void assertReturnedWithoutWaitingForTheCallback(Thread caller, Gate gate) throws Exception {
		joined(caller);
		assertThat(gate.callbackThread.get()).as("구독자 코드는 부른 쪽 스레드에서 돌지 않는다").isNotSameAs(caller);
	}

	private void crossLeave(CountDownLatch bothEntered, CountDownLatch bothDone, Room other) {
		bothEntered.countDown();
		try {
			bothEntered.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return;
		}
		registry.leave(other.id, other.bystander, participant("bystander"));
		bothDone.countDown();
	}

	/**
	 * 구독자 하나(subscriber)와 곁에 있는 연결 둘(bystander, typist)이 든 협업방. bystander는 콜백이 멈춘 동안
	 * 다른 스레드가 내보낼 퇴장용이고, typist는 입력 중·퇴장 통보를 일으키는 쪽이다.
	 */
	private Room room() throws InterruptedException {
		UUID roomId = UUID.randomUUID();
		UUID subscriber = UUID.randomUUID();
		UUID bystander = UUID.randomUUID();
		UUID typist = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership membership = registry.join(roomId, subscriber, participant("subscriber"));
		// 준비 단계의 입장 통보 둘이 다 도착한 뒤에 돌려준다 — 늦게 도착하면 테스트가 멈추려는 프레임과 섞인다.
		CountDownLatch setupJoins = new CountDownLatch(2);
		membership.frames().subscribe(frame -> setupJoins.countDown());
		registry.join(roomId, bystander, participant("bystander"));
		registry.join(roomId, typist, participant("typist"));
		assertThat(setupJoins.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS)).as("준비 단계 입장 통보").isTrue();
		return new Room(roomId, membership.generation(), membership, subscriber, bystander, typist);
	}

	private record Room(UUID id, UUID generation, RoomSessionRegistry.RoomMembership membership, UUID subscriber,
			UUID bystander, UUID typist) {

		/** 조건에 맞는 첫 프레임에서 콜백을 멈추고, 풀리면 reentry를 부른다. */
		Gate holdFramesWhen(Predicate<WsFrame> condition, Runnable reentry) {
			Gate gate = new Gate(reentry);
			membership.frames().subscribe(frame -> {
				if (condition.test(frame) && gate.entered.getCount() > 0) {
					gate.hold();
				}
			});
			return gate;
		}
	}

	/** 구독자 콜백 하나를 멈춰 두는 장치 — entered: 콜백에 들어왔다, release: 풀어 준다, done: 재호출까지 끝났다. */
	private static final class Gate {

		final CountDownLatch entered = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		final CountDownLatch done = new CountDownLatch(1);

		final AtomicReference<Thread> callbackThread = new AtomicReference<>();

		private final Runnable reentry;

		Gate(Runnable reentry) {
			this.reentry = reentry;
		}

		void hold() {
			callbackThread.set(Thread.currentThread());
			entered.countDown();
			try {
				if (!release.await(LIMIT.toMillis() * 2, TimeUnit.MILLISECONDS)) {
					return;
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}
			reentry.run();
			done.countDown();
		}
	}

	private static Thread start(String name, Runnable action) {
		Thread thread = new Thread(action, "room-deadlock-test-" + name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/** 다른 스레드에서 돌려 시간 안에 끝나는지 본다 — 락을 기다리며 멈춰 있으면 그 스택을 실패 메시지에 담는다. */
	private static void within(String what, Runnable action) throws InterruptedException {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread worker = start(what, () -> {
			try {
				action.run();
			} catch (Throwable error) {
				failure.set(error);
			}
		});
		joined(worker);
		if (failure.get() != null) {
			throw new AssertionError(what + " 중 예외", failure.get());
		}
	}

	private static void joined(Thread thread) throws InterruptedException {
		thread.join(LIMIT.toMillis());
		if (thread.isAlive()) {
			fail(thread.getName() + "이(가) " + LIMIT.toSeconds() + "초 안에 끝나지 않았다(" + thread.getState() + "): "
					+ Arrays.toString(thread.getStackTrace()));
		}
	}

	private static void assertNoDeadlock() {
		assertThat(ManagementFactory.getThreadMXBean().findDeadlockedThreads()).as("JVM이 찾은 교착 스레드").isNull();
	}

	private static void awaitBarrier(CyclicBarrier barrier) {
		try {
			barrier.await(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private static ChatMessageFrame chat(UUID roomId, String content) {
		return new ChatMessageFrame(roomId, UUID.randomUUID(), null, null, 0L, "sender", "sender 님", content,
				List.of());
	}

	private static PresenceParticipant participant(String subject) {
		return new PresenceParticipant(subject, subject + " 님");
	}
}
