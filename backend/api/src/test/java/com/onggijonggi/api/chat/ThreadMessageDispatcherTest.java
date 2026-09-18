package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.ThrKind;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Class Name : ThreadMessageDispatcherTest.java
 * Description : 협업방 AI FIFO 실행, timeout, generation 취소 계약을 단위 테스트로 검증한다.
 */
class ThreadMessageDispatcherTest {

	/** 워커가 직렬로 꺼내므로 방송 순서와 seq 순서가 같다 — #190이 지키려는 계약의 본체다. */
	@Test
	void assignsSeqInBroadcastOrder() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "하나"), room.membership.generation());
		dispatcher.dispatch(command(room, "둘"), room.membership.generation());
		dispatcher.dispatch(command(room, "셋"), room.membership.generation());

		awaitFrameCount(room.frames, 3);
		assertThat(room.frames).extracting(frame -> ((ChatMessageFrame) frame).content(),
						frame -> ((ChatMessageFrame) frame).seq())
				.containsExactly(tuple("하나", 0L), tuple("둘", 1L), tuple("셋", 2L));
	}

	/** 한 턴의 delta·DONE은 모두 같은 msgId를 달고 오고(D5 턴 식별자), 그 값은 저장된 PENDING
	 * 행의 id와 같다 — 이력과 실시간이 같은 메시지를 가리키게 하는 근거다. */
	@Test
	void agentFramesCarryTheSameMsgIdAsTheStoredPendingRow() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답", "변"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId)))
				.thenAnswer(invocation -> Msg.pendingAgent(invocation.getArgument(0), room.threadId,
						invocation.getArgument(1)));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI 질문"), room.membership.generation());

		awaitFrameCount(room.frames, 4);
		ArgumentCaptor<UUID> storedId = ArgumentCaptor.forClass(UUID.class);
		verify(msgPersistenceService, timeout(1000)).createPendingAgentMessageBlocking(storedId.capture(),
				anyLong(), eq(room.threadId));
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).msgId(),
						frame -> ((ChatAnswerFrame) frame).seq())
				.containsOnly(tuple(storedId.getValue(), 1L));
		// 사람 메시지가 0, 답변이 1 — 답변의 자리는 턴이 시작되는 순간 정해진다.
		assertThat(((ChatMessageFrame) room.frames.get(0)).seq()).isEqualTo(0L);
	}

	@Test
	void broadcastsOrdinaryMessagesWithoutCallingTheLlm() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		assertThat(dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation())).isEmpty();

		awaitFrameCount(room.frames, 1);
		assertThat(room.frames).usingRecursiveFieldByFieldElementComparatorIgnoringFields("msgId", "seq").containsExactly(message(room, "일반 발화"));
		verify(llm, times(0)).streamChat(any());
	}

	@Test
	void startsMentionedTurnsInFifoOrderAfterBroadcastingTheOriginalMessages() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux(), Flux.just("second"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI one"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI two"), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		firstResponse.tryEmitValue("first");

		ArgumentCaptor<ChatStreamRequest> requests = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000).times(2)).streamChat(requests.capture());
		assertThat(requests.getAllValues()).extracting(request -> request.messages().get(0).content())
				.containsExactly("one", "two");
		awaitFrameCount(room.frames, 7);
		assertThat(room.frames).usingRecursiveFieldByFieldElementComparatorIgnoringFields("msgId", "seq")
				.containsExactly(
				message(room, "@AI one"),
				message(room, "@AI two"),
				// 앞 턴이 실행 중이라 두 번째 턴은 기다린다고 알린다(이슈 #160).
				new ChatQueuedFrame(room.threadId, null, ChatQueuedStatus.QUEUED),
				answer(room, "first", ChatAnswerStatus.STREAMING),
				answer(room, "", ChatAnswerStatus.DONE),
				answer(room, "second", ChatAnswerStatus.STREAMING),
				answer(room, "", ChatAnswerStatus.DONE));
	}

	@Test
	void rejectsBlankMentionPromptWithoutCallingTheLlm() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		ErrorFrame error = dispatcher.dispatch(command(room, "@AI   "), room.membership.generation()).orElseThrow();

		assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
		awaitFrameCount(room.frames, 1);
		assertThat(room.frames).usingRecursiveFieldByFieldElementComparatorIgnoringFields("msgId", "seq").containsExactly(message(room, "@AI   "));
		verify(llm, times(0)).streamChat(any());
	}

	@Test
	void rejectsOnlyTheOverflowingPendingRequest() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, Duration.ofSeconds(120), 0,
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
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI hello"), room.membership.generation());

		awaitFrameCount(room.frames, 2);
		assertThat(room.frames).hasSize(2);
		assertThat(room.frames.get(0)).usingRecursiveComparison().ignoringFields("msgId", "seq")
				.isEqualTo(message(room, "@AI hello"));
		assertThat(room.frames.get(1)).isInstanceOf(ErrorFrame.class);
		assertThat(((ErrorFrame) room.frames.get(1)).code()).isEqualTo("MODEL_UNAVAILABLE");
	}

	@Test
	void preservesLeadingWhitespaceOnceTheOutputContainsMeaningfulText() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just(" ", "answer"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI hello"), room.membership.generation());

		awaitFrameCount(room.frames, 4);
		assertThat(room.frames).usingRecursiveFieldByFieldElementComparatorIgnoringFields("msgId", "seq").containsExactly(
				message(room, "@AI hello"),
				answer(room, " ", ChatAnswerStatus.STREAMING),
				answer(room, "answer", ChatAnswerStatus.STREAMING),
				answer(room, "", ChatAnswerStatus.DONE));
	}

	@Test
	void timeoutCancelsTheUpstreamAndStartsTheNextQueuedTurn() {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		TestRoom room = new TestRoom();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> cancelled.set(true)), Flux.just("next"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, Duration.ofSeconds(120), 20, scheduler);

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
		assertThat(room.frames).usingRecursiveFieldByFieldElementComparatorIgnoringFields("msgId", "seq").contains(
				answer(room, "next", ChatAnswerStatus.STREAMING),
				answer(room, "", ChatAnswerStatus.DONE));
		verify(llm, times(2)).streamChat(any());
	}

	@Test
	void failedTurnBroadcastsInternalErrorAndStartsTheNextQueuedTurn() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux(), Flux.just("next"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI second"), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		firstResponse.tryEmitError(new IllegalStateException("gateway failure"));

		// 사람 메시지 2, 대기 알림 1, 오류 1, 다음 턴의 답변·done 2
		awaitFrameCount(room.frames, 6);
		assertThat(room.frames).anySatisfy(frame -> {
			assertThat(frame).isInstanceOf(ErrorFrame.class);
			assertThat(((ErrorFrame) frame).code()).isEqualTo("INTERNAL_ERROR");
		});
		assertThat(room.frames).usingRecursiveFieldByFieldElementComparatorIgnoringFields("msgId", "seq").contains(
				answer(room, "next", ChatAnswerStatus.STREAMING),
				answer(room, "", ChatAnswerStatus.DONE));
	}

	@Test
	void staleGenerationCancelsTheRunningTurnAndNeverEmitsAnErrorFrame() {
		TestRoom oldRoom = new TestRoom();
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
		ThreadMessageDispatcher dispatcher = dispatcher(oldRoom.registry, llm);

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
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

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

	/**
	* 아직 워커가 꺼내지 않은 @AI 발화도 취소 알림에 포함한다(이슈 #206).
	*
	* 위 attemptsOneCancellation... 테스트는 두 번째 발화가 이미 state.pending에 들어간 흔한
	* 경우다. 여기서는 워커를 에코 방송에 붙잡아 두어, 두 번째 발화가 pending에는 없고
	* inFlight에만 있는 창을 결정적으로 만든다 — 이슈 작성자가 200회 중 1회 관측한 그 상태다.
	*/
	@Test
	void notifiesCancellationForAnAiTurnTheWorkerHasNotQueuedYet() throws InterruptedException {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		// 첫 턴은 평소대로 시작시킨다 — 이게 뒤에 방송 실패로 방을 닫는 쪽이다.
		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());

		// 두 번째 발화를 워커가 에코 방송하는 자리에서 붙잡는다.
		registry.blockOnContent = "@AI second";
		try {
			dispatcher.dispatch(command(room, "@AI second"), room.membership.generation());
			assertThat(registry.workerBlocked.await(1, TimeUnit.SECONDS)).isTrue();

			// 이 시점의 두 번째 발화는 pending에 없다. 여기서 첫 턴의 방송이 실패해 방이 닫힌다.
			registry.failBroadcasts = true;
			source.tryEmitNext("answer");

			assertThat(registry.attemptedFrames)
					.filteredOn(ErrorFrame.class::isInstance)
					.extracting(frame -> ((ErrorFrame) frame).code())
					.containsExactly("MESSAGE_DELIVERY_FAILED");
		} finally {
			registry.releaseWorker.countDown();
		}
	}

	@Test
	void closesTheGenerationWhenTheCurrentGenerationSinkKeepsFailing() {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);
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
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

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
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

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
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(any(), anyLong(), eq(room.threadId), eq(room.userId),
				eq("@AI first"), anyInt())).thenAnswer(invocation -> {
			contextStarted.countDown();
			releaseContext.await(1, TimeUnit.SECONDS);
			return List.of();
		});
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		try {
			dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
			assertThat(contextStarted.await(1, TimeUnit.SECONDS)).isTrue();
			dispatcher.closeAllGenerations();
		} finally {
			releaseContext.countDown();
		}

		assertThat(llmSubscribed.await(250, TimeUnit.MILLISECONDS)).isFalse();
	}

	/**
	* 취소도 닫힘과 같은 구멍이 있다. cancel()은 state.active를 비우지 않으므로(다음 대기 턴을
	* 막지 않기 위해서다) "방이 닫혔나 / 이 턴이 아직 활성인가"로는 걸러지지 않는다 — 두 경로가
	* 공통으로 내리는 신호는 activeTurn.subscription.dispose() 하나뿐이다.
	*
	* 취소 쪽 피해가 더 크다: 방이 살아 있어 뒤늦게 시작된 턴의 오류 프레임이 실제로 방송된다.
	*/
	@Test
	void doesNotStartTheLlmWhenTheTurnIsCancelledDuringContextLookup() throws InterruptedException {
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
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(any(), anyLong(), eq(room.threadId),
				eq(room.userId), eq("@AI first"), anyInt())).thenAnswer(invocation -> {
			contextStarted.countDown();
			releaseContext.await(1, TimeUnit.SECONDS);
			return List.of();
		});
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);
		UUID turnId = UUID.randomUUID();

		try {
			dispatcher.dispatch(command(room, "@AI first", turnId), room.membership.generation());
			assertThat(contextStarted.await(1, TimeUnit.SECONDS)).isTrue();
			dispatcher.cancel(room.threadId, room.membership.generation(), turnId, room.connectionId);
		} finally {
			releaseContext.countDown();
		}

		assertThat(llmSubscribed.await(250, TimeUnit.MILLISECONDS)).isFalse();
	}

	@Test
	void disposesALateSubscriptionWhenTheGenerationClosedDuringSubscription() {
		TestRoom room = new TestRoom();
		AtomicReference<ThreadMessageDispatcher> dispatcherRef = new AtomicReference<>();
		AtomicBoolean cancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.defer(() -> {
			dispatcherRef.get().closeGeneration(room.threadId, room.membership.generation());
			return Flux.<String>never().doOnCancel(() -> cancelled.set(true));
		}));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);
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
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation());

		verify(msgPersistenceService, timeout(1000)).persistHumanMessageBlocking(any(), anyLong(),
				eq(room.threadId), eq(room.userId), eq("일반 발화"));
	}

	@Test
	void doesNotPersistHumanMessageWhenDeliveryFails() {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		registry.failBroadcasts = true;
		TestRoom room = new TestRoom(registry);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "일반 발화"), room.membership.generation());

		verify(msgPersistenceService, never()).persistHumanMessageBlocking(any(), anyLong(), any(), any(), any());
	}

	@Test
	void createsPendingAgentMessageThenCompletesItWithFullContentOnSuccess() {
		TestRoom room = new TestRoom();
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 0);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("hello", " world"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId))).thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI hi"), room.membership.generation());

		verify(msgPersistenceService, timeout(1000)).completeBlocking(pending.getId(), "hello world");
	}

	@Test
	void includesStoredHistoryAsContextBeforeTheCurrentMention() {
		TestRoom room = new TestRoom();
		Msg humanHistory = Msg.human(UUID.randomUUID(), room.threadId, 0, UUID.randomUUID(), "이전 질문");
		Msg agentHistory = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 1);
		agentHistory.complete("이전 답변");
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 2);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답변"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(any(), anyLong(), eq(room.threadId), eq(room.userId),
				eq("@AI 이어서"), anyInt())).thenReturn(List.of(humanHistory, agentHistory));
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId))).thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

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
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 0);
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId))).thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		firstResponse.tryEmitError(new IllegalStateException("gateway failure"));

		verify(msgPersistenceService, timeout(1000)).failBlocking(pending.getId(), MsgStatus.FAILED);
	}

	@Test
	void marksAgentMessageCancelledWhenRoomClosesDuringActiveTurn() {
		TestRoom room = new TestRoom();
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 0);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId))).thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		// dispatch는 큐에 넣고 바로 돌아온다 — 턴이 실제로 시작된 뒤라야 취소할 대상이 있다(#190).
		verify(llm, timeout(1000)).streamChat(any());
		room.registry.leave(room.threadId, room.connectionId, room.participant)
				.ifPresent(generation -> dispatcher.closeGeneration(room.threadId, generation));

		verify(msgPersistenceService, timeout(1000)).cancelBlocking(pending.getId(), "");
	}

	@Test
	void completesWithGeneratedContentEvenWhenTheDoneFrameBroadcastFails() throws InterruptedException {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 0);
		CountDownLatch subscribed = new CountDownLatch(1);
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux().doOnSubscribe(ignored -> subscribed.countDown()));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId))).thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());
		assertThat(subscribed.await(1, TimeUnit.SECONDS)).isTrue();
		source.tryEmitNext("answer");
		registry.failBroadcasts = true;
		source.tryEmitComplete();

		// 여기만 다른 테스트보다 넓게 기다린다(이슈 #207). 완료 저장은 방송 실패로 예외가 한 번 더
		// 오가는 경로라 스케줄링 편차가 누적되는데, 같은 JVM에서 클래스 전체를 연달아 돌리는 CI에서는
		// 1초 창이 빠듯해 10회 중 1~2회 간헐 실패했다. 프로덕션 순서·로직 결함이 아니라는 것은
		// 이슈에서 반복 측정으로 확인됐다(#207, Refs #197).
		verify(msgPersistenceService, timeout(5000)).completeBlocking(pending.getId(), "answer");
		verify(msgPersistenceService, never()).cancelBlocking(eq(pending.getId()), any());
	}

	/** 클라이언트가 실은 clientMsgId·turnId가 에코에, turnId와 쓴 모델이 그 발화가 부른 답변에 돌아온다(이슈 #160). */
	@Test
	void echoesTheClientIdsOnTheMessageAndTheTurnIdAndModelOnItsAnswer() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);
		// UUID가 아닌 nanoid 형태로 둔다 — 서버가 이 값을 UUID로 좁혀 파싱하지 않는다는 계약을
		// 검증한다(이슈 #224, useChat이 실제로 이런 형태의 id를 만든다).
		String clientMsgId = "Fup9Wytbi2B7C9A0";
		UUID turnId = UUID.randomUUID();

		dispatcher.dispatch(command(room, "@AI 질문", clientMsgId, turnId, null), room.membership.generation());

		awaitFrameCount(room.frames, 3);
		ChatMessageFrame echo = (ChatMessageFrame) room.frames.get(0);
		assertThat(echo.clientMsgId()).isEqualTo(clientMsgId);
		assertThat(echo.turnId()).isEqualTo(turnId);
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).turnId(), frame -> ((ChatAnswerFrame) frame).model())
				.containsOnly(tuple(turnId, "test-model"));
	}

	/** 실행 중인 턴을 멈추면 그때까지의 내용이 CANCELLED로 남고, done 뒤에 다음 턴이 시작된다. */
	@Test
	void cancelStopsTheActiveTurnKeepsItsPartialContentAndStartsTheNextTurn() {
		TestRoom room = new TestRoom();
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 0);
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		AtomicBoolean upstreamCancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux().doOnCancel(() -> upstreamCancelled.set(true)),
				Flux.just("next"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId)))
				.thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);
		UUID first = UUID.randomUUID();

		dispatcher.dispatch(command(room, "@AI first", first), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI second", UUID.randomUUID()), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		source.tryEmitNext("부분");
		awaitFrameCount(room.frames, 4);
		dispatcher.cancel(room.threadId, room.membership.generation(), first, room.connectionId);

		awaitTrue(upstreamCancelled);
		assertThat(upstreamCancelled).isTrue();
		verify(msgPersistenceService, timeout(1000)).cancelBlocking(pending.getId(), "부분");
		verify(llm, timeout(1000).times(2)).streamChat(any());
		awaitFrameCount(room.frames, 7);
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).delta(), frame -> ((ChatAnswerFrame) frame).status())
				.containsExactly(tuple("부분", ChatAnswerStatus.STREAMING), tuple("", ChatAnswerStatus.DONE),
						tuple("next", ChatAnswerStatus.STREAMING), tuple("", ChatAnswerStatus.DONE));
		// 취소는 done으로 끝난다 — 오류 프레임이 따라붙으면 화면은 "취소했는데 잠시 뒤 오류"가 된다.
		// startTurn()이 버려진 턴에 Flux.empty()를 돌려주면 EmptyLlmOutputException을 거쳤
		// MODEL_UNAVAILABLE이 여기 따라붙는다(이슈 #205).
		assertThat(room.frames).noneMatch(ErrorFrame.class::isInstance);
	}

	/** turnId는 클라이언트가 만든 값이라 그 발화가 들어온 커넥션에서만 인정한다 — 다른 커넥션이 같은 값을 보내도 턴은 계속된다. */
	@Test
	void ignoresACancelFromAnotherConnection() {
		TestRoom room = new TestRoom();
		AtomicBoolean upstreamCancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true)));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);
		UUID turnId = UUID.randomUUID();

		dispatcher.dispatch(command(room, "@AI first", turnId), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		// 같은 사용자라도 다른 탭(다른 커넥션)이다.
		dispatcher.cancel(room.threadId, room.membership.generation(), turnId, UUID.randomUUID());

		assertThat(upstreamCancelled).isFalse();
		assertThat(room.frames).noneMatch(ChatAnswerFrame.class::isInstance);
	}

	/** 앞 턴이 있으면 뒤 턴은 기다린다는 것을 방에 알리고, 기다리는 중에 취소하면 큐에서 빠진다. */
	@Test
	void announcesQueuedTurnsAndDropsOneCancelledWhileWaiting() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux(), Flux.just("never"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);
		UUID second = UUID.randomUUID();

		dispatcher.dispatch(command(room, "@AI first", UUID.randomUUID()), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI second", second), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		awaitFrameCount(room.frames, 3);
		assertThat(room.frames.get(2)).isEqualTo(new ChatQueuedFrame(room.threadId, second, ChatQueuedStatus.QUEUED));

		dispatcher.cancel(room.threadId, room.membership.generation(), second, room.connectionId);
		firstResponse.tryEmitValue("first");

		awaitFrameCount(room.frames, 6);
		assertThat(room.frames.get(3)).isEqualTo(new ChatQueuedFrame(room.threadId, second, ChatQueuedStatus.CANCELLED));
		verify(llm, times(1)).streamChat(any());
	}

	/** 워커가 발화를 꺼내기 전에 온 취소도 받는다 — 그래서 에코를 기다리지 않고 중단할 수 있다. */
	@Test
	void cancelArrivingBeforeTheWorkerProcessesTheMessagePreventsTheTurn() throws InterruptedException {
		TestRoom room = new TestRoom();
		CountDownLatch workerBlocked = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		// 워커가 처음 seq 블록을 예약하는 자리에서 붙잡아 둔다 — 그 사이에 취소를 넣는다.
		when(msgPersistenceService.allocateSeqBlockBlocking(eq(room.threadId), anyInt())).thenAnswer(invocation -> {
			workerBlocked.countDown();
			releaseWorker.await(1, TimeUnit.SECONDS);
			return 0L;
		});
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);
		UUID turnId = UUID.randomUUID();

		try {
			dispatcher.dispatch(command(room, "@AI stop me", turnId), room.membership.generation());
			assertThat(workerBlocked.await(1, TimeUnit.SECONDS)).isTrue();
			dispatcher.cancel(room.threadId, room.membership.generation(), turnId, room.connectionId);
		} finally {
			releaseWorker.countDown();
		}

		awaitFrameCount(room.frames, 2);
		assertThat(room.frames.get(1)).isEqualTo(new ChatQueuedFrame(room.threadId, turnId, ChatQueuedStatus.CANCELLED));
		// 발화 자체는 받은 것이라 저장한다 — 턴만 만들지 않는다.
		verify(msgPersistenceService, timeout(1000)).persistHumanMessageBlocking(any(), anyLong(), eq(room.threadId),
				eq(room.userId), eq("@AI stop me"));
		verify(llm, never()).streamChat(any());
	}

	/** DIRECT는 멘션 파서를 안 타고 모든 발화에 답하며, HUMAN·AGENT 모두 미리 예약된 msgId·seq를
	 * 그대로 쓴다(이슈 #162) — process()가 새로 만들지 않는다. */
	@Test
	void directDispatchAnswersEveryMessageWithoutAiMentionUsingReservedIds() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);
		ChatMessageCommand.ReservedTurn reserved = reservedTurn();

		dispatcher.dispatch(directCommand(room, "멘션 없는 발화", null, reserved), room.membership.generation());

		awaitFrameCount(room.frames, 3);
		verify(llm, timeout(1000)).streamChat(any());
		assertThat(room.frames).filteredOn(ChatMessageFrame.class::isInstance)
				.extracting(frame -> ((ChatMessageFrame) frame).msgId(), frame -> ((ChatMessageFrame) frame).seq())
				.containsExactly(tuple(reserved.humanMsgId(), reserved.humanSeq()));
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).msgId(), frame -> ((ChatAnswerFrame) frame).seq())
				.containsOnly(tuple(reserved.agentMsgId(), reserved.agentSeq()));
		// HUMAN을 다시 저장하지 않는다 — DirectChatTurnService가 이미 저장했다.
		verify(msgPersistenceService, never()).persistHumanMessageBlocking(any(), anyLong(), any(), any(), any());
		verify(msgPersistenceService, never()).persistHumanMessageAndFetchContextBlocking(any(), anyLong(), any(),
				any(), any(), anyInt());
		// PENDING AGENT도 새로 만들지 않는다 — 이미 예약된 msgId를 그대로 쓴다.
		verify(msgPersistenceService, never()).createPendingAgentMessageBlocking(any(), anyLong(), any());
	}

	/** DIRECT는 delta 앞에 아무것도 덧붙이지 않는다. RAG가 없어(로드맵 v0.4) 근거가 실릴 일이
	 * 없으므로 citations 전용 chat.answer(delta="", STREAMING)를 보내지 않는다 — 그 프레임은 프런트
	 * loading을 끄기 위한 것이었고, 상태를 파생하는 지금 프런트는 loading을 켜지 않는다(PR #231·#232). */
	@Test
	void directDispatchDoesNotBroadcastAnEmptyCitationsFrameBeforeTheFirstDelta() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);
		ChatMessageCommand.ReservedTurn reserved = reservedTurn();

		dispatcher.dispatch(directCommand(room, "질문", null, reserved), room.membership.generation());

		awaitFrameCount(room.frames, 3);
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).delta(), frame -> ((ChatAnswerFrame) frame).status())
				.containsExactly(tuple("답", ChatAnswerStatus.STREAMING), tuple("", ChatAnswerStatus.DONE));
	}

	/** AI FIFO 초과는 DIRECT만 예약된 PENDING AGENT를 즉시 DENIED로 닫고 chat.answer(denied)를
	 * 방 전체에 방송한다 — COLLAB은 AGENT 행 자체를 안 만든다(대비는 기존 rejectsOnlyThe... 테스트). */
	@Test
	void directFifoOverflowDeniesTheReservedAgentAndBroadcastsRoomWide() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService, 0);
		ChatMessageCommand.ReservedTurn first = reservedTurn();
		ChatMessageCommand.ReservedTurn second = reservedTurn();

		dispatcher.dispatch(directCommand(room, "first", null, first), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		dispatcher.dispatch(directCommand(room, "second", null, second), room.membership.generation());

		verify(msgPersistenceService, timeout(1000)).failBlocking(second.agentMsgId(), MsgStatus.DENIED);
		awaitFrameCount(room.frames, 5);
		assertThat(room.frames).filteredOn(ErrorFrame.class::isInstance)
				.extracting(frame -> ((ErrorFrame) frame).code())
				.contains("RATE_LIMITED");
		assertThat(room.frames).filteredOn(frame -> frame instanceof ChatAnswerFrame answerFrame
						&& answerFrame.msgId().equals(second.agentMsgId()))
				.extracting(frame -> ((ChatAnswerFrame) frame).status())
				.containsExactly(ChatAnswerStatus.DENIED);
	}

	/** DIRECT는 turnId만으로 취소를 인정한다 — 다른 탭(다른 커넥션)의 재연결·OWNER도 멈출 수
	 * 있어야 하기 때문이다(이슈 #162). COLLAB의 대비는 ignoresACancelFromAnotherConnection. */
	@Test
	void directCancelFromAnotherConnectionStillCancelsTheActiveTurn() {
		TestRoom room = new TestRoom();
		AtomicBoolean upstreamCancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true)));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);
		UUID turnId = UUID.randomUUID();
		ChatMessageCommand.ReservedTurn reserved = reservedTurn();

		dispatcher.dispatch(directCommand(room, "first", turnId, reserved), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		// 같은 사용자의 다른 탭(다른 커넥션)이어도 DIRECT는 취소를 인정한다.
		dispatcher.cancel(room.threadId, room.membership.generation(), turnId, UUID.randomUUID());

		awaitTrue(upstreamCancelled);
		assertThat(upstreamCancelled).isTrue();
		verify(msgPersistenceService, timeout(1000)).cancelBlocking(eq(reserved.agentMsgId()), any());
		assertThat(room.frames).filteredOn(frame -> frame instanceof ChatAnswerFrame answerFrame
						&& answerFrame.msgId().equals(reserved.agentMsgId()))
				.extracting(frame -> ((ChatAnswerFrame) frame).status())
				.contains(ChatAnswerStatus.CANCELLED);
	}

	/** 대기 중(pending)인 DIRECT 턴을 취소하면 COLLAB처럼 chat.queued(cancelled)가 아니라
	 * 예약된 AGENT의 chat.answer(cancelled)로 알린다(이슈 #162, §3.2). */
	@Test
	void directCancelOfAQueuedTurnUsesChatAnswerNotChatQueued() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);
		UUID secondTurnId = UUID.randomUUID();
		ChatMessageCommand.ReservedTurn first = reservedTurn();
		ChatMessageCommand.ReservedTurn second = reservedTurn();

		dispatcher.dispatch(directCommand(room, "first", UUID.randomUUID(), first), room.membership.generation());
		dispatcher.dispatch(directCommand(room, "second", secondTurnId, second), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());

		dispatcher.cancel(room.threadId, room.membership.generation(), secondTurnId, room.connectionId);

		verify(msgPersistenceService, timeout(1000)).cancelBlocking(second.agentMsgId(), "");
		assertThat(room.frames).filteredOn(frame -> frame instanceof ChatAnswerFrame answerFrame
						&& answerFrame.msgId().equals(second.agentMsgId()))
				.extracting(frame -> ((ChatAnswerFrame) frame).status())
				.containsExactly(ChatAnswerStatus.CANCELLED);
		assertThat(room.frames).filteredOn(ChatQueuedFrame.class::isInstance)
				.noneMatch(frame -> ((ChatQueuedFrame) frame).status() == ChatQueuedStatus.CANCELLED);
	}

	/**
	* 재검토로 확인한 버그(이슈 #233) — activeTurnContentIfMatches가 state.active만 보면, 아직
	* 시작 전이라 FIFO 대기 중인(state.pending) 정상 턴을 "고아"로 오판한다. DIRECT는 같은
	* OWNER가 첫 턴이 스트리밍 중인 동안 다음 발화를 보낼 수 있고 그 발화도 정상적으로
	* 큐잉된다 — 이 상태에서 재시도가 오면 그 턴을 FAILED로 잘못 닫고 중복 턴을 새로
	* 시작하게 된다.
	*/
	@Test
	void activeTurnContentIfMatchesRecognizesAQueuedDirectTurnAsStillAlive() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);
		ChatMessageCommand.ReservedTurn first = reservedTurn();
		ChatMessageCommand.ReservedTurn queued = reservedTurn();

		dispatcher.dispatch(directCommand(room, "first", UUID.randomUUID(), first), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		dispatcher.dispatch(directCommand(room, "second", UUID.randomUUID(), queued), room.membership.generation());

		assertThat(dispatcher.activeTurnContentIfMatches(room.threadId, room.membership.generation(),
				queued.agentMsgId())).contains("");
		// active 쪽도 여전히 정상 동작해야 한다 — pending 지원을 더하면서 active 분기를
		// 깨뜨리지 않았는지 같은 테스트에서 함께 확인한다.
		assertThat(dispatcher.activeTurnContentIfMatches(room.threadId, room.membership.generation(),
				first.agentMsgId())).isPresent();
	}

	@Test
	void closingTheLastDirectConnectionCancelsReservedTurnsStillInTheInbox() throws InterruptedException {
		FailingRoomSessionRegistry registry = new FailingRoomSessionRegistry();
		TestRoom room = new TestRoom(registry);
		registry.blockOnContent = "blocked before pending";
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		MsgPersistenceService persistence = mock(MsgPersistenceService.class);
		ThreadMessageDispatcher dispatcher = dispatcher(registry, llm, persistence);
		ChatMessageCommand.ReservedTurn reserved = reservedTurn();

		dispatcher.dispatch(directCommand(room, "blocked before pending", UUID.randomUUID(), reserved),
				room.membership.generation());
		assertThat(registry.workerBlocked.await(1, TimeUnit.SECONDS)).isTrue();
		room.registry.leave(room.threadId, room.connectionId, room.participant)
				.ifPresent(generation -> dispatcher.closeGeneration(room.threadId, generation));
		registry.releaseWorker.countDown();

		verify(persistence, timeout(1000).times(1)).cancelBlocking(reserved.agentMsgId(), "");
		verify(llm, never()).streamChat(any());
	}

	@Test
	void closingTheLastDirectConnectionCancelsQueuedReservedTurns() {
		TestRoom room = new TestRoom();
		Sinks.One<String> firstResponse = Sinks.one();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(firstResponse.asMono().flux());
		MsgPersistenceService persistence = mock(MsgPersistenceService.class);
		when(persistence.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, persistence);
		ChatMessageCommand.ReservedTurn first = reservedTurn();
		ChatMessageCommand.ReservedTurn queued = reservedTurn();

		dispatcher.dispatch(directCommand(room, "first", UUID.randomUUID(), first), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		dispatcher.dispatch(directCommand(room, "queued", UUID.randomUUID(), queued), room.membership.generation());
		room.registry.leave(room.threadId, room.connectionId, room.participant)
				.ifPresent(generation -> dispatcher.closeGeneration(room.threadId, generation));

		verify(persistence, timeout(1000).times(1)).cancelBlocking(queued.agentMsgId(), "");
	}

	@Test
	void usesTheModelTheMessageAskedForAndFallsBackToTheServerDefault() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("a"), Flux.just("b"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI one", null, null, "picked-model"), room.membership.generation());
		dispatcher.dispatch(command(room, "@AI two", null, null, " "), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> requests = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000).times(2)).streamChat(requests.capture());
		assertThat(requests.getAllValues()).extracting(ChatStreamRequest::modelId)
				.containsExactly("picked-model", "test-model");
		awaitFrameCount(room.frames, 6);
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).model())
				.containsExactly("picked-model", "picked-model", "test-model", "test-model");
	}

	@Test
	void rejectsInvalidAiSettingsAtStartup() {
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();

		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new ThreadMessageDispatcher(registry, llm, msgPersistenceService, " ",
						Duration.ofSeconds(1), 0, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new ThreadMessageDispatcher(registry, llm, msgPersistenceService, "model",
						Duration.ZERO, 0, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new ThreadMessageDispatcher(registry, llm, msgPersistenceService, "model",
						Duration.ofSeconds(1), -1, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new ThreadMessageDispatcher(registry, llm, msgPersistenceService, "model",
						Duration.ofSeconds(1), 0, -1, scheduler));
	}

	private static ThreadMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm) {
		return dispatcher(registry, llm, Duration.ofSeconds(120), 20, VirtualTimeScheduler.create());
	}

	private static ThreadMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			Duration timeout, int maxPending, VirtualTimeScheduler scheduler) {
		return new ThreadMessageDispatcher(registry, llm, mock(MsgPersistenceService.class), "test-model", timeout,
				maxPending, 20, scheduler);
	}

	private static ThreadMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			MsgPersistenceService msgPersistenceService) {
		return dispatcher(registry, llm, msgPersistenceService, 20);
	}

	private static ThreadMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			MsgPersistenceService msgPersistenceService, int maxPending) {
		return new ThreadMessageDispatcher(registry, llm, msgPersistenceService, "test-model",
				Duration.ofSeconds(120), maxPending, 20, VirtualTimeScheduler.create());
	}

	/** DIRECT 전용 — bootstrap 또는 비동기 저장이 미리 예약해뒀다고 가정하는 HUMAN·AGENT 자리. */
	private static ChatMessageCommand.ReservedTurn reservedTurn() {
		return new ChatMessageCommand.ReservedTurn(UUID.randomUUID(), 100L, UUID.randomUUID(), 101L);
	}

	/** DIRECT 발화 — reservedTurn이 항상 채워져 있고, kind가 DIRECT라는 점만 command()와 다르다. */
	private static ChatMessageCommand directCommand(TestRoom room, String content, UUID turnId,
			ChatMessageCommand.ReservedTurn reserved) {
		return new ChatMessageCommand(room.threadId, ThrKind.DIRECT, room.userId, room.participant.subject(),
				room.participant.displayName(), content, null, null, turnId, room.connectionId, "trace", reserved);
	}

	private static ChatMessageCommand command(TestRoom room, String content) {
		return command(room, content, null);
	}

	/** 취소가 관심사인 테스트용 — turnId만 싣는다. 커넥션은 방의 연결(room.connectionId)이다. */
	private static ChatMessageCommand command(TestRoom room, String content, UUID turnId) {
		return command(room, content, null, turnId, null);
	}

	private static ChatMessageCommand command(TestRoom room, String content, String clientMsgId, UUID turnId,
			String model) {
		return new ChatMessageCommand(room.threadId, ThrKind.COLLAB, room.userId, room.participant.subject(),
				room.participant.displayName(), content, model, clientMsgId, turnId, room.connectionId, "trace",
				null);
	}

	/**
	* 문맥 조회(이슈 #100)가 LLM 호출 앞에 boundedElastic 구간을 하나 더 만들어, 이후 프레임들이
	* dispatch() 호출과 같은 스레드에서 동기적으로 방송되지 않는다 — 프레임 개수가 기대치에 도달할
	* 때까지 짧게 폴링한다.
	*/
	/** msgId·seq는 아래 비교에서 무시되므로 자리만 채운다 — 그 계약은 전용 테스트가 본다. */
	private static ChatMessageFrame message(TestRoom room, String content) {
		return new ChatMessageFrame(room.threadId, null, null, null, 0L, room.participant.subject(),
				room.participant.displayName(), content);
	}

	private static ChatAnswerFrame answer(TestRoom room, String delta, ChatAnswerStatus status) {
		return new ChatAnswerFrame(room.threadId, null, null, "test-model", 0L, delta, List.of(), false, status);
	}

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
			this(new RoomSessionRegistry(Duration.ofMillis(50)));
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

		/**
		* 이 내용의 사람 메시지를 방송하는 자리에서 워커를 붙잡아 둔다(이슈 #206 재현용). 워커가
		* 여기 멈춰 있는 동안 그 발화는 inbox에서는 빠졌지만 아직 registerTurn에 닿지 않아
		* state.pending에 없고 state.inFlight에만 있다 — 문제의 창이 바로 그 상태다.
		*/
		private volatile String blockOnContent;

		private final CountDownLatch workerBlocked = new CountDownLatch(1);

		private final CountDownLatch releaseWorker = new CountDownLatch(1);

		FailingRoomSessionRegistry() {
			super(Duration.ofMillis(50));
		}

		@Override
		public boolean broadcastIfCurrent(UUID threadId, UUID roomGeneration, WsFrame frame) {
			attemptedFrames.add(frame);
			if (frame instanceof ChatMessageFrame message && message.content().equals(blockOnContent)) {
				workerBlocked.countDown();
				try {
					releaseWorker.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException error) {
					Thread.currentThread().interrupt();
				}
			}
			if (failBroadcasts) {
				throw new IllegalStateException("sink failure");
			}
			return super.broadcastIfCurrent(threadId, roomGeneration, frame);
		}
	}

}
