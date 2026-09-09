package com.onggijonggi.api.chat;

import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Class Name : CollabWebSocketHandlerUnitTest.java
 * Description : `WebSocketSession`을 mock으로 대체해 `CollabWebSocketHandler`의 구독·종료
 *               경로만 좁게 검증한다. receive/send가 정확히 한 번씩만 구독되는지, 사용자
 *               프로비저닝 실패 시 INTERNAL_ERROR 후 정상 종료하는지, JWT 만료 시 4000으로,
 *               느린 소비자의 outbound 버퍼가 넘칠 때 1011로 닫는지를 실제 소켓 없이 확인한다.
 */
class CollabWebSocketHandlerUnitTest {

	@Test
	void subscribesToReceiveAndSendExactlyOnce() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		AtomicInteger receiveSubscriptions = new AtomicInteger();
		RoomSessionRegistry registry = new RoomSessionRegistry();
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "ws-user";

		when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/api/ws/" + threadId));
		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("ws-user")).thenReturn(Mono.just(userId));
		// 연결이 붙는 순간 참여자 스냅샷(이슈 #26)이 한 장 나가므로 textMessage가 실제 값을
		// 돌려줘야 한다 — mock의 기본값(null)이면 직렬화 단계에서 NPE가 난다.
		when(session.textMessage(anyString())).thenAnswer(invocation -> new WebSocketMessage(
				WebSocketMessage.Type.TEXT, DefaultDataBufferFactory.sharedInstance.wrap(
						invocation.<String>getArgument(0).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
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

	@Test
	void sendsInternalErrorAndClosesNormallyWhenUserProvisioningFails() {
		UUID threadId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry();
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "failed-user";
		AtomicReference<String> sent = new AtomicReference<>();

		when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/api/ws/" + threadId));
		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("failed-user")).thenReturn(Mono.error(new RuntimeException("db down")));
		when(session.textMessage(anyString())).thenAnswer(invocation -> new WebSocketMessage(
				WebSocketMessage.Type.TEXT, DefaultDataBufferFactory.sharedInstance.wrap(
						invocation.<String>getArgument(0).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
		when(session.send(any())).thenAnswer(invocation -> Flux.from(
				invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
				.doOnNext(message -> sent.set(message.getPayloadAsText())).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		CollabWebSocketHandler handler = handler(registry, provisioning);

		handler.handle(session).block();

		assertThat(sent.get()).contains("\"type\":\"error\"", "\"code\":\"INTERNAL_ERROR\"",
				"\"sessionId\":\"" + threadId + "\"");
		verify(session).close(CloseStatus.NORMAL);
	}

	@Test
	void closesWithCode4000WhenTheJwtExpires() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry();
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		JwtAuthenticationToken authentication = mock(JwtAuthenticationToken.class);
		Jwt jwt = mock(Jwt.class);

		when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/api/ws/" + threadId));
		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(authentication));
		when(authentication.getToken()).thenReturn(jwt);
		when(jwt.getSubject()).thenReturn("expiring-user");
		when(jwt.getExpiresAt()).thenReturn(Instant.now().plusMillis(50));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("expiring-user")).thenReturn(Mono.just(userId));
		// 연결이 붙는 순간 참여자 스냅샷(이슈 #26)이 한 장 나가므로 textMessage가 실제 값을
		// 돌려줘야 한다 — mock의 기본값(null)이면 직렬화 단계에서 NPE가 난다.
		when(session.textMessage(anyString())).thenAnswer(invocation -> new WebSocketMessage(
				WebSocketMessage.Type.TEXT, DefaultDataBufferFactory.sharedInstance.wrap(
						invocation.<String>getArgument(0).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
		when(session.receive()).thenReturn(Flux.never());
		when(session.send(any())).thenAnswer(invocation -> Flux.from(invocation.getArgument(0)).then());
		when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

		CollabWebSocketHandler handler = handler(registry, provisioning);

		handler.handle(session).block(java.time.Duration.ofSeconds(1));

		verify(session).close(argThat(status -> status.getCode() == 4000));
	}

	@Test
	void closesTheSlowConsumerWithCode1011WhenItsOutboundBufferOverflows() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		RoomSessionRegistry registry = new RoomSessionRegistry();
		var provisioning = mock(com.onggijonggi.api.auth.UserIdentityService.class);
		WebSocketSession session = mock(WebSocketSession.class);
		HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
		Principal principal = () -> "slow-user";
		CountDownLatch sendSubscribed = new CountDownLatch(1);
		CountDownLatch closed = new CountDownLatch(1);
		AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();

		when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost/api/ws/" + threadId));
		when(handshakeInfo.getPrincipal()).thenReturn(Mono.just(principal));
		when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
		when(provisioning.resolveOrProvision("slow-user")).thenReturn(Mono.just(userId));
		when(session.receive()).thenReturn(Flux.never());
		when(session.send(any())).thenAnswer(invocation -> {
			Flux.from(invocation.<org.reactivestreams.Publisher<WebSocketMessage>>getArgument(0))
					.subscribe(new BaseSubscriber<>() {
						@Override
						protected void hookOnSubscribe(Subscription subscription) {
							sendSubscribed.countDown();
							// 느린 네트워크 write를 재현한다. 의도적으로 demand를 요청하지 않는다.
						}
					});
			return Mono.never();
		});
		when(session.close(any(CloseStatus.class))).thenAnswer(invocation -> {
			CloseStatus status = invocation.getArgument(0);
			return Mono.fromRunnable(() -> {
				closeStatus.set(status);
				closed.countDown();
			});
		});

		UUID observerId = UUID.randomUUID();
		PresenceParticipant observerParticipant =
				new PresenceParticipant("observer", "지켜보는 사람");
		RoomSessionRegistry.RoomMembership observer = registry.join(threadId, observerId, observerParticipant);
		var observerSubscription = observer.frames().subscribe();
		CollabWebSocketHandler handler = handler(registry, provisioning);
		var handlerSubscription = handler.handle(session).subscribe();
		try {
			assertThat(sendSubscribed.await(1, TimeUnit.SECONDS)).isTrue();
			for (int i = 0; i <= 512 && closed.getCount() > 0; i++) {
				registry.broadcastIfCurrent(threadId, observer.generation(),
						new ChatMessageFrame(threadId, "slow-user", "느린 소비자", "message-" + i));
			}

			assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(closeStatus.get().getCode()).isEqualTo(1011);
			verify(session, times(1)).close(argThat(status -> status.getCode() == 1011));
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

	private static CollabWebSocketHandler handler(RoomSessionRegistry registry,
			com.onggijonggi.api.auth.UserIdentityService provisioning) {
		LlmChatStreamService llm = mock(LlmChatStreamService.class);
		when(llm.streamChat(any())).thenReturn(Flux.never());
		CollabMessageDispatcher dispatcher = new CollabMessageDispatcher(registry, llm,
				mock(MsgPersistenceService.class), "test-model", Duration.ofSeconds(120), 20, 20,
				Schedulers.parallel());
		return new CollabWebSocketHandler(new JsonMapper(), registry, dispatcher, provisioning,
				admittingMembership());
	}

}
