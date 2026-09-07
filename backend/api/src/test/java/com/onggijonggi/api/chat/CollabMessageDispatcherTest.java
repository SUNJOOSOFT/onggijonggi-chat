package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Class Name : CollabMessageDispatcherTest.java
 * Description : 협업방 AI FIFO 실행, timeout, generation 취소 계약을 단위 테스트로 검증한다.
 */
class CollabMessageDispatcherTest {

	@Test
	void broadcastsOrdinaryMessagesWithoutCallingTheLlm() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		assertThat(dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation())).isEmpty();

		assertThat(room.frames).containsExactly(new ChatMessageFrame(room.threadId, room.userId, "일반 발화"));
		verify(llm, times(0)).streamChat(any());
	}

	@Test
	void startsMentionedTurnsInFifoOrderAfterBroadcastingTheOriginalMessages() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux(), Flux.just("second"));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI one"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI two"), room.membership.generation());
		verify(llm).streamChat(any());
		firstResponse.tryEmitValue("first");

		ArgumentCaptor<ChatStreamRequest> requests = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, times(2)).streamChat(requests.capture());
		assertThat(requests.getAllValues()).extracting(request -> request.messages().get(0).content())
				.containsExactly("one", "two");
		assertThat(room.frames).containsExactly(
				new ChatMessageFrame(room.threadId, room.userId, "@AI one"),
				new ChatMessageFrame(room.threadId, room.userId, "@AI two"),
				new ChatAnswerFrame(room.threadId, "first", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE),
				new ChatAnswerFrame(room.threadId, "second", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE));
	}

	@Test
	void rejectsBlankMentionPromptWithoutCallingTheLlm() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		ErrorFrame error = dispatcher.dispatch(command(room, "@AI   "), room.membership.generation()).orElseThrow();

		assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(room.frames).containsExactly(new ChatMessageFrame(room.threadId, room.userId, "@AI   "));
		verify(llm, times(0)).streamChat(any());
	}

	@Test
	void rejectsOnlyTheOverflowingPendingRequest() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, Duration.ofSeconds(120), 0,
				VirtualTimeScheduler.create());

		assertThat(dispatcher.dispatch(command(room, "@AI first"), room.membership.generation())).isEmpty();
		ErrorFrame error = dispatcher.dispatch(command(room, "@AI second"), room.membership.generation()).orElseThrow();

		assertThat(error.code()).isEqualTo("RATE_LIMITED");
		verify(llm).streamChat(any());
	}

	@Test
	void emptyOrWhitespaceOnlyOutputBroadcastsModelUnavailableWithoutDone() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("", "   "));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI hello"), room.membership.generation());

		assertThat(room.frames).hasSize(2);
		assertThat(room.frames.get(0)).isEqualTo(new ChatMessageFrame(room.threadId, room.userId, "@AI hello"));
		assertThat(room.frames.get(1)).isInstanceOf(ErrorFrame.class);
		assertThat(((ErrorFrame) room.frames.get(1)).code()).isEqualTo("MODEL_UNAVAILABLE");
	}

	@Test
	void preservesLeadingWhitespaceOnceTheOutputContainsMeaningfulText() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just(" ", "answer"));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI hello"), room.membership.generation());

		assertThat(room.frames).containsExactly(
				new ChatMessageFrame(room.threadId, room.userId, "@AI hello"),
				new ChatAnswerFrame(room.threadId, " ", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "answer", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE));
	}

	@Test
	void timeoutCancelsTheUpstreamAndStartsTheNextQueuedTurn() {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		TestRoom room = new TestRoom();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)), Flux.just("next"));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, Duration.ofSeconds(120), 20, scheduler);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI second"), room.membership.generation());
		scheduler.advanceTimeBy(Duration.ofSeconds(120));

		assertThat(cancelled).isTrue();
		assertThat(room.frames).anySatisfy(frame -> {
			assertThat(frame).isInstanceOf(ErrorFrame.class);
			assertThat(((ErrorFrame) frame).code()).isEqualTo("MODEL_UNAVAILABLE");
		});
		assertThat(room.frames).contains(
				new ChatAnswerFrame(room.threadId, "next", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE));
		verify(llm, times(2)).streamChat(any());
	}

	@Test
	void failedTurnBroadcastsInternalErrorAndStartsTheNextQueuedTurn() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux(), Flux.just("next"));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI second"), room.membership.generation());
		firstResponse.tryEmitError(new IllegalStateException("gateway failure"));

		assertThat(room.frames).anySatisfy(frame -> {
			assertThat(frame).isInstanceOf(ErrorFrame.class);
			assertThat(((ErrorFrame) frame).code()).isEqualTo("INTERNAL_ERROR");
		});
		assertThat(room.frames).contains(
				new ChatAnswerFrame(room.threadId, "next", List.of(), false, ChatAnswerStatus.STREAMING),
				new ChatAnswerFrame(room.threadId, "", List.of(), false, ChatAnswerStatus.DONE));
	}

	@Test
	void staleGenerationCancelsTheRunningTurnAndNeverEmitsAnErrorFrame() {
		TestRoom oldRoom = new TestRoom();
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
		CollabMessageDispatcher dispatcher = dispatcher(oldRoom.registry, llm);

		dispatcher.dispatch(command(oldRoom, "@AI first"), oldRoom.membership.generation());
		oldRoom.registry.leave(oldRoom.threadId, oldRoom.connectionId, oldRoom.userId);
		oldRoom.subscription.dispose();
		UUID newConnectionId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership newMembership =
				oldRoom.registry.join(oldRoom.threadId, newConnectionId, UUID.randomUUID());
		List<WsFrame> newFrames = new CopyOnWriteArrayList<>();
		Disposable newSubscription = newMembership.frames().subscribe(newFrames::add);
		try {
			source.tryEmitNext("late result");

			assertThat(cancelled).isTrue();
			assertThat(newFrames).isEmpty();
		} finally {
			newSubscription.dispose();
			oldRoom.registry.leave(oldRoom.threadId, newConnectionId, UUID.randomUUID());
		}
	}

	@Test
	void attemptsOneCancellationNotificationWhenSinkFailureDropsPendingTurns() {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI second"), room.membership.generation());
		registry.failBroadcasts = true;

		source.tryEmitNext("answer");

		assertThat(registry.attemptedFrames)
				.filteredOn(ErrorFrame.class::isInstance)
				.extracting(frame -> ((ErrorFrame) frame).code())
				.containsExactly("MESSAGE_DELIVERY_FAILED");
		assertThat(cancelled).isTrue();
		verify(llm).streamChat(any());
	}

	@Test
	void returnsMessageDeliveryFailedWhenTheCurrentGenerationSinkKeepsFailing() {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);
		registry.failBroadcasts = true;

		ErrorFrame error = dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation()).orElseThrow();

		assertThat(error.code()).isEqualTo("MESSAGE_DELIVERY_FAILED");
		assertThat(registry.attemptedFrames).hasSize(1);
	}

	@Test
	void retriesWithACurrentStateWhenTheFetchedStateClosesBeforeTheLockIsAcquired() throws Exception {
		// dispatch()가 states.computeIfAbsent()로 상태를 얻은 직후, 락을 잡기 전에 다른 스레드의
		// sink 고장 처리가 같은 상태를 닫고 map에서 지울 수 있다. 두 statement 사이에는 애플리케이션
		// 코드가 없어 sleep이나 반복으로는 이 경합 창을 결정론적으로 만들 수 없다 — 대신
		// CollabMessageDispatcher.onStateFetchedForTesting() 훅을 딱 그 지점에서 한 번만 걸어,
		// 상태를 얻은 스레드를 CountDownLatch로 세워두고 메인 스레드가 그 사이에 실제로
		// closeGeneration()을 완료시킨 뒤 풀어준다.
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		UUID generation = room.membership.generation();
		CountDownLatch stateFetched = new CountDownLatch(1);
		CountDownLatch closeCompleted = new CountDownLatch(1);
		RacyDispatcher dispatcher = new RacyDispatcher(room.registry, llm, stateFetched, closeCompleted);

		// 상태를 먼저 만들어 둔다 — 이 호출은 훅이 아직 무장되지 않아 그대로 통과한다.
		dispatcher.dispatch(command(room, "warm-up"), generation);
		dispatcher.armRaceOnce();

		ExecutorService executor = Executors.newFixedThreadPool(1);
		try {
			Future<Optional<ErrorFrame>> raced = executor
					.submit(() -> dispatcher.dispatch(command(room, "raced"), generation));

			assertThat(stateFetched.await(5, TimeUnit.SECONDS)).isTrue();
			// 이 시점에서 raced 스레드는 warm-up이 만든 (곧 닫힐) 상태를 이미 손에 쥔 채 훅 안에서
			// 멈춰 있다. 메인 스레드가 바로 그 상태를 닫아 map에서 지운다.
			dispatcher.closeGeneration(room.threadId, generation);
			closeCompleted.countDown();

			assertThat(raced.get(5, TimeUnit.SECONDS)).isEmpty();
		} finally {
			executor.shutdownNow();
		}

		assertThat(room.frames).extracting(frame -> ((ChatMessageFrame) frame).content())
				.containsExactly("warm-up", "raced");
	}

	@Test
	void staleGenerationAfterARaceRetryIsDiscardedWithoutAnErrorFrame() throws Exception {
		// 재획득 뒤에도 stale 처리는 기존 계약을 그대로 따른다는 것을 확인한다: 경합으로 한 번
		// continue한 뒤 방 자체가 이미 다른 세대로 넘어가 있으면, 오류 프레임 없이 조용히 끝나야 한다.
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		UUID staleGeneration = room.membership.generation();
		CountDownLatch stateFetched = new CountDownLatch(1);
		CountDownLatch closeCompleted = new CountDownLatch(1);
		RacyDispatcher dispatcher = new RacyDispatcher(room.registry, llm, stateFetched, closeCompleted);

		dispatcher.dispatch(command(room, "warm-up"), staleGeneration);
		dispatcher.armRaceOnce();

		ExecutorService executor = Executors.newFixedThreadPool(1);
		try {
			Future<Optional<ErrorFrame>> raced = executor
					.submit(() -> dispatcher.dispatch(command(room, "raced"), staleGeneration));

			assertThat(stateFetched.await(5, TimeUnit.SECONDS)).isTrue();
			dispatcher.closeGeneration(room.threadId, staleGeneration);
			// 상태를 닫는 것과 별개로, 방 자체를 새 세대로 넘긴다 — 재시도 시점에는 staleGeneration이
			// 더 이상 현재 세대가 아니다.
			room.registry.leave(room.threadId, room.connectionId, room.userId);
			room.registry.join(room.threadId, UUID.randomUUID(), UUID.randomUUID());
			closeCompleted.countDown();

			assertThat(raced.get(5, TimeUnit.SECONDS)).isEmpty();
		} finally {
			executor.shutdownNow();
		}

		assertThat(room.frames).extracting(frame -> ((ChatMessageFrame) frame).content())
				.containsExactly("warm-up");
	}

	@Test
	void cancelsTheRunningTurnWhenTheLastConnectionLeaves() {
		TestRoom room = new TestRoom();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		room.registry.leave(room.threadId, room.connectionId, room.userId)
				.ifPresent(generation -> dispatcher.closeGeneration(room.threadId, generation));

		assertThat(cancelled).isTrue();
	}

	@Test
	void cancelsAllRunningTurnsWhenTheServerShutsDown() {
		TestRoom room = new TestRoom();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		dispatcher.closeAllGenerations();

		assertThat(cancelled).isTrue();
	}

	@Test
	void disposesALateSubscriptionWhenTheGenerationClosedDuringSubscription() {
		TestRoom room = new TestRoom();
		AtomicReference<CollabMessageDispatcher> dispatcherRef = new AtomicReference<>();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.defer(() -> {
			dispatcherRef.get().closeGeneration(room.threadId, room.membership.generation());
			return Flux.<String>never().doOnCancel(() -> cancelled.set(true));
		}));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);
		dispatcherRef.set(dispatcher);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());

		assertThat(cancelled).isTrue();
	}

	@Test
	void persistsHumanMessageAfterBroadcast() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation());

		verify(msgPersistenceService, timeout(1000)).persistHumanMessageBlocking(room.threadId, room.userId,
				"일반 발화");
	}

	@Test
	void doesNotPersistHumanMessageWhenDeliveryFails() {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		registry.failBroadcasts = true;
		TestRoom room = new TestRoom(registry);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation());

		verify(msgPersistenceService, never()).persistHumanMessageBlocking(any(), any(), any());
	}

	@Test
	void createsPendingAgentMessageThenCompletesItWithFullContentOnSuccess() {
		TestRoom room = new TestRoom();
		Msg pending = Msg.pendingAgent(room.threadId, 0);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("hello", " world"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(room.threadId)).thenReturn(pending);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI hi"), room.membership.generation());

		verify(msgPersistenceService, timeout(1000)).completeBlocking(pending.getId(), "hello world");
	}

	@Test
	void marksAgentMessageFailedWhenTurnErrors() {
		TestRoom room = new TestRoom();
		Msg pending = Msg.pendingAgent(room.threadId, 0);
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(room.threadId)).thenReturn(pending);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		firstResponse.tryEmitError(new IllegalStateException("gateway failure"));

		verify(msgPersistenceService, timeout(1000)).failBlocking(pending.getId(), MsgStatus.FAILED);
	}

	@Test
	void marksAgentMessageCancelledWhenRoomClosesDuringActiveTurn() {
		TestRoom room = new TestRoom();
		Msg pending = Msg.pendingAgent(room.threadId, 0);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(room.threadId)).thenReturn(pending);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		room.registry.leave(room.threadId, room.connectionId, room.userId)
				.ifPresent(generation -> dispatcher.closeGeneration(room.threadId, generation));

		verify(msgPersistenceService, timeout(1000)).failBlocking(pending.getId(), MsgStatus.CANCELLED);
	}

	@Test
	void completesWithGeneratedContentEvenWhenTheDoneFrameBroadcastFails() {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		Msg pending = Msg.pendingAgent(room.threadId, 0);
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(room.threadId)).thenReturn(pending);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		source.tryEmitNext("answer");
		registry.failBroadcasts = true;
		source.tryEmitComplete();

		verify(msgPersistenceService, timeout(1000)).completeBlocking(pending.getId(), "answer");
		verify(msgPersistenceService, never()).failBlocking(pending.getId(), MsgStatus.CANCELLED);
	}

	@Test
	void rejectsInvalidAiSettingsAtStartup() {
		RoomSessionRegistry registry = new RoomSessionRegistry();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();

		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, msgPersistenceService, " ",
						Duration.ofSeconds(1), 0, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, msgPersistenceService, "model",
						Duration.ZERO, 0, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, msgPersistenceService, "model",
						Duration.ofSeconds(1), -1, scheduler));
	}

	private static CollabMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm) {
		return dispatcher(registry, llm, Duration.ofSeconds(120), 20, VirtualTimeScheduler.create());
	}

	private static CollabMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			Duration timeout, int maxPending, VirtualTimeScheduler scheduler) {
		return new CollabMessageDispatcher(registry, llm, mock(MsgPersistenceService.class), "test-model", timeout,
				maxPending, scheduler);
	}

	private static CollabMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			MsgPersistenceService msgPersistenceService) {
		return new CollabMessageDispatcher(registry, llm, msgPersistenceService, "test-model",
				Duration.ofSeconds(120), 20, VirtualTimeScheduler.create());
	}

	private static ChatMessageCommand command(TestRoom room, String content) {
		return new ChatMessageCommand(room.threadId, room.userId, content, "trace");
	}

	private static final class TestRoom {

		private final RoomSessionRegistry registry;

		private final UUID threadId = UUID.randomUUID();

		private final UUID connectionId = UUID.randomUUID();

		private final UUID userId = UUID.randomUUID();

		private final RoomSessionRegistry.RoomMembership membership;

		private final List<WsFrame> frames = new CopyOnWriteArrayList<>();

		private final Disposable subscription;

		TestRoom() {
			this(new RoomSessionRegistry());
		}

		TestRoom(RoomSessionRegistry registry) {
			this.registry = registry;
			this.membership = registry.join(threadId, connectionId, userId);
			this.subscription = membership.frames().subscribe(frames::add);
		}
	}

	private static final class FailingRoomSessionRegistry extends RoomSessionRegistry {

		private final List<WsFrame> attemptedFrames = new CopyOnWriteArrayList<>();

		private boolean failBroadcasts;

		@Override
		public boolean broadcastIfCurrent(UUID threadId, UUID roomGeneration, WsFrame frame) {
			attemptedFrames.add(frame);
			if (failBroadcasts) {
				throw new IllegalStateException("sink failure");
			}
			return super.broadcastIfCurrent(threadId, roomGeneration, frame);
		}
	}

	/**
	 * {@link CollabMessageDispatcher#onStateFetchedForTesting()}를 딱 한 번만 걸어, 상태를 얻은 직후
	 * 락을 잡기 전 구간에서 호출한 스레드를 세워둔다. 무장 이후 첫 dispatch() 호출에서만 발동하고
	 * 그 뒤로는 평소처럼 동작한다.
	 */
	private static final class RacyDispatcher extends CollabMessageDispatcher {

		private final CountDownLatch stateFetched;

		private final CountDownLatch proceed;

		private volatile boolean armed;

		RacyDispatcher(RoomSessionRegistry registry, LlmChatStreamService llm, CountDownLatch stateFetched,
				CountDownLatch proceed) {
			super(registry, llm, mock(MsgPersistenceService.class), "test-model", Duration.ofSeconds(120), 20,
					VirtualTimeScheduler.create());
			this.stateFetched = stateFetched;
			this.proceed = proceed;
		}

		void armRaceOnce() {
			armed = true;
		}

		@Override
		void onStateFetchedForTesting() {
			if (!armed) {
				return;
			}
			armed = false;
			stateFetched.countDown();
			try {
				proceed.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

}
