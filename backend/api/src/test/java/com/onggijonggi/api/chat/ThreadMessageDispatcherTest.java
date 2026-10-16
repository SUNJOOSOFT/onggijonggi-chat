package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.rag.SearchResult;
import com.onggijonggi.api.rag.ThreadDocumentSearch;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgFile;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.ThrKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import tools.jackson.databind.ObjectMapper;

import static com.onggijonggi.api.chat.Eventually.awaitIdle;
import static com.onggijonggi.api.chat.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

	/** 턴을 부른 발화의 브라우저 시간대가 LLM 요청까지 간다 — 현재 시각 도구가 그 시간대로 답한다. */
	@Test
	void passesTheSendersTimeZoneToTheLlm() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(new ChatMessageCommand(room.threadId, ThrKind.COLLAB, room.userId,
				room.participant.subject(), room.participant.displayName(), "@AI 지금 몇 시야", List.of(), null,
				"America/New_York", null, null, room.connectionId, "trace", null), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().timeZone()).isEqualTo("America/New_York");
		// 도구는 이 턴의 공통 지점을 지난다(이슈 #390).
		assertThat(request.getValue().toolExecutions()).isNotNull();
	}

	/** 입퇴장 줄은 대화가 아니라 모델 문맥에 넣지 않는다. 같은 SYSTEM이라도 위험 알림처럼 본문이 있는 줄은 그대로 간다. */
	@Test
	void leavesMembershipLinesOutOfTheLlmContext() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(any(), anyLong(), eq(room.threadId),
				eq(room.userId), eq("@AI 요약해줘"), anyList(), anyInt())).thenReturn(List.of(
						MembershipNoticeService.newNotice(room.threadId, 0L, MembershipNotice.Event.JOINED, "kc-9",
								new ObjectMapper()),
						Msg.system(room.threadId, 1L, "위험할 수 있는 발화가 감지되었습니다.")));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI 요약해줘"), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().messages()).extracting(ChatMessage::content)
				.containsExactly("위험할 수 있는 발화가 감지되었습니다.", "요약해줘");
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
		// 턴마다 도구 실행 기록이 따로라 앞 턴의 도구가 뒤 턴에 섞이지 않는다(이슈 #390).
		assertThat(requests.getAllValues().get(0).toolExecutions())
				.isNotSameAs(requests.getAllValues().get(1).toolExecutions());
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

	/** 역할별 에이전트(이슈 #357)는 페르소나 system 메시지를 대화 이력보다 앞에 끼워 보낸다. */
	@Test
	void sendsTheMentionedAgentsPersonaBeforeHistory() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@FIN 비용 관점에서 검토해줘"), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().messages().get(0).role()).isEqualTo("system");
		assertThat(request.getValue().messages().get(0).content()).isEqualTo(Agent.FIN.systemPrompt());
		assertThat(request.getValue().messages().get(1).content()).isEqualTo("비용 관점에서 검토해줘");
	}

	/**
	 * 역할별 에이전트도 방 문서 검색 결과별 웹 규칙(이슈 #368)을 그대로 받는다 — 페르소나는 근거만 말하고 "문서만"이라고
	 * 하지 않아 그 규칙과 부딪히지 않는다.
	 */
	@Test
	void roleAgentsGetTheSameWebRulesAsAi() {
		SearchResult found = new SearchResult(SearchResult.Status.FOUND, null, "q", false, List.of(new SearchResult.Chunk("doc:1:0",
				UUID.randomUUID(), "규정.pdf", 1, 0, "page=1", "출장 일비는 하루 4만 원이다.", 0.9, null)));

		ToolTurn turn = toolTurn(true, "@FIN 출장 일비를 웹에서 찾은 기준과 비교해줘", List.of("출장 일비"), List.of(Mono.just(found)));
		assertThat(turn.request().messages().get(0).content()).isEqualTo(Agent.FIN.systemPrompt());
		assertThat(turn.onlyToolResult()).contains(ThreadMessageDispatcher.FOUND_WEB_RULE);
		for (Agent agent : Agent.values()) {
			assertThat(agent.systemPrompt()).as(agent.name()).doesNotContain("문서만").doesNotContain("웹");
		}
	}

	/** `@AI`는 역할 구분이 없어 페르소나 system 메시지를 끼우지 않는다(이슈 #357) — 기존 동작 그대로다. */
	@Test
	void plainAiMentionHasNoPersonaMessage() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI 알려줘"), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().messages()).hasSize(1);
		assertThat(request.getValue().messages().get(0).role()).isEqualTo("user");
	}

	/** 한 메시지에 서로 다른 에이전트를 멘션하면 이번 범위는 거절한다(이슈 #357 "확인이 필요한 것" 1번). */
	@Test
	void rejectsMultipleDistinctAgentMentionsWithoutCallingTheLlm() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		ErrorFrame error = dispatcher.dispatch(command(room, "@FIN @LAW 같이 검토해줘"), room.membership.generation())
				.orElseThrow();

		assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
		verify(llm, times(0)).streamChat(any());
	}

	/** 답변이 어느 에이전트의 것인지가 스트리밍·완료 프레임과 저장에 함께 실린다(이슈 #357). */
	@Test
	void persistsAndBroadcastsTheMentionedAgentOnCompletion() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 1L);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId)))
				.thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@LAW 유의할 점은?"), room.membership.generation());

		awaitFrameCount(room.frames, 3);
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).agent())
				.containsOnly(Agent.LAW);
		verify(msgPersistenceService, timeout(1000)).completeBlocking(eq(pending.getId()), eq("답"), isNull(),
				eq("{\"agent\":\"law\"}"));
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
		eventually(() -> assertThat(room.frames).anySatisfy(frame -> {
			assertThat(frame).isInstanceOf(ErrorFrame.class);
			assertThat(((ErrorFrame) frame).code()).isEqualTo("AI_TURN_TIMEOUT");
		}));
		eventually(() -> assertThat(room.frames)
				.usingRecursiveFieldByFieldElementComparatorIgnoringFields("msgId", "seq").contains(
						answer(room, "next", ChatAnswerStatus.STREAMING),
						answer(room, "", ChatAnswerStatus.DONE)));
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
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(any(), anyLong(), eq(room.threadId), eq(room.userId),
				eq("@AI first"), anyList(), anyInt())).thenAnswer(invocation -> {
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

		// 취소된 턴은 LLM을 아예 부르지 않는다 — "일정 시간 아무 일도 없었다"를 고정된 250ms로
		// 관찰하는 대신, Mockito가 실제 호출 여부를 직접 폴링해서 판단하게 한다(이슈 #222).
		verify(llm, timeout(1000).times(0)).streamChat(any());
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
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(any(), anyLong(), eq(room.threadId),
				eq(room.userId), eq("@AI first"), anyList(), anyInt())).thenAnswer(invocation -> {
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

		// 취소된 턴은 LLM을 아예 부르지 않는다 — "일정 시간 아무 일도 없었다"를 고정된 250ms로
		// 관찰하는 대신, Mockito가 실제 호출 여부를 직접 폴링해서 판단하게 한다(이슈 #222).
		verify(llm, timeout(1000).times(0)).streamChat(any());
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
				eq(room.threadId), eq(room.userId), eq("일반 발화"), anyList());
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

		verify(msgPersistenceService, never()).persistHumanMessageBlocking(any(), anyLong(), any(), any(), any(), anyList());
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

		verify(msgPersistenceService, timeout(1000)).completeBlocking(pending.getId(), "hello world", null, null);
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
				eq("@AI 이어서"), anyList(), anyInt())).thenReturn(List.of(humanHistory, agentHistory));
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

	/** 협업방은 문맥을 이번 메시지 저장 전에 읽는다 — 이번 첨부는 발화에서, 이전 첨부는 문맥에서 붙는다. */
	@Test
	void putsAttachmentTextsIntoThePromptAndOnlyFileNamesIntoTheBroadcast() {
		TestRoom room = new TestRoom();
		Msg earlier = Msg.human(UUID.randomUUID(), room.threadId, 0, UUID.randomUUID(), "이 표 봐줘");
		MsgFile earlierFile = new MsgFile(UUID.randomUUID(), room.userId, "표.csv", "이름,팀");
		MsgFile currentFile = new MsgFile(UUID.randomUUID(), room.userId, "규정.pdf", "연차는 사흘 전에 신청한다.");
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 2);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답변"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.persistHumanMessageAndFetchContextBlocking(any(), anyLong(), eq(room.threadId),
				eq(room.userId), eq("@AI 요약해줘"), eq(List.of(currentFile.getId())), anyInt()))
				.thenReturn(List.of(earlier));
		when(msgPersistenceService.filesByMessageBlocking(List.of(earlier.getId())))
				.thenReturn(Map.of(earlier.getId(), List.of(earlierFile)));
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId))).thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(new ChatMessageCommand(room.threadId, ThrKind.COLLAB, room.userId,
				room.participant.subject(), room.participant.displayName(), "@AI 요약해줘", List.of(currentFile), null,
				null, null, null, room.connectionId, "trace", null), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().messages()).containsExactly(
				new ChatMessage("user", MsgFileService.withFiles("이 표 봐줘", List.of(earlierFile))),
				new ChatMessage("user", MsgFileService.withFiles("요약해줘", List.of(currentFile))));
		eventually(() -> assertThat(room.frames).filteredOn(ChatMessageFrame.class::isInstance)
				.extracting(frame -> ((ChatMessageFrame) frame).attachments())
				.containsExactly(List.of(new MsgFileView(currentFile.getId(), "규정.pdf"))));
	}

	/** 1:1은 이번 메시지가 이미 저장돼 문맥에 들어 있다 — 그쪽에서 첨부가 붙으므로 두 번 싣지 않는다. */
	@Test
	void directTurnDoesNotRepeatTheCurrentAttachmentInThePrompt() {
		TestRoom room = new TestRoom();
		ChatMessageCommand.ReservedTurn reserved = reservedTurn();
		MsgFile file = new MsgFile(UUID.randomUUID(), room.userId, "규정.pdf", "연차는 사흘 전에 신청한다.");
		Msg current = Msg.human(reserved.humanMsgId(), room.threadId, reserved.humanSeq(), UUID.randomUUID(), "요약해줘");
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of(current));
		when(msgPersistenceService.filesByMessageBlocking(List.of(current.getId())))
				.thenReturn(Map.of(current.getId(), List.of(file)));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(new ChatMessageCommand(room.threadId, ThrKind.DIRECT, room.userId,
				room.participant.subject(), room.participant.displayName(), "요약해줘", List.of(file), null, null, null, null,
				room.connectionId, "trace", reserved), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().messages()).containsExactly(
				new ChatMessage("user", MsgFileService.withFiles("요약해줘", List.of(file))),
				new ChatMessage("user", "요약해줘"));
	}

	/** 검색할 고정 문서가 없는 방에는 검색 도구도 문서 지시도 붙이지 않는다. */
	@Test
	void attachesNoDocumentToolWhenTheRoomHasNothingToSearch() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId)))
				.thenReturn(Msg.pendingAgent(UUID.randomUUID(), room.threadId, 1));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().documentTool()).isNull();
		assertThat(request.getValue().messages()).noneSatisfy(message -> assertThat(message.role()).isEqualTo("system"));
	}

	/**
	 * 고정 문서가 있으면 검색 도구를 붙이고 어떤 문서가 있는지 알리지만, 검색은 모델이 부를 때만 한다. 모델이 부르지 않은 턴(첨부 요약·
	 * 현재 시각 같은 질문)에는 출처도 안내도 붙지 않는다 — 예전에는 매 턴 미리 검색해 관련 없는 청크가 출처로 붙었다.
	 */
	@Test
	void searchesOnlyWhenTheModelCallsTheTool() {
		ToolTurn turn = toolTurn(true, List.of(), List.of());

		assertThat(turn.request().documentTool()).isNotNull();
		assertThat(systemBeforeLastUser(turn.request().messages())).contains("규정.pdf").contains("취업규칙.docx")
				.contains(ThreadDocumentTool.NAME).contains("필요 없는 질문에는 검색하지 마세요");
		verify(turn.documents(), never()).search(any(), any(), any(), any(), any());
		assertThat(turn.done()).satisfies(frame -> {
			assertThat(frame.citations()).isEmpty();
			assertThat(frame.groundingNotice()).isNull();
		});
	}

	/**
	 * 파일 이름만으로는 내용을 알기 어려워 태그(#362)가 있으면 카테고리·키워드·요약을 함께 알린다. 태그가 없는 문서(태깅 꺼짐·대기·실패)는
	 * 파일 이름만, 미분류는 카테고리 없이 싣는다. 요약은 SUMMARY_MAX_CHARS자까지, 태그는 앞의 MAX_TAGGED_DOCUMENTS개 문서에만 싣는다.
	 */
	@Test
	void listsTheDocumentTagsSoTheModelCanTellWhatEachDocumentIsAbout() {
		String prompt = ThreadMessageDispatcher.documentsPrompt(List.of(
				new SearchableDocument("규정_v3_최종.pdf", "인사·총무", List.of("급여 지급일", "수습 기간"), "급여 계산과 지급 기준"),
				SearchableDocument.untagged("메모.txt"),
				new SearchableDocument("안내.txt", null, List.of("도자기"), null)));

		assertThat(prompt).contains("- 규정_v3_최종.pdf — 인사·총무 / 키워드: 급여 지급일, 수습 기간 / 요약: 급여 계산과 지급 기준\n")
				.contains("- 메모.txt\n").contains("- 안내.txt — 키워드: 도자기\n").contains(ThreadDocumentTool.NAME)
				.contains("목록만 보고 답하지 마세요");

		String longSummary = "가".repeat(ThreadMessageDispatcher.SUMMARY_MAX_CHARS + 50);
		List<SearchableDocument> many = new ArrayList<>();
		for (int i = 0; i <= ThreadMessageDispatcher.MAX_TAGGED_DOCUMENTS; i++) {
			many.add(new SearchableDocument("문서" + i + ".txt", "인사·총무", List.of(), longSummary));
		}
		String capped = ThreadMessageDispatcher.documentsPrompt(many);

		assertThat(capped).contains("요약: " + "가".repeat(ThreadMessageDispatcher.SUMMARY_MAX_CHARS) + "…\n")
				.doesNotContain("가".repeat(ThreadMessageDispatcher.SUMMARY_MAX_CHARS + 1))
				.contains("- 문서" + ThreadMessageDispatcher.MAX_TAGGED_DOCUMENTS + ".txt\n");
	}

	/**
	 * 근거를 찾으면(FOUND) 근거와 함께 "문서 우선, 웹 검색을 요청받았으면 근거가 있어도 먼저 webSearch를 부르고, 요청하지 않았으면
	 * 웹을 쓰지도 제안하지도 않음, 도구 결과가 없으면 웹에서 찾았다고 말하지 않음"을 도구 결과로 돌려준다(이슈 #368).
	 */
	@Test
	void aFoundSearchReturnsTheEvidenceAndUsesTheWebOnlyWhenAsked() {
		SearchResult found = new SearchResult(SearchResult.Status.FOUND, null, "q", false, List.of(new SearchResult.Chunk("doc:1:0",
				UUID.randomUUID(), "규정.pdf", 1, 0, "page=1", "최저임금은 시간당 1만 원이다.", 0.9, null)));

		String withWeb = toolTurn(true, List.of("최저임금"), List.of(Mono.just(found))).onlyToolResult();
		assertThat(withWeb).contains("최저임금은 시간당 1만 원이다.").contains("문서 근거를 우선")
				.contains(ThreadMessageDispatcher.FOUND_WEB_RULE).doesNotContain(ThreadMessageDispatcher.WEB_TOOL_HONESTY);
		assertThat(ThreadMessageDispatcher.FOUND_WEB_RULE).contains("먼저 webSearch 도구를 호출")
				.contains("웹 검색을 먼저 제안하지도 마세요").contains("웹 검색을 하지 못했다고 말하세요");
		assertThat(withWeb.indexOf(ThreadMessageDispatcher.FOUND_WEB_RULE)).as("웹 규칙은 근거 목록 뒤에 둔다")
				.isGreaterThan(withWeb.indexOf("최저임금은 시간당 1만 원이다."));

		assertThat(toolTurn(false, List.of("최저임금"), List.of(Mono.just(found))).onlyToolResult()).as("웹이 꺼진 배포에서는 웹을 말하지 않는다")
				.contains("문서 근거를 우선").doesNotContain("웹");
	}

	/** 근거가 없으면 찾지 못했다는 사실만 돌려준다 — 웹을 쓸지는 모델이 판단한다(이슈 #368). */
	@Test
	void aNoEvidenceSearchOnlySaysNothingWasFound() {
		SearchResult noMatch = new SearchResult(SearchResult.Status.NO_EVIDENCE, SearchResult.Reason.NO_MATCH, "q", false, List.of());

		assertThat(toolTurn(true, List.of("최저임금"), List.of(Mono.just(noMatch))).onlyToolResult())
				.isEqualTo(ThreadMessageDispatcher.NO_EVIDENCE_RESULT).doesNotContain("웹");
	}

	/**
	 * 문서 검색을 하지 못하면(UNAVAILABLE·검색 호출 예외) 알리게 하고, 웹은 쓰지 말고 웹 검색 여부를 묻게 한다. 답변은 계속된다.
	 * 고장 원인은 넣지 않고, 웹이 꺼진 배포에서는 웹을 제안하지 않는다(이슈 #368).
	 */
	@Test
	void anUnavailableSearchIsToldToTheModelAndOffersTheWebOnlyWhenAvailable() {
		for (SearchResult.Reason reason : List.of(SearchResult.Reason.BACKEND_ERROR, SearchResult.Reason.MODEL_MISMATCH,
				SearchResult.Reason.NOT_CONFIGURED, SearchResult.Reason.OVERLOADED)) {
			String result = toolTurn(true, List.of("q"), List.of(Mono.just(new SearchResult(SearchResult.Status.UNAVAILABLE, reason, "q",
					false, List.of())))).onlyToolResult();
			assertThat(result).as(reason.name()).contains("검색하지 못했습니다").contains("웹 검색으로 답변해 드릴지 물어보세요")
					.contains("앞선 제안에 응했으면").contains(ThreadMessageDispatcher.WEB_TOOL_HONESTY).doesNotContain(reason.name())
					.doesNotContain("Elasticsearch");
		}
		assertThat(toolTurn(true, List.of("q"), List.of(Mono.error(new IllegalStateException("검색 서버 끊김")))).onlyToolResult())
				.as("검색 호출 예외도 같은 결과").contains("검색하지 못했습니다").contains("웹 검색으로 답변해 드릴지").doesNotContain("끊김");
		assertThat(toolTurn(false, List.of("q"), List.of(Mono.just(new SearchResult(SearchResult.Status.UNAVAILABLE,
				SearchResult.Reason.BACKEND_ERROR, "q", false, List.of())))).onlyToolResult())
				.as("웹이 꺼진 배포").contains("확인할 수 없다고 알리세요").doesNotContain("웹");
	}

	/**
	 * 모델이 검색해 근거를 찾으면(FOUND) 완료 프레임·저장 둘 다에 citations가 채워진다(이슈 #347). 검색어는 모델이 쓴 문장 그대로이고,
	 * 이미 독립 문장이라 이력을 넘기지 않는다(다시 쓰기 생략).
	 */
	@Test
	void includesDocumentSearchCitationsWhenTheModelSearches() {
		UUID docId = UUID.randomUUID();
		SearchResult.Chunk chunk = new SearchResult.Chunk("doc:1:0", docId, "규정.pdf", 1, 0, "page=1",
				"연차는 사흘 전에 신청한다.", 0.9, null);
		ToolTurn turn = toolTurn(true, List.of("연차 신청 기한"),
				List.of(Mono.just(new SearchResult(SearchResult.Status.FOUND, null, "연차 신청 기한", false, List.of(chunk)))));

		assertThat(turn.done().citations()).containsExactly(
				new Citation(docId.toString(), "규정.pdf", "연차는 사흘 전에 신청한다.", 0.9, "doc:1:0", "page=1"));
		verify(turn.documents()).search(eq(turn.room().threadId), any(), eq("연차 신청 기한"), eq(List.of()), eq("test-model"));
		ArgumentCaptor<String> citationsJson = ArgumentCaptor.forClass(String.class);
		verify(turn.persistence(), timeout(1000)).completeBlocking(eq(turn.pending().getId()), eq("답"),
				citationsJson.capture(), isNull());
		assertThat(citationsJson.getValue()).contains(docId.toString()).contains("규정.pdf");
	}

	/** 모델이 검색했지만 맞는 근거가 없을 때(NO_MATCH) 사용자에게 안내를 보낸다(이슈 #353) — 일반 답변은 그대로 허용한다. */
	@Test
	void sendsNoEvidenceNoticeWhenSearchFindsNoMatchingChunks() {
		ToolTurn turn = toolTurn(true, List.of("q"), List.of(Mono.just(new SearchResult(SearchResult.Status.NO_EVIDENCE,
				SearchResult.Reason.NO_MATCH, "q", false, List.of()))));

		assertThat(turn.done()).satisfies(frame -> {
			assertThat(frame.groundingNotice()).isEqualTo(GroundingNotice.NO_EVIDENCE);
			assertThat(frame.citations()).isEmpty();
		});
	}

	/** 방 문서 검색 자체가 실패하면 근거 없이 답하면서도 "검색을 하지 못했다"는 안내를 보낸다(이슈 #353). */
	@Test
	void sendsUnavailableNoticeWhenDocumentSearchFails() {
		ToolTurn turn = toolTurn(true, List.of("q"), List.of(Mono.error(new IllegalStateException("elasticsearch down"))));

		assertThat(turn.done().groundingNotice()).isEqualTo(GroundingNotice.UNAVAILABLE);
	}

	/** 모델이 검색어를 바꿔 다시 불러 한 번이라도 근거를 찾으면 근거 없음 안내를 보내지 않는다. */
	@Test
	void aLaterFoundSearchClearsTheNoEvidenceNotice() {
		SearchResult.Chunk chunk = new SearchResult.Chunk("doc:1:0", UUID.randomUUID(), "규정.pdf", 1, 0, "page=1",
				"연차는 사흘 전에 신청한다.", 0.9, null);
		ToolTurn turn = toolTurn(true, List.of("휴가", "연차 신청 기한"), List.of(
				Mono.just(new SearchResult(SearchResult.Status.NO_EVIDENCE, SearchResult.Reason.NO_MATCH, "휴가", false, List.of())),
				Mono.just(new SearchResult(SearchResult.Status.FOUND, null, "연차 신청 기한", false, List.of(chunk)))));

		assertThat(turn.done()).satisfies(frame -> {
			assertThat(frame.groundingNotice()).isNull();
			assertThat(frame.citations()).extracting(Citation::chunkId).containsExactly("doc:1:0");
		});
	}

	/** 고정 문서 목록을 읽지 못하면 도구 없이 검색 실패와 같은 지시·안내로 답한다 — 장애를 조용히 넘기지 않는다. */
	/** 모델이 개정본 도구를 부르면 이 턴의 방·요청자로 ThreadDocumentRevision에 넘기고, 그 문장을 모델에 돌려준다. */
	@Test
	void theRevisionToolRunsForTheTurnsRoomAndRequester() {
		TestRoom room = new TestRoom();
		AtomicReference<String> toolResult = new AtomicReference<>();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		List<ThreadDocumentTool.Edit> edits = List.of(new ThreadDocumentTool.Edit("주 12시간", "주 18시간"));
		when(llm.streamChat(any())).thenAnswer(invocation -> {
			ChatStreamRequest sent = invocation.getArgument(0);
			toolResult.set(sent.documentTool().reviseThreadDocument("규정.docx", edits));
			return Flux.just("답");
		});
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId)))
				.thenReturn(Msg.pendingAgent(UUID.randomUUID(), room.threadId, 1));
		ThreadDocumentSearch documents = mock(ThreadDocumentSearch.class);
		when(documents.searchableDocuments(eq(room.threadId), any())).thenReturn(Mono.just(List.of(SearchableDocument.untagged("규정.docx"))));
		ThreadDocumentRevision revision = mock(ThreadDocumentRevision.class);
		when(revision.revise(eq(room.threadId), any(), eq("규정.docx"), eq(edits))).thenReturn("등록했습니다");
		ThreadMessageDispatcher dispatcher = new ThreadMessageDispatcher(room.registry, llm, msgPersistenceService, documents,
				new ObjectMapper(), "test-model", Duration.ofSeconds(120), 20, 20, VirtualTimeScheduler.create(), true, revision);

		dispatcher.dispatch(command(room, "@AI 개정본 만들어줘"), room.membership.generation());

		awaitDone(room);
		assertThat(toolResult).hasValue("등록했습니다");
		ArgumentCaptor<CurrentActor> actor = ArgumentCaptor.forClass(CurrentActor.class);
		verify(revision).revise(eq(room.threadId), actor.capture(), eq("규정.docx"), eq(edits));
		assertThat(actor.getValue().userId()).isEqualTo(room.userId);
	}

	@Test
	void answersWithoutTheToolWhenTheDocumentListFails() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId)))
				.thenReturn(Msg.pendingAgent(UUID.randomUUID(), room.threadId, 1));
		ThreadDocumentSearch documents = mock(ThreadDocumentSearch.class);
		when(documents.searchableDocuments(eq(room.threadId), any())).thenReturn(Mono.error(new IllegalStateException("db down")));
		ThreadMessageDispatcher dispatcher = new ThreadMessageDispatcher(room.registry, llm, msgPersistenceService, documents,
				new ObjectMapper(), "test-model", Duration.ofSeconds(120), 20, 20, VirtualTimeScheduler.create());

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());

		ArgumentCaptor<ChatStreamRequest> request = ArgumentCaptor.forClass(ChatStreamRequest.class);
		verify(llm, timeout(1000)).streamChat(request.capture());
		assertThat(request.getValue().documentTool()).isNull();
		assertThat(systemBeforeLastUser(request.getValue().messages())).contains("검색하지 못했습니다").doesNotContain("db down");
		assertThat(awaitDone(room).groundingNotice()).isEqualTo(GroundingNotice.UNAVAILABLE);
	}

	/** 검색 도구가 붙은 한 턴의 결과. */
	private record ToolTurn(TestRoom room, ChatStreamRequest request, List<String> toolResults, ThreadDocumentSearch documents,
			MsgPersistenceService persistence, Msg pending) {

		ChatAnswerFrame done() {
			return awaitDone(room);
		}

		String onlyToolResult() {
			done();
			assertThat(toolResults).hasSize(1);
			return toolResults.get(0);
		}
	}

	/**
	 * 검색할 고정 문서(규정.pdf, 취업규칙.docx)가 있는 방에서 한 턴을 돌린다. 모델은 queries를 차례로 검색 도구에 넘긴 뒤 "답"을 내고,
	 * 검색은 results를 차례로 돌려준다.
	 */
	private static ToolTurn toolTurn(boolean webEnabled, List<String> queries, List<Mono<SearchResult>> results) {
		return toolTurn(webEnabled, "@AI 올해 최저임금 얼마야?", queries, results);
	}

	private static ToolTurn toolTurn(boolean webEnabled, String content, List<String> queries, List<Mono<SearchResult>> results) {
		TestRoom room = new TestRoom();
		AtomicReference<ChatStreamRequest> request = new AtomicReference<>();
		List<String> toolResults = new CopyOnWriteArrayList<>();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenAnswer(invocation -> {
			ChatStreamRequest sent = invocation.getArgument(0);
			request.set(sent);
			for (String query : queries) {
				toolResults.add(sent.documentTool().searchThreadDocuments(query));
			}
			return Flux.just("답");
		});
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 1);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId))).thenReturn(pending);
		ThreadDocumentSearch documents = mock(ThreadDocumentSearch.class);
		when(documents.searchableDocuments(eq(room.threadId), any())).thenReturn(Mono.just(List.of(SearchableDocument.untagged("규정.pdf"),
				SearchableDocument.untagged("취업규칙.docx"))));
		if (!results.isEmpty()) {
			OngoingStubbing<Mono<SearchResult>> stub = when(documents.search(eq(room.threadId), any(), any(), any(), any()));
			for (Mono<SearchResult> result : results) {
				stub = stub.thenReturn(result);
			}
		}
		ThreadMessageDispatcher dispatcher = new ThreadMessageDispatcher(room.registry, llm, msgPersistenceService, documents,
				new ObjectMapper(), "test-model", Duration.ofSeconds(120), 20, 20, VirtualTimeScheduler.create(), webEnabled, null);

		dispatcher.dispatch(command(room, content), room.membership.generation());

		awaitDone(room);
		return new ToolTurn(room, request.get(), toolResults, documents, msgPersistenceService, pending);
	}

	/** 답변 완료(DONE) 프레임을 기다린다. */
	private static ChatAnswerFrame awaitDone(TestRoom room) {
		long deadline = System.currentTimeMillis() + 2000;
		while (System.currentTimeMillis() < deadline) {
			Optional<ChatAnswerFrame> done = room.frames.stream().filter(ChatAnswerFrame.class::isInstance)
					.map(ChatAnswerFrame.class::cast).filter(frame -> frame.status() == ChatAnswerStatus.DONE).findFirst();
			if (done.isPresent()) {
				return done.get();
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("DONE 프레임이 오지 않았다");
	}

	/** 이번 발화(맨 끝 user) 바로 앞의 system 지시. */
	private static String systemBeforeLastUser(List<ChatMessage> messages) {
		assertThat(messages.get(messages.size() - 1).role()).isEqualTo("user");
		ChatMessage instruction = messages.get(messages.size() - 2);
		assertThat(instruction.role()).isEqualTo("system");
		return instruction.content();
	}

	/** 고정 문서가 없는 방은 검색 도구가 없어 안내도 나오지 않는다(이슈 #353). dispatcher()의 기본 noOpDocumentSearch가 그런 방이다. */
	@Test
	void sendsNoNoticeWhenRoomHasNoPinnedDocuments() {
		TestRoom room = new TestRoom();
		Msg pending = Msg.pendingAgent(UUID.randomUUID(), room.threadId, 0);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("답"));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.createPendingAgentMessageBlocking(any(), anyLong(), eq(room.threadId))).thenReturn(pending);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(command(room, "@AI first"), room.membership.generation());

		awaitFrameCount(room.frames, 2);
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.map(ChatAnswerFrame.class::cast)
				.filteredOn(frame -> frame.status() == ChatAnswerStatus.DONE)
				.first()
				.satisfies(frame -> assertThat(frame.groundingNotice()).isNull());
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
		// 조각이 실제로 방송된 뒤에 방송을 고장 낸다. 구독 신호(subscribed)는 요청이 닿기 전에 올 수 있어,
		// 그 틈에 고장을 먼저 내면 조각 방송이 실패해 턴이 완료가 아니라 취소로 끝난다.
		awaitAttemptedDelta(registry, "answer");
		registry.failBroadcasts = true;
		source.tryEmitComplete();

		// 여기만 다른 테스트보다 넓게 기다린다(이슈 #207). 완료 저장은 방송 실패로 예외가 한 번 더
		// 오가는 경로라 스케줄링 편차가 누적되는데, 같은 JVM에서 클래스 전체를 연달아 돌리는 CI에서는
		// 1초 창이 빠듯해 10회 중 1~2회 간헐 실패했다. 프로덕션 순서·로직 결함이 아니라는 것은
		// 이슈에서 반복 측정으로 확인됐다(#207, Refs #197).
		verify(msgPersistenceService, timeout(5000)).completeBlocking(pending.getId(), "answer", null, null);
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
	void membershipRevocationCancelsTheSubjectsActiveCollabTurn() {
		TestRoom room = new TestRoom();
		AtomicBoolean upstreamCancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true)));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI revoke this", UUID.randomUUID()), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());

		dispatcher.cancelCollabTurnsFrom(room.participant.subject());

		awaitTrue(upstreamCancelled);
		assertThat(upstreamCancelled).isTrue();
		eventually(() -> assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).status())
				.contains(ChatAnswerStatus.DONE));
	}

	/**
	* 방 프레임이 registry 대기열에서 나가다 sink에서 실패하면(여기서는 듣는 사람 없이 warm-up 버퍼 한 칸을 넘김) registry가
	* 그 세대를 알려 오고, 그 세대의 AI 작업이 닫힌다(이슈 #405). 실패를 그 방의 다음 방송에 떠넘기지 않는다.
	*/
	@Test
	void aFrameThatFailsInTheRoomQueueClosesItsOwnGeneration() {
		TestRoom room = new TestRoom();
		room.subscription.dispose();
		AtomicBoolean upstreamCancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("a", "b").concatWith(Flux.<String>never())
				.doOnCancel(() -> upstreamCancelled.set(true)));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI fail", UUID.randomUUID()), room.membership.generation());

		awaitTrue(upstreamCancelled);
		assertThat(upstreamCancelled).isTrue();
	}

	@Test
	void revocationDuringCompletionDoesNotEmitASecondTerminalFrame() {
		AtomicReference<Runnable> revokeOnDone = new AtomicReference<>();
		AtomicBoolean triggered = new AtomicBoolean();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50)) {
			@Override
			public boolean broadcastIfCurrent(UUID threadId, UUID generation, WsFrame frame) {
				boolean broadcast = super.broadcastIfCurrent(threadId, generation, frame);
				if (frame instanceof ChatAnswerFrame answer && answer.status() == ChatAnswerStatus.DONE
						&& triggered.compareAndSet(false, true)) revokeOnDone.get().run();
				return broadcast;
			}
		};
		TestRoom room = new TestRoom(registry);
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.just("answer"));
		ThreadMessageDispatcher dispatcher = dispatcher(registry, llm);
		revokeOnDone.set(() -> dispatcher.cancelCollabTurnsFrom(room.participant.subject()));

		dispatcher.dispatch(command(room, "@AI finish", UUID.randomUUID()), room.membership.generation());

		awaitTrue(triggered);
		assertThat(triggered).isTrue();
		awaitIdle(registry, room.threadId);
		assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
				.extracting(frame -> ((ChatAnswerFrame) frame).status())
				.containsExactly(ChatAnswerStatus.STREAMING, ChatAnswerStatus.DONE);
	}

	@Test
	void revocationWaitsForInProgressDeltaBeforeSendingTerminalFrame() throws Exception {
		CountDownLatch streamingEntered = new CountDownLatch(1);
		CountDownLatch releaseStreaming = new CountDownLatch(1);
		CountDownLatch revocationStarted = new CountDownLatch(1);
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50)) {
			@Override
			public boolean broadcastIfCurrent(UUID threadId, UUID generation, WsFrame frame) {
				if (frame instanceof ChatAnswerFrame answer && answer.status() == ChatAnswerStatus.STREAMING) {
					streamingEntered.countDown();
					try {
						releaseStreaming.await(2, TimeUnit.SECONDS);
					} catch (InterruptedException error) {
						Thread.currentThread().interrupt();
					}
				}
				return super.broadcastIfCurrent(threadId, generation, frame);
			}
		};
		TestRoom room = new TestRoom(registry);
		Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(source.asFlux());
		ThreadMessageDispatcher dispatcher = dispatcher(registry, llm);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			dispatcher.dispatch(command(room, "@AI race", UUID.randomUUID()), room.membership.generation());
			verify(llm, timeout(1000)).streamChat(any());
			Future<?> emission = executor.submit(() -> source.tryEmitNext("delta"));
			assertThat(streamingEntered.await(1, TimeUnit.SECONDS)).isTrue();
			Future<?> revocation = executor.submit(() -> {
				revocationStarted.countDown();
				dispatcher.cancelCollabTurnsFrom(room.participant.subject());
			});
			assertThat(revocationStarted.await(1, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> revocation.get(100, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
			releaseStreaming.countDown();
			emission.get(2, TimeUnit.SECONDS);
			revocation.get(2, TimeUnit.SECONDS);
			awaitFrameCount(room.frames, 3);
			assertThat(room.frames).filteredOn(ChatAnswerFrame.class::isInstance)
					.extracting(frame -> ((ChatAnswerFrame) frame).status())
					.containsExactly(ChatAnswerStatus.STREAMING, ChatAnswerStatus.DONE);
		} finally {
			releaseStreaming.countDown();
			executor.shutdownNow();
		}
	}

	/** 권한 회수는 1:1 턴을 건드리지 않는다(#299) — 1:1은 워크스페이스 권한이 아니라 소유자 계약으로 지킨다. */
	@Test
	void membershipRevocationLeavesTheSubjectsDirectTurnRunning() {
		TestRoom room = new TestRoom();
		AtomicBoolean upstreamCancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true)));
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.recentCompleteContextBlocking(eq(room.threadId), anyInt())).thenReturn(List.of());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm, msgPersistenceService);

		dispatcher.dispatch(directCommand(room, "first", UUID.randomUUID(), reservedTurn()), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());

		dispatcher.cancelCollabTurnsFrom(room.participant.subject());

		// 취소는 호출 안에서 동기로 일어나므로, 호출이 끝났는데 끊기지 않았다면 건드리지 않은 것이다.
		assertThat(upstreamCancelled).isFalse();
	}

	/** 같은 협업방이라도 다른 사람이 시작한 턴은 그대로 둔다 — 권한을 잃은 사람의 턴만 취소한다. */
	@Test
	void membershipRevocationLeavesOtherPeoplesCollabTurnsRunning() {
		TestRoom room = new TestRoom();
		AtomicBoolean upstreamCancelled = new AtomicBoolean();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true)));
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);

		dispatcher.dispatch(command(room, "@AI keep going", UUID.randomUUID()), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());

		dispatcher.cancelCollabTurnsFrom("someone-else");

		assertThat(upstreamCancelled).isFalse();
	}

	@Test
	void membershipRevocationCancelsTheSubjectsQueuedCollabTurn() {
		TestRoom room = new TestRoom();
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, llm);
		UUID queuedTurnId = UUID.randomUUID();

		dispatcher.dispatch(command(room, "@AI first", UUID.randomUUID()), room.membership.generation());
		verify(llm, timeout(1000)).streamChat(any());
		dispatcher.dispatch(command(room, "@AI queued", queuedTurnId), room.membership.generation());
		awaitFrameCount(room.frames, 3);

		dispatcher.cancelCollabTurnsFrom(room.participant.subject());

		eventually(() -> assertThat(room.frames)
				.contains(new ChatQueuedFrame(room.threadId, queuedTurnId, ChatQueuedStatus.CANCELLED)));
		verify(llm, times(1)).streamChat(any());
	}

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
				eq(room.userId), eq("@AI stop me"), anyList());
		verify(llm, never()).streamChat(any());
	}

	/**
	* 대화가 오가는 방의 입퇴장 줄은 그 방의 블록에서 seq를 받는다 — DB에서 따로 뽑으면 이미 예약된 블록 뒤
	* 번호라 그 뒤의 대화보다 뒤로 정렬된다. 대화가 없는 방은 DB에서 하나만 뽑는다.
	*/
	@Test
	void allocateSeqUsesTheOpenRoomsBlockAndFallsBackToTheDatabaseOtherwise() {
		TestRoom room = new TestRoom();
		UUID quietThreadId = UUID.randomUUID();
		MsgPersistenceService msgPersistenceService = mock(MsgPersistenceService.class);
		when(msgPersistenceService.allocateSeqBlockBlocking(room.threadId, 100)).thenReturn(500L);
		when(msgPersistenceService.allocateSeqBlockBlocking(quietThreadId, 1)).thenReturn(900L);
		ThreadMessageDispatcher dispatcher = dispatcher(room.registry, mock(LlmChatStreamService.class),
				msgPersistenceService);

		dispatcher.dispatch(command(room, "안녕하세요"), room.membership.generation());
		awaitFrameCount(room.frames, 1);

		assertThat(((ChatMessageFrame) room.frames.get(0)).seq()).isEqualTo(500L);
		assertThat(dispatcher.allocateSeqBlocking(room.threadId)).isEqualTo(501L);
		assertThat(dispatcher.allocateSeqBlocking(quietThreadId)).isEqualTo(900L);
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
		verify(msgPersistenceService, never()).persistHumanMessageBlocking(any(), anyLong(), any(), any(), any(), anyList());
		verify(msgPersistenceService, never()).persistHumanMessageAndFetchContextBlocking(any(), anyLong(), any(),
				any(), any(), anyList(), anyInt());
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
		eventually(() -> assertThat(room.frames).filteredOn(frame -> frame instanceof ChatAnswerFrame answerFrame
						&& answerFrame.msgId().equals(reserved.agentMsgId()))
				.extracting(frame -> ((ChatAnswerFrame) frame).status())
				.contains(ChatAnswerStatus.CANCELLED));
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
		eventually(() -> assertThat(room.frames).filteredOn(frame -> frame instanceof ChatAnswerFrame answerFrame
						&& answerFrame.msgId().equals(second.agentMsgId()))
				.extracting(frame -> ((ChatAnswerFrame) frame).status())
				.containsExactly(ChatAnswerStatus.CANCELLED));
		awaitIdle(room.registry, room.threadId);
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
		ThreadDocumentSearch documents = noOpDocumentSearch();
		ObjectMapper json = new ObjectMapper();
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new ThreadMessageDispatcher(registry, llm, msgPersistenceService, documents, json,
						" ", Duration.ofSeconds(1), 0, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new ThreadMessageDispatcher(registry, llm, msgPersistenceService, documents, json,
						"model", Duration.ZERO, 0, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new ThreadMessageDispatcher(registry, llm, msgPersistenceService, documents, json,
						"model", Duration.ofSeconds(1), -1, 20, scheduler));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> new ThreadMessageDispatcher(registry, llm, msgPersistenceService, documents, json,
						"model", Duration.ofSeconds(1), 0, -1, scheduler));
	}

	private static ThreadMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm) {
		return dispatcher(registry, llm, Duration.ofSeconds(120), 20, VirtualTimeScheduler.create());
	}

	private static ThreadMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			Duration timeout, int maxPending, VirtualTimeScheduler scheduler) {
		return new ThreadMessageDispatcher(registry, llm, mock(MsgPersistenceService.class), noOpDocumentSearch(),
				new ObjectMapper(), "test-model", timeout, maxPending, 20, scheduler);
	}

	/** 이 파일의 테스트는 대부분 RAG 검색을 다루지 않는다 — 검색할 고정 문서가 없는 방으로 두어 검색 도구도 지시도 붙지 않게 한다. */
	private static ThreadDocumentSearch noOpDocumentSearch() {
		ThreadDocumentSearch documents = mock(ThreadDocumentSearch.class);
		when(documents.searchableDocuments(any(), any())).thenReturn(Mono.just(List.of()));
		return documents;
	}

	private static ThreadMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			MsgPersistenceService msgPersistenceService) {
		return dispatcher(registry, llm, msgPersistenceService, 20);
	}

	private static ThreadMessageDispatcher dispatcher(RoomSessionRegistry registry, LlmChatStreamService llm,
			MsgPersistenceService msgPersistenceService, int maxPending) {
		return new ThreadMessageDispatcher(registry, llm, msgPersistenceService, noOpDocumentSearch(),
				new ObjectMapper(), "test-model", Duration.ofSeconds(120), maxPending, 20,
				VirtualTimeScheduler.create());
	}

	/** DIRECT 전용 — bootstrap 또는 비동기 저장이 미리 예약해뒀다고 가정하는 HUMAN·AGENT 자리. */
	private static ChatMessageCommand.ReservedTurn reservedTurn() {
		return new ChatMessageCommand.ReservedTurn(UUID.randomUUID(), 100L, UUID.randomUUID(), 101L);
	}

	/** DIRECT 발화 — reservedTurn이 항상 채워져 있고, kind가 DIRECT라는 점만 command()와 다르다. */
	private static ChatMessageCommand directCommand(TestRoom room, String content, UUID turnId,
			ChatMessageCommand.ReservedTurn reserved) {
		return new ChatMessageCommand(room.threadId, ThrKind.DIRECT, room.userId, room.participant.subject(),
				room.participant.displayName(), content, List.of(), null, null, null, turnId, room.connectionId,
				"trace", reserved);
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
				room.participant.displayName(), content, List.of(), model, null, clientMsgId, turnId,
				room.connectionId, "trace", null);
	}

	/**
	* 문맥 조회(이슈 #100)가 LLM 호출 앞에 boundedElastic 구간을 하나 더 만들어, 이후 프레임들이
	* dispatch() 호출과 같은 스레드에서 동기적으로 방송되지 않는다 — 프레임 개수가 기대치에 도달할
	* 때까지 짧게 폴링한다.
	*/
	/** msgId·seq는 아래 비교에서 무시되므로 자리만 채운다 — 그 계약은 전용 테스트가 본다. */
	private static ChatMessageFrame message(TestRoom room, String content) {
		return new ChatMessageFrame(room.threadId, null, null, null, 0L, room.participant.subject(),
				room.participant.displayName(), content, List.of());
	}

	private static ChatAnswerFrame answer(TestRoom room, String delta, ChatAnswerStatus status) {
		return new ChatAnswerFrame(room.threadId, null, null, "test-model", 0L, delta, List.of(), false, status, null,
				null);
	}

	private static void awaitAttemptedDelta(FailingRoomSessionRegistry registry, String delta) {
		long deadline = System.currentTimeMillis() + 2000;
		while (registry.attemptedFrames.stream()
				.noneMatch(frame -> frame instanceof ChatAnswerFrame answer && delta.equals(answer.delta()))
				&& System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			}
		}
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
