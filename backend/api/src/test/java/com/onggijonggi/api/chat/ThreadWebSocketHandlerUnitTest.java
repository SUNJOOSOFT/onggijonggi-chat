package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.ThrKind;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Class Name : ThreadWebSocketHandlerUnitTest.java
 * Description : `WebSocketSession`을 mock으로 대체해 `ThreadWebSocketHandler`의 구독·종료
 *               경로만 좁게 검증한다. receive/send가 정확히 한 번씩만 구독되는지, 사용자
 *               프로비저닝 실패 시 INTERNAL_ERROR 후 정상 종료하는지, JWT 만료 시 4000으로 닫는지,
 *               방 버퍼가 넘칠 때 커넥션은 두고 그 방 구독만 푸는지(이슈 #161)를 실제 소켓 없이 확인한다.
 */
class ThreadWebSocketHandlerUnitTest {

	private static final long WINDOW_SECONDS = 60;

	/** 다른 테스트가 한도에 걸려 엉뚱하게 실패하지 않도록 넉넉히 둔다. */
	private static final int MESSAGES_PER_WINDOW = 1000;

	@Test
	void subscribesToReceiveAndSendExactlyOnce() {
		UUID userId = UUID.randomUUID();
		AtomicInteger receiveSubscriptions = new AtomicInteger();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "ws-user";

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("ws-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.defer(() -> {
			receiveSubscriptions.incrementAndGet();
			return Flux.empty();
		}));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(invocation.getArgument(0)).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		ThreadWebSocketHandler handler = handler(registry, provisioning);

		handler.handle(session).block();

		assertThat(receiveSubscriptions).hasValue(1);
		verify(session, times(1)).receive();
		verify(session, times(1)).send(any());
	}

	/** 커넥션 자체를 못 세운 실패라 어느 방에도 속하지 않는다 — threadId 없이 알린다(이슈 #161). */
	@Test
	void sendsInternalErrorAndClosesNormallyWhenUserProvisioningFails() {
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "failed-user";
		AtomicReference<String> sent = new AtomicReference<>();

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("failed-user")).thenReturn(Mono.error(new RuntimeException("db down")));
		stubTextMessages(session);
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.set(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		ThreadWebSocketHandler handler = handler(registry, provisioning);

		handler.handle(session).block();

		assertThat(sent.get()).contains("\"type\":\"error\"", "\"code\":\"INTERNAL_ERROR\"", "\"threadId\":null");
		verify(session).close(CloseStatus.NORMAL);
	}

	@Test
	void closesWithCode4000WhenTheJwtExpires() {
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		JwtAuthenticationToken authentication = mock(JwtAuthenticationToken.class);
		Jwt jwt = mock(Jwt.class);

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(authentication));
		when(authentication.getToken()).thenReturn(jwt);
		when(jwt.getSubject()).thenReturn("expiring-user");
		when(jwt.getExpiresAt()).thenReturn(Instant.now().plusMillis(50));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("expiring-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.never());
		when(session.send(any())).thenAnswer(invocation -> Flux.from(invocation.getArgument(0)).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		ThreadWebSocketHandler handler = handler(registry, provisioning);

		handler.handle(session).block(java.time.Duration.ofSeconds(1));

		verify(session).close(argThat(status -> status.getCode() == 4000));
	}

	/**
	* 방 버퍼가 넘치면 그 방 구독만 풀고 커넥션은 닫지 않는다(이슈 #161). 커넥션 단위로 끊던 예전(1011)
	* 대로면 한 방의 폭주가 그 사용자의 모든 방을 끊는다. 구독이 풀렸다는 것은 같은 방에 남은 사람이
	* 그 사용자의 퇴장 통보를 받는 것으로 본다 — 느린 쪽 소켓은 읽지 않으므로 그쪽 프레임으로는 볼 수 없다.
	*/
	@Test
	void unsubscribesOnlyTheOverflowingRoomAndKeepsTheConnectionOpen() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "slow-user";
		CountDownLatch slowJoined = new CountDownLatch(1);
		CountDownLatch slowLeft = new CountDownLatch(1);

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("slow-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.subscribeFrame(threadId)))
				.concatWith(Flux.never()));
		when(session.send(any())).thenAnswer(invocation -> {
			Flux.from(invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
					.subscribe(new BaseSubscriber<>() {
						@Override
						protected void hookOnSubscribe(Subscription subscription) {
							// 느린 네트워크 write를 재현한다. 의도적으로 demand를 요청하지 않는다.
						}
					});
			return Mono.never();
		});
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		UUID observerId = UUID.randomUUID();
		PresenceParticipant observerParticipant =
				new PresenceParticipant("observer", "지켜보는 사람");
		RoomSessionRegistry.RoomMembership observer = registry.join(threadId, observerId, observerParticipant);
		var observerSubscription = observer.frames().subscribe(frame -> {
			if (frame instanceof PresenceJoinFrame join && join.subject().equals("slow-user")) {
				slowJoined.countDown();
			}
			if (frame instanceof PresenceLeaveFrame leave && leave.subject().equals("slow-user")) {
				slowLeft.countDown();
			}
		});
		ThreadWebSocketHandler handler = handler(registry, provisioning);
		var handlerSubscription = handler.handle(session).subscribe();
		try {
			assertThat(slowJoined.await(1, TimeUnit.SECONDS)).isTrue();
			for (int i = 0; i < 1000 && slowLeft.getCount() > 0; i++) {
				registry.broadcastIfCurrent(threadId, observer.generation(),
						new ChatMessageFrame(threadId, UUID.randomUUID(), null, null, 0L, "someone", "누군가", "message-" + i));
			}

			assertThat(slowLeft.await(2, TimeUnit.SECONDS)).isTrue();
			verify(session, never()).close(any(CloseStatus.class));
		} finally {
			handlerSubscription.dispose();
			observerSubscription.dispose();
			registry.leave(threadId, observerId, observerParticipant);
		}
	}

	/** 이 테스트들의 관심사는 메시지 루프라 인가는 통과시킨다 — 참가자 검증 자체는 통합 테스트가 맡는다. */
	private static ThreadMembershipService admittingMembership() {
		ThreadMembershipService membership = mock(ThreadMembershipService.class);
		when(membership.isActiveParticipant(any(), any())).thenReturn(Mono.just(true));
		when(membership.kindOf(any())).thenReturn(Mono.just(Optional.of(ThrKind.COLLAB)));
		when(membership.isActiveDirectOwner(any(), any())).thenReturn(Mono.just(true));
		when(membership.isOpenForWriting(any())).thenReturn(Mono.just(true));
		return membership;
	}

	private static ThreadMembershipService admittingDirectOwner() {
		ThreadMembershipService membership = mock(ThreadMembershipService.class);
		when(membership.isActiveParticipant(any(), any())).thenReturn(Mono.just(true));
		when(membership.isActiveDirectOwner(any(), any())).thenReturn(Mono.just(true));
		when(membership.kindOf(any())).thenReturn(Mono.just(Optional.of(ThrKind.DIRECT)));
		when(membership.isOpenForWriting(any())).thenReturn(Mono.just(true));
		return membership;
	}

	@Test
	void answersRateLimitedAndKeepsTheConnectionWhenMessagesComeTooFast() throws InterruptedException {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "chatty-user";
		List<String> sent = new CopyOnWriteArrayList<>();
		CountDownLatch rateLimited = new CountDownLatch(1);
		Sinks.Many<WebSocketMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("chatty-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		// 한도가 1이라 두 번째 발화부터 걸린다. 구독은 발화가 아니라 세지 않는다.
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> {
					sent.add(message.getPayloadAsText());
					if (message.getPayloadAsText().contains("\"code\":\"RATE_LIMITED\"")) {
						rateLimited.countDown();
					}
				}).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());
		when(session.receive()).thenReturn(inbound.asFlux());

		Mono<Void> completion = handler(registry, provisioning, 1).handle(session).cache();
		completion.subscribe();
		inbound.tryEmitNext(inboundText(WsTestExchange.subscribeFrame(threadId)));
		inbound.tryEmitNext(inboundText(WsTestExchange.chatMessageFrame(threadId, "first message")));
		inbound.tryEmitNext(inboundText(WsTestExchange.chatMessageFrame(threadId, "second message")));
		assertThat(rateLimited.await(2, TimeUnit.SECONDS)).isTrue();
		inbound.tryEmitComplete();
		completion.block();

		// 초과한 발화에는 RATE_LIMITED가 돌아간다.
		assertThat(sent).anyMatch(text -> text.contains("\"code\":\"RATE_LIMITED\""));
		// 연결은 정상 종료다 — 한도를 넘겼다고 끊지 않는다(이슈 #74). 끊으면 클라이언트가
		// 백오프로 다시 붙어 핸드셰이크 부하로 옮겨갈 뿐이다.
		verify(session, times(1)).close(CloseStatus.NORMAL);
	}

	/**
	* 구독되지 않은 첫 발화는 DIRECT bootstrap 후보다(이슈 #162) — 이 threadId가 이미 COLLAB이거나
	* 남의 것이면 DirectChatTurnService가 404를 던지고, 존재를 드러내지 않으면서 클라이언트의 기존
	* NOT_SUBSCRIBED 재구독 복구를 그대로 태우려고 이 경우도 NOT_SUBSCRIBED로 답한다.
	*/
	@Test
	void answersNotSubscribedWhenDirectBootstrapRejectsAnExistingNonDirectThread() {
		UUID threadId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		var directChatTurnService = mock(DirectChatTurnService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "unsubscribed-user";
		List<String> sent = new CopyOnWriteArrayList<>();

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("unsubscribed-user")).thenReturn(Mono.just(UUID.randomUUID()));
		when(directChatTurnService.prepareOrCreateWithPendingAgentBlocking(any(), any(), any(), any(), any()))
				.thenThrow(new org.springframework.web.server.ResponseStatusException(
						org.springframework.http.HttpStatus.NOT_FOUND));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.chatMessageFrame(threadId, "여기요"))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.add(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		handler(registry, provisioning, MESSAGES_PER_WINDOW, directChatTurnService).handle(session).block();

		assertThat(sent).singleElement().asString()
				.contains("\"code\":\"NOT_SUBSCRIBED\"", "\"threadId\":\"" + threadId + "\"");
	}

	/**
	* 기존 DIRECT 방에 이어 쓰다가 HUMAN·PENDING AGENT 예약 저장 자체가 실패하면(이슈 #162, §2.2)
	* 아직 아무것도 방송되지 않았으므로 요청자에게만 오류를 주는 대신, 방 전체에 warning
	* system.notice(MESSAGE_DELIVERY_FAILED)를 방송해 다른 탭도 배너로 알 수 있게 한다.
	*/
	@Test
	void broadcastsDeliveryFailedNoticeWhenExistingDirectReservationFails() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		var directChatTurnService = mock(DirectChatTurnService.class);
		ThreadMembershipService membership = mock(ThreadMembershipService.class);
		when(membership.isActiveParticipant(any(), any())).thenReturn(Mono.just(true));
		when(membership.kindOf(any())).thenReturn(Mono.just(Optional.of(ThrKind.DIRECT)));
		when(membership.isActiveDirectOwner(any(), any())).thenReturn(Mono.just(true));
		when(membership.isOpenForWriting(any())).thenReturn(Mono.just(true));
		when(directChatTurnService.prepareExistingWithPendingAgentBlocking(eq(threadId), any(), any(), any()))
				.thenThrow(new IllegalStateException("database unavailable"));
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "direct-user";

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("direct-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.subscribeFrame(threadId)),
				inboundText(WsTestExchange.chatMessageFrame(threadId, "안녕"))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(invocation.getArgument(0)).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		UUID observerId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership observer =
				registry.join(threadId, observerId, new PresenceParticipant("observer", "관찰자"));
		CountDownLatch noticeReceived = new CountDownLatch(1);
		AtomicReference<SystemNoticeFrame> notice = new AtomicReference<>();
		var observerSubscription = observer.frames().subscribe(frame -> {
			if (frame instanceof SystemNoticeFrame systemNotice
					&& "MESSAGE_DELIVERY_FAILED".equals(systemNotice.code())) {
				notice.set(systemNotice);
				noticeReceived.countDown();
			}
		});

		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		ThreadMessageDispatcher dispatcher = new ThreadMessageDispatcher(registry, llm,
				mock(MsgPersistenceService.class), "test-model", Duration.ofSeconds(120), 20, 20,
				Schedulers.parallel());
		ThreadWebSocketHandler handler = new ThreadWebSocketHandler(new JsonMapper(), registry, dispatcher,
				provisioning, membership, directChatTurnService, Clock.systemUTC(), WINDOW_SECONDS,
				MESSAGES_PER_WINDOW);

		try {
			handler.handle(session).block();

			assertThat(noticeReceived.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(notice.get().severity()).isEqualTo("warning");
			assertThat(notice.get().threadId()).isEqualTo(threadId);
		} finally {
			observerSubscription.dispose();
			registry.leave(threadId, observerId, new PresenceParticipant("observer", "관찰자"));
		}
	}

	/**
	* 재시도(이슈 #233)가 이미 COMPLETE인 턴을 가리키면(활성 턴도 없다 — 이 테스트의 dispatcher는
	* 방금 만들어져 아무 턴도 진행 중이지 않다) 저장된 최종 답변을 이 커넥션에만 DONE 상태
	* ChatAnswerFrame으로 돌려주고, 새 LLM 호출이나 dispatcher 큐 진입은 없다.
	*/
	@Test
	void replaysTheFinalAnswerWhenTheStoredTurnIsAlreadyComplete() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID humanMsgId = UUID.randomUUID();
		UUID agentMsgId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		var directChatTurnService = mock(DirectChatTurnService.class);
		ThreadMembershipService membership = mock(ThreadMembershipService.class);
		when(membership.isActiveParticipant(any(), any())).thenReturn(Mono.just(true));
		when(membership.kindOf(any())).thenReturn(Mono.just(Optional.of(ThrKind.DIRECT)));
		when(membership.isActiveDirectOwner(any(), any())).thenReturn(Mono.just(true));
		when(membership.isOpenForWriting(any())).thenReturn(Mono.just(true));
		when(directChatTurnService.prepareExistingWithPendingAgentBlocking(eq(threadId), any(), any(), any()))
				.thenReturn(new DirectChatTurnService.StoredTurn(humanMsgId, 0L, agentMsgId, threadId, 1L, true,
						MsgStatus.COMPLETE));
		com.onggijonggi.common.chat.domain.Msg agentMsg = com.onggijonggi.common.chat.domain.Msg
				.pendingAgent(agentMsgId, threadId, 1L);
		agentMsg.complete("이전에 이미 완료된 답변");
		when(directChatTurnService.findAgentMessage(agentMsgId)).thenReturn(Optional.of(agentMsg));
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "direct-user";
		List<String> sent = new CopyOnWriteArrayList<>();

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("direct-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.subscribeFrame(threadId)),
				inboundText(WsTestExchange.chatMessageFrame(threadId, "안녕"))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.add(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		ThreadMessageDispatcher dispatcher = new ThreadMessageDispatcher(registry, llm,
				mock(MsgPersistenceService.class), "test-model", Duration.ofSeconds(120), 20, 20,
				Schedulers.parallel());
		ThreadWebSocketHandler handler = new ThreadWebSocketHandler(new JsonMapper(), registry, dispatcher,
				provisioning, membership, directChatTurnService, Clock.systemUTC(), WINDOW_SECONDS,
				MESSAGES_PER_WINDOW);

		handler.handle(session).block();

		assertThat(sent).anyMatch(frame -> frame.contains("\"type\":\"chat.answer\"")
				&& frame.contains("\"status\":\"done\"") && frame.contains("이전에 이미 완료된 답변")
				&& frame.contains("\"msgId\":\"" + agentMsgId + "\""));
		assertThat(sent).anyMatch(frame -> frame.contains("\"type\":\"chat.message\"")
				&& frame.contains("\"msgId\":\"" + humanMsgId + "\""));
		verify(llm, never()).streamChat(any());
	}

	/**
	* 새 DIRECT 방 동시 생성 경합(이슈 #162, §2.1) — 첫 시도가 PK 충돌로 실패하면
	* `prepareExistingWithPendingAgentBlocking`으로 재조회해 이어쓴다. 재조회까지 성공하면
	* bootstrap이 정상 완료돼 발화가 방송된다.
	*/
	@Test
	void retriesBootstrapWithExistingThreadAfterCreateIdCollision() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		var directChatTurnService = mock(DirectChatTurnService.class);
		when(directChatTurnService.prepareOrCreateWithPendingAgentBlocking(any(), any(), any(), any(), any()))
				.thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
		when(directChatTurnService.prepareExistingWithPendingAgentBlocking(eq(threadId), any(), any(), any()))
				.thenReturn(new DirectChatTurnService.StoredTurn(UUID.randomUUID(), 0L, UUID.randomUUID(), threadId,
						1L, false, MsgStatus.PENDING));
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "collision-user";

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("collision-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.chatMessageFrame(threadId, "안녕"))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(invocation.getArgument(0)).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		UUID observerId = UUID.randomUUID();
		RoomSessionRegistry.RoomMembership observer =
				registry.join(threadId, observerId, new PresenceParticipant("observer", "관찰자"));
		CountDownLatch messageBroadcast = new CountDownLatch(1);
		var observerSubscription = observer.frames().subscribe(frame -> {
			if (frame instanceof ChatMessageFrame chatMessage && "안녕".equals(chatMessage.content())) {
				messageBroadcast.countDown();
			}
		});

		ThreadWebSocketHandler handler = handler(registry, provisioning, MESSAGES_PER_WINDOW, directChatTurnService);

		try {
			handler.handle(session).block();

			assertThat(messageBroadcast.await(1, TimeUnit.SECONDS)).isTrue();
			verify(directChatTurnService).prepareExistingWithPendingAgentBlocking(eq(threadId), eq(userId),
					eq("안녕"), any());
		} finally {
			observerSubscription.dispose();
			registry.leave(threadId, observerId, new PresenceParticipant("observer", "관찰자"));
		}
	}

	/**
	* bootstrap이 성공하면 그 연결을 presence 없는 경로로 자동 구독한다(이슈 #162, §2.2) — 같은
	* 방의 다른 구독자는 이 연결의 발화를 받지만 presence.join은 전혀 받지 않는다.
	*/
	@Test
	void bootstrapSubscribesWithoutPresenceOnSuccess() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		var directChatTurnService = mock(DirectChatTurnService.class);
		when(directChatTurnService.prepareOrCreateWithPendingAgentBlocking(eq(threadId), eq(userId), eq("안녕"),
				any(), any())).thenReturn(new DirectChatTurnService.StoredTurn(UUID.randomUUID(), 0L,
				UUID.randomUUID(), threadId, 1L, false, MsgStatus.PENDING));
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "bootstrap-user";

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("bootstrap-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.chatMessageFrame(threadId, "안녕"))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(invocation.getArgument(0)).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		UUID observerId = UUID.randomUUID();
		// 방 상태(presenceEnabled)는 최초 생성자가 정하고 이후 join은 덮어쓰지 않는다
		// (RoomSessionRegistry.join) — 이 관찰자도 같은 DIRECT 방의 다른 탭이라고 보고
		// bootstrap과 똑같이 presence 없는 경로로 들어가야 방이 처음부터 올바른 상태로 만들어진다.
		RoomSessionRegistry.RoomMembership observer = registry.join(threadId, observerId,
				new PresenceParticipant("observer", "관찰자"), false);
		CountDownLatch messageBroadcast = new CountDownLatch(1);
		AtomicInteger presenceJoins = new AtomicInteger();
		var observerSubscription = observer.frames().subscribe(frame -> {
			if (frame instanceof ChatMessageFrame chatMessage && "안녕".equals(chatMessage.content())) {
				messageBroadcast.countDown();
			}
			if (frame instanceof PresenceJoinFrame) {
				presenceJoins.incrementAndGet();
			}
		});

		ThreadWebSocketHandler handler = handler(registry, provisioning, MESSAGES_PER_WINDOW, directChatTurnService);

		try {
			handler.handle(session).block();

			// 자동 구독이 실제로 걸렸다는 것은 방금 붙은 이 연결의 발화가 기존 구독자(observer)에게
			// broadcastIfCurrent로 도달한다는 것으로 확인한다 — 구독이 안 됐다면 generation이 안 맞아
			// 방송 자체가 무시된다.
			assertThat(messageBroadcast.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(presenceJoins.get()).isZero();
		} finally {
			observerSubscription.dispose();
			registry.leave(threadId, observerId, new PresenceParticipant("observer", "관찰자"));
		}
	}

	@Test
	void reconnectingToAnExistingDirectRoomSubscribesWithoutPresence() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		ThreadMembershipService membership = admittingDirectOwner();
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		List<String> sent = new CopyOnWriteArrayList<>();

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just((Principal) () -> "direct-owner"));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("direct-owner")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.subscribeFrame(threadId))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.add(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		ThreadMessageDispatcher dispatcher = new ThreadMessageDispatcher(registry, llm,
				mock(MsgPersistenceService.class), "test-model", Duration.ofSeconds(120), 20, 20,
				Schedulers.parallel());
		ThreadWebSocketHandler handler = new ThreadWebSocketHandler(new JsonMapper(), registry, dispatcher,
				provisioning, membership, mock(DirectChatTurnService.class), Clock.systemUTC(), WINDOW_SECONDS,
				MESSAGES_PER_WINDOW);

		handler.handle(session).block();

		assertThat(sent).noneMatch(frame -> frame.contains("\"type\":\"presence."));
	}

	@Test
	void rejectsDirectBootstrapWithoutClientIdsBeforePersisting() {
		UUID threadId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		var directChatTurnService = mock(DirectChatTurnService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		List<String> sent = new CopyOnWriteArrayList<>();

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just((Principal) () -> "draft-owner"));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("draft-owner")).thenReturn(Mono.just(UUID.randomUUID()));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText("{\"type\":\"chat.message\",\"threadId\":\""
				+ threadId + "\",\"content\":\"hello\"}")));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.add(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		handler(registry, provisioning, MESSAGES_PER_WINDOW, directChatTurnService).handle(session).block();

		assertThat(sent).singleElement().asString().contains("\"code\":\"MALFORMED_REQUEST\"");
		verify(directChatTurnService, never()).prepareOrCreateWithPendingAgentBlocking(any(), any(), any(), any(),
				any());
	}

	/** 클라이언트가 올려보내는 텍스트 프레임 한 장. */
	private static WebSocketMessage inboundText(String json) {
		return new WebSocketMessage(WebSocketMessage.Type.TEXT,
				DefaultDataBufferFactory.sharedInstance.wrap(
						json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	/** 나가는 프레임을 실제 메시지로 만들게 한다 — mock의 기본값(null)이면 직렬화 단계에서 NPE가 난다. */
	private static void stubTextMessages(WebSocketSession session) {
		when(session.textMessage(anyString())).thenAnswer(invocation -> new WebSocketMessage(
				WebSocketMessage.Type.TEXT, DefaultDataBufferFactory.sharedInstance.wrap(
						invocation.<String>getArgument(0).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
	}

	private static ThreadWebSocketHandler handler(RoomSessionRegistry registry,
			com.onggijonggi.api.auth.UserIdentityService provisioning) {
		return handler(registry, provisioning, MESSAGES_PER_WINDOW);
	}

	/** 레이트리밋(#74)이 관심사인 테스트만 한도를 좁혀 준다. */
	private static ThreadWebSocketHandler handler(RoomSessionRegistry registry,
			com.onggijonggi.api.auth.UserIdentityService provisioning, int messagesPerWindow) {
		return handler(registry, provisioning, messagesPerWindow, mock(DirectChatTurnService.class));
	}

	/** DIRECT bootstrap(이슈 #162)이 관심사인 테스트만 이 서비스를 직접 stub한다. */
	private static ThreadWebSocketHandler handler(RoomSessionRegistry registry,
			com.onggijonggi.api.auth.UserIdentityService provisioning, int messagesPerWindow,
			DirectChatTurnService directChatTurnService) {
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		ThreadMessageDispatcher dispatcher = new ThreadMessageDispatcher(registry, llm,
				mock(MsgPersistenceService.class), "test-model", Duration.ofSeconds(120), 20, 20,
				Schedulers.parallel());
		return new ThreadWebSocketHandler(new JsonMapper(), registry, dispatcher, provisioning,
				admittingMembership(), directChatTurnService, Clock.systemUTC(), WINDOW_SECONDS, messagesPerWindow);
	}

}
