package com.onggijonggi.api.chat;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Class Name : CollabWebSocketHandlerUnitTest.java
 * Description : `WebSocketSession`을 mock으로 대체해 `CollabWebSocketHandler`의 구독·종료
 *               경로만 좁게 검증한다. receive/send가 정확히 한 번씩만 구독되는지, 사용자
 *               프로비저닝 실패 시 INTERNAL_ERROR 후 정상 종료하는지, JWT 만료 시 4000으로 닫는지,
 *               방 버퍼가 넘칠 때 커넥션은 두고 그 방 구독만 푸는지(이슈 #161)를 실제 소켓 없이 확인한다.
 */
class CollabWebSocketHandlerUnitTest {

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

		CollabWebSocketHandler handler = handler(registry, provisioning);

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

		CollabWebSocketHandler handler = handler(registry, provisioning);

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

		CollabWebSocketHandler handler = handler(registry, provisioning);

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
		CollabWebSocketHandler handler = handler(registry, provisioning);
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
		return membership;
	}

	@Test
	void answersRateLimitedAndKeepsTheConnectionWhenMessagesComeTooFast() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "chatty-user";
		List<String> sent = new CopyOnWriteArrayList<>();

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("chatty-user")).thenReturn(Mono.just(userId));
		stubTextMessages(session);
		// 한도가 1이라 두 번째 발화부터 걸린다. 구독은 발화가 아니라 세지 않는다.
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.subscribeFrame(threadId)),
				inboundText(WsTestExchange.chatMessageFrame(threadId, "첫 발화")),
				inboundText(WsTestExchange.chatMessageFrame(threadId, "둘째 발화"))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.add(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		handler(registry, provisioning, 1).handle(session).block();

		// 초과한 발화에는 RATE_LIMITED가 돌아간다.
		assertThat(sent).anyMatch(text -> text.contains("\"code\":\"RATE_LIMITED\""));
		// 연결은 정상 종료다 — 한도를 넘겼다고 끊지 않는다(이슈 #74). 끊으면 클라이언트가
		// 백오프로 다시 붙어 핸드셰이크 부하로 옮겨갈 뿐이다.
		verify(session, times(1)).close(CloseStatus.NORMAL);
	}

	/** 구독하지 않은 방으로 온 발화는 방송하지 않고 NOT_SUBSCRIBED로 돌려준다(이슈 #161). */
	@Test
	void answersNotSubscribedForAMessageToARoomThisConnectionDidNotSubscribe() {
		UUID threadId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry(Duration.ofMillis(50));
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "unsubscribed-user";
		List<String> sent = new CopyOnWriteArrayList<>();

		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("unsubscribed-user")).thenReturn(Mono.just(UUID.randomUUID()));
		stubTextMessages(session);
		when(session.receive()).thenReturn(Flux.just(inboundText(WsTestExchange.chatMessageFrame(threadId, "여기요"))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.add(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		handler(registry, provisioning).handle(session).block();

		assertThat(sent).singleElement().asString()
				.contains("\"code\":\"NOT_SUBSCRIBED\"", "\"threadId\":\"" + threadId + "\"");
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

	private static CollabWebSocketHandler handler(RoomSessionRegistry registry,
			com.onggijonggi.api.auth.UserIdentityService provisioning) {
		return handler(registry, provisioning, MESSAGES_PER_WINDOW);
	}

	/** 레이트리밋(#74)이 관심사인 테스트만 한도를 좁혀 준다. */
	private static CollabWebSocketHandler handler(RoomSessionRegistry registry,
			com.onggijonggi.api.auth.UserIdentityService provisioning, int messagesPerWindow) {
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		CollabMessageDispatcher dispatcher = new CollabMessageDispatcher(registry, llm,
				mock(MsgPersistenceService.class), new CollabCitationSearchService(), "test-model",
				Duration.ofSeconds(120), 20, 20, Schedulers.parallel());
		return new CollabWebSocketHandler(new JsonMapper(), registry, dispatcher, provisioning,
				admittingMembership(), Clock.systemUTC(), WINDOW_SECONDS, messagesPerWindow);
	}

}
