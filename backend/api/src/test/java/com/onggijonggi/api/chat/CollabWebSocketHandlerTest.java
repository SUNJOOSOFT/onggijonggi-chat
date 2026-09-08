package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Class Name : CollabWebSocketHandlerTest.java
 * Description : 이슈 #3 — /api/ws/{threadId}의 실제 프로덕션 배선(WsSecurityConfig +
 *               CollabWebSocketHandler)을 서브프로토콜 인증 기준으로 검증한다. 이슈 #7 스파이크가
 *               확인했던 "인증 컨텍스트가 메시지 루프까지 전파된다"는 사실을, Authorization 헤더가
 *               아니라 Sec-WebSocket-Protocol 조건에서 다시 확인한다.
 *               이슈 #16이 방 단위 방송을 들이면서, 같은 방의 두 실제 클라이언트가 한 메시지를
 *               함께 받는지와 잘못된 threadId를 거르는지도 여기서 함께 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ChatControllerTest.FakeChatModelConfig.class, FakeJwtDecoderConfig.class, CollabRoomFixture.class})
@ExtendWith(ThreadDumpOnStallExtension.class)
class CollabWebSocketHandlerTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	private final ObjectMapper objectMapper = new JsonMapper();

	@LocalServerPort
	private int port;

	@Autowired
	private CollabRoomFixture.CollabRooms rooms;

	@Test
	void broadcastsAValidatedFrameBackToTheAuthenticatedSender() throws Exception {
		UUID threadId = rooms.openRoom("collab-ws-user");
		String received = exchange("collab-ws-user", threadId,
				List.of("""
						{"type":"chat.message","content":"hello","sessionId":"ignored","from":"ignored"}
						"""), 1).get(0);

		WsFrame frame = objectMapper.readValue(received, WsFrame.class);

		assertThat(frame).isInstanceOfSatisfying(ChatMessageFrame.class, message -> {
			assertThat(message.sessionId()).isEqualTo(threadId);
			assertThat(message.from()).isNotNull();
			assertThat(message.content()).isEqualTo("hello");
		});
		assertThat(received).doesNotContain("connected:", "presence.join");
	}

	/** LOCKED로 바뀐 방은 새 메시지를 받지 않는다(#131) — 연결은 유지된 채 프레임만 거부된다. */
	@Test
	void rejectsMessagesToALockedThread() throws Exception {
		UUID threadId = rooms.openRoom("locked-ws-user");
		rooms.lockRoom(threadId);

		String received = exchange("locked-ws-user", threadId,
				List.of("""
						{"type":"chat.message","content":"should not send"}
						"""), 1).get(0);

		ErrorFrame error = (ErrorFrame) objectMapper.readValue(received, WsFrame.class);
		assertThat(error.sessionId()).isEqualTo(threadId);
		assertThat(error.code()).isEqualTo("THREAD_LOCKED");
	}

	@Test
	void keepsConnectionAfterMalformedFrameAndUsesDistinctTraceIds() throws Exception {
		UUID threadId = rooms.openRoom("malformed-user");
		List<String> received = exchange("malformed-user", threadId,
				List.of("not-json", "{\"type\":\"chat.message\",\"content\":\"   \"}",
						"{\"type\":\"unknown\",\"content\":\"ignored\"}",
						"{\"type\":\"chat.message\",\"content\":\"valid\"}"), 4);

		ErrorFrame first = (ErrorFrame) objectMapper.readValue(received.get(0), WsFrame.class);
		ErrorFrame second = (ErrorFrame) objectMapper.readValue(received.get(1), WsFrame.class);
		ErrorFrame third = (ErrorFrame) objectMapper.readValue(received.get(2), WsFrame.class);
		ChatMessageFrame valid = (ChatMessageFrame) objectMapper.readValue(received.get(3), WsFrame.class);

		assertThat(first.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(second.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(third.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(List.of(first.traceId(), second.traceId(), third.traceId())).doesNotHaveDuplicates();
		assertThat(first.traceId()).isNotBlank();
		assertThat(valid.content()).isEqualTo("valid");
	}

	@Test
	void ignoresKnownServerOnlyFrameTypes() throws Exception {
		UUID threadId = rooms.openRoom("server-frame-user");
		List<String> received = exchange("server-frame-user", threadId,
				List.of("{\"type\":\"presence.join\",\"sessionId\":\"" + threadId + "\"}",
						"{\"type\":\"presence.leave\",\"sessionId\":\"" + threadId + "\"}",
						"{\"type\":\"chat.message\",\"content\":\"accepted\"}"), 1);

		ChatMessageFrame frame = (ChatMessageFrame) objectMapper.readValue(received.get(0), WsFrame.class);
		assertThat(frame.content()).isEqualTo("accepted");
	}

	@Test
	void rejectsBinaryFramesWithoutClosingTheConnection() throws Exception {
		UUID threadId = rooms.openRoom("binary-user");
		String token = TestJwtSupport.signedJwt("binary-user", List.of("USER"));
		List<String> received = new CopyOnWriteArrayList<>();

		new ReactorNettyWebSocketClient()
				.execute(wsUri(threadId), allowedHeaders(), protocolHandler(token, session -> {
					return WsTestExchange.exchange(session, active -> Flux.just(
							active.binaryMessage(factory -> factory.wrap(new byte[] {1, 2, 3})),
							active.textMessage("{\"type\":\"chat.message\",\"content\":\"after binary\"}")),
							2, message -> received.add(message.getPayloadAsText()));
				}))
				.block(WsTestTimeouts.BLOCK);

		ErrorFrame error = (ErrorFrame) objectMapper.readValue(received.get(0), WsFrame.class);
		ChatMessageFrame valid = (ChatMessageFrame) objectMapper.readValue(received.get(1), WsFrame.class);
		assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(valid.content()).isEqualTo("after binary");
	}

	@Test
	void broadcastsOneMessageToTwoRealClientsInTheSameRoom() throws Exception {
		UUID threadId = rooms.openRoom("room-user-1", "room-user-2");
		Sinks.Many<String> firstOutbound = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<String> secondOutbound = Sinks.many().unicast().onBackpressureBuffer();
		List<String> firstReceived = new CopyOnWriteArrayList<>();
		List<String> secondReceived = new CopyOnWriteArrayList<>();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch received = new CountDownLatch(2);
		CountDownLatch completed = new CountDownLatch(2);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		// 두 클라이언트는 chat.message만 센다. 서버의 방 등록 순서에 따라 서로의 presence.join을
		// 받을 수도 있는데, 이 테스트가 보는 것은 방송이라 그 프레임에 기대면 안 된다(이슈 #25).
		Disposable first = openClient("room-user-1", threadId, firstOutbound,
				firstReceived, ready, received, completed, failure);
		Disposable second = openClient("room-user-2", threadId, secondOutbound,
				secondReceived, ready, received, completed, failure);

		try {
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			firstOutbound.tryEmitNext("{\"type\":\"chat.message\",\"content\":\"for everyone\"}");
			assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();

			ChatMessageFrame firstFrame = (ChatMessageFrame) objectMapper.readValue(firstReceived.get(0), WsFrame.class);
			ChatMessageFrame secondFrame = (ChatMessageFrame) objectMapper.readValue(secondReceived.get(0), WsFrame.class);
			assertThat(firstFrame).isEqualTo(secondFrame);
			assertThat(firstFrame.sessionId()).isEqualTo(threadId);
		} finally {
			firstOutbound.tryEmitComplete();
			secondOutbound.tryEmitComplete();
			first.dispose();
			second.dispose();
		}
	}

	@Test
	void announcesPresenceLeaveToTheMemberStillInTheRoom() throws Exception {
		UUID threadId = rooms.openRoom("presence-staying", "presence-leaving");
		Sinks.Many<String> stayingOutbound = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<String> leavingOutbound = Sinks.many().unicast().onBackpressureBuffer();
		List<String> stayingReceived = new CopyOnWriteArrayList<>();
		List<String> leavingReceived = new CopyOnWriteArrayList<>();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch stayingReceivedBoth = new CountDownLatch(2);
		CountDownLatch leavingReceivedOne = new CountDownLatch(1);
		CountDownLatch completed = new CountDownLatch(2);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		// 남는 쪽은 나가는 사람의 메시지와 퇴장 통보를 차례로 받는다. presence.join은 세지 않는다 —
		// 서버가 언제 방에 등록하는지 클라이언트가 알 수 없어 도착 여부가 정해지지 않는다.
		// 입장 통보 자체는 RoomSessionRegistryTest가 결정적으로 검증한다.
		Disposable staying = openClient("presence-staying", threadId, stayingOutbound, stayingReceived,
				ready, stayingReceivedBoth, completed, failure, 2,
				frameTypes("chat.message", "presence.leave"));
		Disposable leaving = openClient("presence-leaving", threadId, leavingOutbound, leavingReceived,
				ready, leavingReceivedOne, completed, failure, 1, frameTypes("chat.message"));

		try {
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

			// 이 메시지가 남는 쪽에 닿았다는 것이 곧 "둘 다 방에 등록됐다"는 증거다. 그 뒤에야
			// 나가는 쪽을 끊어야 퇴장 통보를 받을 상대가 있다고 확신할 수 있다.
			leavingOutbound.tryEmitNext("{\"type\":\"chat.message\",\"content\":\"before leaving\"}");
			assertThat(leavingReceivedOne.await(5, TimeUnit.SECONDS)).isTrue();

			leavingOutbound.tryEmitComplete();
			leaving.dispose();

			assertThat(stayingReceivedBoth.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();

			ChatMessageFrame message =
					(ChatMessageFrame) objectMapper.readValue(stayingReceived.get(0), WsFrame.class);
			PresenceLeaveFrame left =
					(PresenceLeaveFrame) objectMapper.readValue(stayingReceived.get(1), WsFrame.class);

			assertThat(left.sessionId()).isEqualTo(threadId);
			// 방금 말하고 나간 그 사람이다.
			assertThat(left.userId()).isEqualTo(message.from());
		} finally {
			stayingOutbound.tryEmitComplete();
			leavingOutbound.tryEmitComplete();
			staying.dispose();
			leaving.dispose();
		}
	}

	@Test
	void sendsForbiddenAndClosesNormallyWhenUserIsNotAParticipant() throws Exception {
		UUID threadId = rooms.openRoom("room-owner-user");
		String token = TestJwtSupport.signedJwt("outsider-user", List.of("USER"));
		AtomicReference<String> received = new AtomicReference<>();
		AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();

		new ReactorNettyWebSocketClient()
				.execute(wsUri(threadId), allowedHeaders(), new WebSocketHandler() {
					@Override
					public List<String> getSubProtocols() {
						return List.of("access_token", token);
					}

					@Override
					public Mono<Void> handle(WebSocketSession session) {
						return session.receive()
								.doOnNext(message -> received.set(message.getPayloadAsText()))
								.then(session.closeStatus().doOnNext(closeStatus::set))
								.then();
					}
				})
				.block(WsTestTimeouts.BLOCK);

		ErrorFrame error = (ErrorFrame) objectMapper.readValue(received.get(), WsFrame.class);
		assertThat(error.sessionId()).isEqualTo(threadId);
		assertThat(error.code()).isEqualTo("FORBIDDEN");
		// 재연결해도 같은 거부라, 프론트가 루프를 멈출 수 있게 정상 종료로 닫는다.
		assertThat(closeStatus.get().getCode()).isEqualTo(1000);
	}

	@Test
	void sendsMalformedRequestAndClosesNormallyForInvalidThreadId() throws Exception {
		String token = TestJwtSupport.signedJwt("invalid-thread-user", List.of("USER"));
		HttpHeaders headers = allowedHeaders();
		AtomicReference<String> received = new AtomicReference<>();
		AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();

		new ReactorNettyWebSocketClient()
				.execute(URI.create("ws://localhost:" + port + "/api/ws/not-a-uuid"), headers, new WebSocketHandler() {
					@Override
					public List<String> getSubProtocols() {
						return List.of("access_token", token);
					}

					@Override
					public Mono<Void> handle(WebSocketSession session) {
						return session.receive()
								.doOnNext(message -> received.set(message.getPayloadAsText()))
								.then(session.closeStatus().doOnNext(closeStatus::set))
								.then();
					}
				})
				.block(WsTestTimeouts.BLOCK);

		ErrorFrame error = (ErrorFrame) objectMapper.readValue(received.get(), WsFrame.class);
		assertThat(error.sessionId()).isNull();
		assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(closeStatus.get().getCode()).isEqualTo(1000);
	}

	/**
	* 입장 인가는 연결할 때 한 번뿐이라, 연결을 유지한 채 참가자에서 빠진 사람이 계속 말할 수
	* 있었다(이슈 #20). 두 번 보내고 두 번 다 거부당하는 것으로, 첫 거부가 연결을 닫지 않았다는
	* 것까지 함께 본다 — 이미 열린 연결로 방송이 계속 가는 것(받기)은 이슈 #135 몫이다.
	*/
	@Test
	void refusesMessagesFromSomeoneRemovedWhileStillConnected() throws Exception {
		UUID threadId = rooms.openRoom("revoked-ws-owner", "revoked-ws-member");
		Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch ready = new CountDownLatch(1);
		CountDownLatch refusedTwice = new CountDownLatch(2);
		CountDownLatch completed = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Disposable member = openClient("revoked-ws-member", threadId, outbound, received,
				ready, refusedTwice, completed, failure, 2, frameTypes("error"));

		try {
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			rooms.endParticipation(threadId, "revoked-ws-member", ThrMbrStatus.REVOKED, "OWNER_REVOKED");

			outbound.tryEmitNext("{\"type\":\"chat.message\",\"content\":\"still here?\"}");
			outbound.tryEmitNext("{\"type\":\"chat.message\",\"content\":\"and again\"}");

			assertThat(refusedTwice.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();

			ErrorFrame first = (ErrorFrame) objectMapper.readValue(received.get(0), WsFrame.class);
			assertThat(first.code()).isEqualTo("FORBIDDEN");
			assertThat(received).hasSize(2);
		} finally {
			outbound.tryEmitComplete();
			member.dispose();
		}
	}

	@Test
	void closesConnectionWithCode1000WhenClientClosesNormally() {
		UUID threadId = UUID.randomUUID();
		String token = TestJwtSupport.signedJwt("normal-close-user", List.of("USER"));
		AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient(
				HttpClient.create(ConnectionProvider.newConnection()));

		client.execute(wsUri(threadId), allowedHeaders(), new WebSocketHandler() {
			@Override
			public List<String> getSubProtocols() {
				return List.of("access_token", token);
			}

			@Override
			public Mono<Void> handle(WebSocketSession session) {
				return Mono.when(session.receive().then(),
						session.close(), session.closeStatus().doOnNext(closeStatus::set).then());
			}
		}).block(WsTestTimeouts.BLOCK);

		assertThat(closeStatus.get().getCode()).isEqualTo(1000);
	}

	@Test
	void rejectsHandshakeWithoutSubProtocolAndRejectsTheOldPath() {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

		assertThatThrownBy(() -> client.execute(wsUri(UUID.randomUUID()), allowedHeaders(), session -> Mono.empty())
				.block(WsTestTimeouts.BLOCK))
				.isInstanceOf(WebSocketClientHandshakeException.class)
				.hasMessageContaining("401");

		String token = TestJwtSupport.signedJwt("old-path-user", List.of("USER"));
		assertThatThrownBy(() -> client.execute(URI.create("ws://localhost:" + port + "/api/ws"),
				allowedHeaders(), protocolHandler(token, session -> Mono.empty())).block(WsTestTimeouts.BLOCK))
				.isInstanceOf(WebSocketClientHandshakeException.class);

		assertThatThrownBy(() -> client.execute(
				URI.create("ws://localhost:" + port + "/api/ws/" + UUID.randomUUID() + "/extra"),
				allowedHeaders(), protocolHandler(token, session -> Mono.empty())).block(WsTestTimeouts.BLOCK))
				.isInstanceOf(WebSocketClientHandshakeException.class);
	}

	private List<String> exchange(String subject, UUID threadId, List<String> outbound, int expectedFrames) {
		String token = TestJwtSupport.signedJwt(subject, List.of("USER"));
		List<String> received = new CopyOnWriteArrayList<>();

		new ReactorNettyWebSocketClient()
				.execute(wsUri(threadId), allowedHeaders(), protocolHandler(token, session -> {
					return WsTestExchange.exchange(session,
							active -> Flux.fromIterable(outbound).map(active::textMessage), expectedFrames,
							message -> received.add(message.getPayloadAsText()));
				}))
				.block(WsTestTimeouts.BLOCK);

		return received;
	}

	private Disposable openClient(String subject, UUID threadId, Sinks.Many<String> outbound,
			List<String> frames, CountDownLatch ready, CountDownLatch received,
			CountDownLatch completed, AtomicReference<Throwable> failure) {
		return openClient(subject, threadId, outbound, frames, ready, received, completed, failure, 1,
				frameTypes("chat.message"));
	}

	private Disposable openClient(String subject, UUID threadId, Sinks.Many<String> outbound,
			List<String> frames, CountDownLatch ready, CountDownLatch received,
			CountDownLatch completed, AtomicReference<Throwable> failure, long expectedFrames,
			Predicate<WebSocketMessage> interesting) {
		String token = TestJwtSupport.signedJwt(subject, List.of("USER"));
		return new ReactorNettyWebSocketClient()
				.execute(wsUri(threadId), allowedHeaders(), protocolHandler(token, session -> {
					return WsTestExchange.exchange(session,
							active -> outbound.asFlux().map(active::textMessage), expectedFrames,
							message -> {
								frames.add(message.getPayloadAsText());
								received.countDown();
							}, ready::countDown, interesting)
							.doFinally(ignored -> outbound.tryEmitComplete())
							.then(session.close(CloseStatus.NORMAL));
				}))
				.doOnError(error -> failure.compareAndSet(null, error))
				.doFinally(ignored -> completed.countDown())
				.subscribe();
	}

	/** 서버의 방 등록 순서를 클라이언트가 알 수 없으므로, 각 테스트는 자기가 볼 프레임만 센다. */
	private static Predicate<WebSocketMessage> frameTypes(String... types) {
		return message -> {
			String payload = message.getPayloadAsText();
			for (String type : types) {
				if (payload.contains("\"type\":\"" + type + "\"")) {
					return true;
				}
			}
			return false;
		};
	}

	private WebSocketHandler protocolHandler(String token,
			java.util.function.Function<WebSocketSession, Mono<Void>> body) {
		return new WebSocketHandler() {
			@Override
			public List<String> getSubProtocols() {
				return List.of("access_token", token);
			}

			@Override
			public Mono<Void> handle(WebSocketSession session) {
				return body.apply(session);
			}
		};
	}

	private URI wsUri(UUID threadId) {
		return URI.create("ws://localhost:" + port + "/api/ws/" + threadId);
	}

	private static HttpHeaders allowedHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setOrigin(ALLOWED_ORIGIN);
		return headers;
	}

}
