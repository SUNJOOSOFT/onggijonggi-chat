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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

		awaitFrameCount(room.frames, 1);
		assertThat(room.frames).containsExactly(new ChatMessageFrame(room.threadId, room.participant.subject(), room.participant.displayName(), "일반 발화"));
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
		verify(llm, timeout(1000)).streamChat(any());
		firstResponse.tryEmitValue("first");

		ArgumentCaptor<ChatStreamRequest> requests = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000).times(2)).streamChat(requests.capture());
		assertThat(requests.getAllValues()).extracting(request -> request.messages().get(0).content())
				.containsExactly("one", "two");
		awaitFrameCount(room.frames, 6);
		assertThat(room.frames).containsExactly(
				new ChatMessageFrame(room.threadId, room.participant.subject(), room.participant.displayName(), "@AI one"),
				new ChatMessageFrame(room.threadId, room.participant.subject(), room.participant.displayName(), "@AI two"),
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
		awaitFrameCount(room.frames, 1);
		assertThat(room.frames).containsExactly(new ChatMessageFrame(room.threadId, room.participant.subject(), room.participant.displayName(), "@AI   "));
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
		verify(llm, timeout(1000)).streamChat(any());
		assertThat(dispatcher.dispatch(command(room, "@AI second"), room.membership.generation())).isEmpty();

		// AI 대기열이 찼는지는 워커가 순서대로 처리해봐야 정확하다 — 동기 반환이 아니라 방송으로 온다(#190).
		awaitFrameCount(room.frames, 3);
		assertThat(room.frames).filteredOn(ErrorFrame.class::isInstance)
				.extracting(frame -> ((ErrorFrame) frame).code())
				.containsExactly("RATE_LIMITED");
	}

	@Test
	void emptyOrWhitespaceOnlyOutputBroadcastsModelUnavailableWithoutDone() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("", "   "));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI hello"), room.membership.generation());

		awaitFrameCount(room.frames, 2);
		assertThat(room.frames).hasSize(2);
		assertThat(room.frames.get(0)).isEqualTo(new ChatMessageFrame(room.threadId, room.participant.subject(), room.participant.displayName(), "@AI hello"));
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

		awaitFrameCount(room.frames, 4);
		assertThat(room.frames).containsExactly(
				new ChatMessageFrame(room.threadId, room.participant.subject(), room.participant.displayName(), "@AI hello"),
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
		verify(llm, timeout(1000)).streamChat(any());
		scheduler.advanceTimeBy(Duration.ofSeconds(120));

		awaitTrue(cancelled);
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
		verify(llm, timeout(1000)).streamChat(any());
		firstResponse.tryEmitError(new IllegalStateException("gateway failure"));

		awaitFrameCount(room.frames, 5);
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
		verify(llm, timeout(1000)).streamChat(any());
		oldRoom.registry.leave(oldRoom.threadId, oldRoom.connectionId, oldRoom.participant);
		oldRoom.subscription.dispose();
		UUID newConnectionId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership newMembership =
				oldRoom.registry.join(oldRoom.threadId, newConnectionId, anonymous());
		List<WsFrame> newFrames = new CopyOnWriteArrayList<>();
		Disposable newSubscription = newMembership.frames().subscribe(newFrames::add);
		try {
			source.tryEmitNext("late result");

			awaitTrue(cancelled);
		assertThat(cancelled).isTrue();
			assertThat(newFrames).isEmpty();
		} finally {
			newSubscription.dispose();
			oldRoom.registry.leave(oldRoom.threadId, newConnectionId, anonymous());
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
		verify(llm, timeout(1000)).streamChat(any());
		registry.failBroadcasts = true;

		source.tryEmitNext("answer");

		assertThat(registry.attemptedFrames)
				.filteredOn(ErrorFrame.class::isInstance)
				.extracting(frame -> ((ErrorFrame) frame).code())
				.containsExactly("MESSAGE_DELIVERY_FAILED");
		awaitTrue(cancelled);
		assertThat(cancelled).isTrue();
		verify(llm).streamChat(any());
	}

	@Test
	void closesTheGenerationWhenTheCurrentGenerationSinkKeepsFailing() {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);
		registry.failBroadcasts = true;

		assertThat(dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation())).isEmpty();

		// 방송이 실패한 시점엔 보낸 사람에게 갈 통로(방 sink)가 이미 고장난 뒤라, 동기
		// MESSAGE_DELIVERY_FAILED 대신 generation을 닫는 것으로 끝낸다(이슈 #190).
		awaitFrameCount(registry.attemptedFrames, 1);
		assertThat(registry.attemptedFrames).hasSize(1);
	}

	@Test
	void cancelsTheRunningTurnWhenTheLastConnectionLeaves() throws InterruptedException {
		TestRoom room = new TestRoom();
		CountDownLatch subscribed = new CountDownLatch(1);
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.defer(() -> {
			subscribed.countDown();
			return Flux.<String>never().doOnCancel(() -> cancelled.set(true));
		}));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();
		room.registry.leave(room.threadId, room.connectionId, room.participant)
				.ifPresent(generation -> dispatcher.closeGeneration(room.threadId, generation));

		awaitTrue(cancelled);
		assertThat(cancelled).isTrue();
	}

	@Test
	void cancelsAllRunningTurnsWhenTheServerShutsDown() throws InterruptedException {
		TestRoom room = new TestRoom();
		CountDownLatch subscribed = new CountDownLatch(1);
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.defer(() -> {
			subscribed.countDown();
			return Flux.<String>never().doOnCancel(() -> cancelled.set(true));
		}));
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();
		dispatcher.closeAllGenerations();

		awaitTrue(cancelled);
		assertThat(cancelled).isTrue();
	}

	@Test
	void doesNotStartTheLlmWhenTheRoomClosesDuringContextLookup() throws InterruptedException {
		TestRoom room = new TestRoom();
		CountDownLatch contextStarted = new CountDownLatch(1);
		CountDownLatch releaseContext = new CountDownLatch(1);
		CountDownLatch llmSubscribed = new CountDownLatch(1);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.defer(() -> {
			llmSubscribed.countDown();
			return Flux.never();
		}));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(eq(room.threadId), eq(room.userId),
				eq("@AI first"), anyInt())).thenAnswer(invocation -> {
			contextStarted.countDown();
			releaseContext.await(1, TimeUnit.SECONDS);
			return List.of();
		});
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		try {
			dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
			assertThat(contextStarted.await(1, TimeUnit.SECONDS)).isTrue();
			dispatcher.closeAllGenerations();
		} finally {
			releaseContext.countDown();
		}

		assertThat(llmSubscribed.await(250, TimeUnit.MILLISECONDS)).isFalse();
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

		awaitTrue(cancelled);
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
	void includesStoredHistoryAsContextBeforeTheCurrentMention() {
		TestRoom room = new TestRoom();
		Msg humanHistory = Msg.human(room.threadId, 0, UUID.randomUUID(), "이전 질문");
		Msg agentHistory = Msg.pendingAgent(room.threadId, 1);
		agentHistory.complete("이전 답변");
		Msg pending = Msg.pendingAgent(room.threadId, 2);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답변"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(eq(room.threadId), eq(room.userId),
				eq("@AI 이어서"), anyInt())).thenReturn(List.of(humanHistory, agentHistory));
		when(msgPersistenceService.createPendingAgentMessageBlocking(room.threadId)).thenReturn(pending);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI 이어서"), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().messages()).containsExactly(
				new ChatMessage("user", "이전 질문"),
				new ChatMessage("assistant", "이전 답변"),
				new ChatMessage("user", "이어서"));
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
		// dispatch는 큐에 넣고 바로 돌아온다 — 턴이 실제로 시작된 뒤라야 취소할 대상이 있다(#190).
		verify(llm, timeout(1000)).streamChat(any());
		room.registry.leave(room.threadId, room.connectionId, room.participant)
				.ifPresent(generation -> dispatcher.closeGeneration(room.threadId, generation));

		verify(msgPersistenceService, timeout(1000)).failBlocking(pending.getId(), MsgStatus.CANCELLED);
	}

	@Test
	void completesWithGeneratedContentEvenWhenTheDoneFrameBroadcastFails() throws InterruptedException {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		Msg pending = Msg.pendingAgent(room.threadId, 0);
		CountDownLatch subscribed = new CountDownLatch(1);
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux().doOnSubscribe(ignored -> subscribed.countDown()));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(room.threadId)).thenReturn(pending);
		CollabMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();
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
						Duration.ofSeconds(1), 0, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, msgPersistenceService, "model",
						Duration.ZERO, 0, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, msgPersistenceService, "model",
						Duration.ofSeconds(1), -1, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new CollabMessageDispatcher(registry, llm, msgPersistenceService, "model",
						Duration.ofSeconds(1), 0, -1, scheduler));
	}

	private static CollabMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm) {
		return dispatcher(registry, llm, Duration.ofSeconds(120), 20, VirtualTimeScheduler.create());
	}

	private static CollabMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			Duration timeout, int maxPending, VirtualTimeScheduler scheduler) {
		return new CollabMessageDispatcher(registry, llm, mock(MsgPersistenceService.class), "test-model", timeout,
				maxPending, 20, scheduler);
	}

	private static CollabMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			MsgPersistenceService msgPersistenceService) {
		return new CollabMessageDispatcher(registry, llm, msgPersistenceService, "test-model",
				Duration.ofSeconds(120), 20, 20, VirtualTimeScheduler.create());
	}

	private static ChatMessageCommand command(TestRoom room, String content) {
		return new ChatMessageCommand(room.threadId, room.userId, room.participant.subject(),
				room.participant.displayName(), content, "trace");
	}

	/**
	* 문맥 조회(이슈 #100)가 LLM 호출 앞에 boundedElastic 구간을 하나 더 만들어, 이후 프레임들이
	* dispatch() 호출과 같은 스레드에서 동기적으로 방송되지 않는다 — 프레임 개수가 기대치에 도달할
	* 때까지 짧게 폴링한다.
	*/
	private static void awaitFrameCount(List<WsFrame> frames, int expected) {
		long deadline = System.currentTimeMillis() + 2000;
		while (frames.size() < expected && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	private static void awaitTrue(AtomicBoolean flag) {
		long deadline = System.currentTimeMillis() + 2000;
		while (!flag.get() && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	/** 누구인지가 관심사가 아닌 자리에 세우는 익명 참가자(이슈 #130). */
	private static PresenceParticipant anonymous() {
		UUID id = UUID.randomUUID();
		return new PresenceParticipant("subject-" + id, "다른 사람");
	}

	private static final class TestRoom {

		private final RoomSessionRegistry registry;

		private final UUID threadId = UUID.randomUUID();

		private final UUID connectionId = UUID.randomUUID();

		private final UUID userId = UUID.randomUUID();

		/** 저장은 내부 id(userId)로, 방송 프레임은 subject·표시 이름으로 사람을 가리킨다(이슈 #130). */
		private final PresenceParticipant participant =
				new PresenceParticipant("subject-" + userId, "발화자");

		private final RoomSessionRegistry.RoomMembership membership;

		private final List<WsFrame> frames = new CopyOnWriteArrayList<>();

		private final Disposable subscription;

		TestRoom() {
			this(new RoomSessionRegistry());
		}

		TestRoom(RoomSessionRegistry registry) {
			this.registry = registry;
			this.membership = registry.join(threadId, connectionId, participant);
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

}
