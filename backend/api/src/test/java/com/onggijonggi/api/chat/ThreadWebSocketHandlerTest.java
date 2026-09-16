package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
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
 * Class Name : ThreadWebSocketHandlerTest.java
 * Description : 이슈 #3 — /api/ws의 실제 프로덕션 배선(WsSecurityConfig + ThreadWebSocketHandler)을
 *               서브프로토콜 인증 기준으로 검증한다. 이슈 #7 스파이크가 확인했던 "인증 컨텍스트가
 *               메시지 루프까지 전파된다"는 사실을, Authorization 헤더가 아니라 Sec-WebSocket-Protocol
 *               조건에서 다시 확인한다.
 *               이슈 #16이 방 단위 방송을 들이면서, 같은 방의 두 실제 클라이언트가 한 메시지를
 *               함께 받는지도 여기서 함께 본다. 이슈 #161로 커넥션 하나가 room.subscribe로 여러 방을
 *               나르게 되어, 모든 교환이 구독으로 시작하고 방 하나의 거부·강제 해지가 커넥션을 닫지
 *               않는지도 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, CollabRoomFixture.class,
		FakeKeycloakAdminConfig.class})
@ExtendWith(ThreadDumpOnStallExtension.class)
class ThreadWebSocketHandlerTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	private final ObjectMapper objectMapper = new JsonMapper();

	@LocalServerPort
	private int port;

	@Autowired
	private CollabRoomFixture.CollabRooms rooms;

	@Autowired
	private RoomSessionRegistry roomSessionRegistry;

	private RestTestClient restTestClient;

	@BeforeEach
	void setUp() {
		restTestClient = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void broadcastsAValidatedFrameBackToTheAuthenticatedSender() throws Exception {
		UUID threadId = rooms.openRoom("collab-ws-user");
		String received = exchange("collab-ws-user", threadId,
				List.of("""
						{"type":"chat.message","content":"hello","threadId":"%s","from":"ignored"}
						""".formatted(threadId)), 1).get(0);

		WsFrame frame = objectMapper.readValue(received, WsFrame.class);

		assertThat(frame).isInstanceOfSatisfying(ChatMessageFrame.class, message -> {
			assertThat(message.threadId()).isEqualTo(threadId);
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
				List.of(WsTestExchange.chatMessageFrame(threadId, "should not send")), 1).get(0);

		ErrorFrame error = (ErrorFrame) objectMapper.readValue(received, WsFrame.class);
		assertThat(error.threadId()).isEqualTo(threadId);
		assertThat(error.code()).isEqualTo("THREAD_LOCKED");
	}

	@Test
	void sendsAParticipantSnapshotAsTheFirstFrameOfASubscription() throws Exception {
		UUID threadId = rooms.openRoom("snapshot-user");
		// 스냅샷만 기다리고 끝내면 수신이 먼저 완료돼 세션이 닫히고, 뒤늦게 나가는 전송이 깨진다
		// (WsTestExchange의 전송 지연, 이슈 #102). 메시지를 하나 주고받으며 순서를 본다.
		List<String> received = exchange("snapshot-user", threadId,
				List.of(WsTestExchange.chatMessageFrame(threadId, "hello")), 2,
				frameTypes("presence.snapshot", "chat.message"));

		WsFrame frame = objectMapper.readValue(received.get(0), WsFrame.class);

		assertThat(frame).isInstanceOfSatisfying(PresenceSnapshotFrame.class, snapshot -> {
			assertThat(snapshot.threadId()).isEqualTo(threadId);
			// 방에 혼자여도 자기 자신은 들어 있다(이슈 #26). userId는 fixture가 돌려주지 않아
			// 값 자체는 RoomSessionRegistryTest가 확인한다.
			assertThat(snapshot.participants()).hasSize(1);
		});
	}

	@Test
	void keepsConnectionAfterMalformedFrameAndUsesDistinctTraceIds() throws Exception {
		UUID threadId = rooms.openRoom("malformed-user");
		List<String> received = exchange("malformed-user", threadId,
				List.of("not-json", WsTestExchange.chatMessageFrame(threadId, "   "),
						"{\"type\":\"unknown\",\"content\":\"ignored\"}",
						WsTestExchange.chatMessageFrame(threadId, "valid")), 4);

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
				List.of("{\"type\":\"presence.join\",\"threadId\":\"" + threadId + "\"}",
						"{\"type\":\"presence.leave\",\"threadId\":\"" + threadId + "\"}",
						"{\"type\":\"system.notice\",\"threadId\":\"" + threadId + "\"}",
						"{\"type\":\"chat.queued\",\"threadId\":\"" + threadId + "\"}",
						"{\"type\":\"pong\"}",
						WsTestExchange.chatMessageFrame(threadId, "accepted")), 1);

		ChatMessageFrame frame = (ChatMessageFrame) objectMapper.readValue(received.get(0), WsFrame.class);
		assertThat(frame.content()).isEqualTo("accepted");
	}

	/**
	* 이슈 #160이 연 인바운드 프레임 중 조용히 넘어가야 하는 것들(이미 구독한 방 재구독, 구독 안 한 방
	* 해지·취소) 사이에 응답이 오는 것들을 섞어 "조용한 것은 정말 아무것도 안 보냈다"를 순서로 확인한다
	* (인바운드는 연결마다 순서대로 처리된다). 에코는 방 워커가 비동기로 보내지만 맨 끝이라 순서가 선다.
	*/
	@Test
	void handlesQuietInboundFramesAndEchoesTheClientIds() throws Exception {
		UUID threadId = rooms.openRoom("inbound-frames-user");
		// UUID가 아닌 nanoid 형태 — 서버가 UUID로 좁혀 파싱하지 않는다는 계약을 검증한다(이슈 #224).
		String clientMsgId = "Fup9Wytbi2B7C9A0";
		UUID turnId = UUID.randomUUID();
		List<String> received = exchange("inbound-frames-user", threadId, List.of(
				WsTestExchange.subscribeFrame(threadId),
				"{\"type\":\"room.unsubscribe\",\"threadId\":\"" + UUID.randomUUID() + "\"}",
				"{\"type\":\"chat.cancel\",\"threadId\":\"" + threadId + "\",\"turnId\":\"" + UUID.randomUUID() + "\"}",
				"{\"type\":\"chat.cancel\",\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID() + "\"}",
				"{\"type\":\"chat.cancel\",\"threadId\":\"" + threadId + "\"}",
				"{\"type\":\"ping\"}",
				"{\"type\":\"chat.message\",\"threadId\":\"" + threadId + "\",\"content\":\"after\",\"clientMsgId\":\""
						+ clientMsgId + "\",\"turnId\":\"" + turnId + "\"}"), 3);

		ErrorFrame missingTurn = (ErrorFrame) objectMapper.readValue(received.get(0), WsFrame.class);
		WsFrame pong = objectMapper.readValue(received.get(1), WsFrame.class);
		ChatMessageFrame echo = (ChatMessageFrame) objectMapper.readValue(received.get(2), WsFrame.class);

		assertThat(missingTurn.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(pong).isEqualTo(new PongFrame());
		assertThat(echo.content()).isEqualTo("after");
		assertThat(echo.clientMsgId()).isEqualTo(clientMsgId);
		assertThat(echo.turnId()).isEqualTo(turnId);
	}

	/**
	* 한 커넥션이 두 방을 나른다(이슈 #161). 방마다 에코가 자기 threadId로 오고, 한 방을 해지하면 그
	* 방으로의 발화만 NOT_SUBSCRIBED가 되며 다른 방은 그대로 산다.
	*
	* 해지는 두 에코를 받은 뒤에 보낸다 — 에코는 방 워커가 비동기로 방송하는데, 그 전에 방의 마지막
	* 연결이 빠지면 방 세대가 닫혀 에코가 버려진다. 방이 다르면 에코끼리의 도착 순서도 정해지지 않아
	* 순서가 아니라 내용으로 확인한다.
	*/
	@Test
	void carriesSeveralRoomsOnOneConnectionAndUnsubscribesOneOfThem() throws Exception {
		UUID firstRoom = rooms.openRoom("multiplex-user");
		UUID secondRoom = rooms.openRoom("multiplex-user");
		Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch subscribed = new CountDownLatch(2);
		CountDownLatch frames = new CountDownLatch(4);
		CountDownLatch completed = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Disposable client = openClient("multiplex-user", firstRoom, outbound, received, subscribed, frames,
				completed, failure, 4, 2, frameTypes("chat.message", "error"));
		try {
			outbound.tryEmitNext(WsTestExchange.subscribeFrame(secondRoom));
			assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

			outbound.tryEmitNext(WsTestExchange.chatMessageFrame(firstRoom, "first"));
			outbound.tryEmitNext(WsTestExchange.chatMessageFrame(secondRoom, "second"));
			awaitSize(received, 2);

			outbound.tryEmitNext("{\"type\":\"room.unsubscribe\",\"threadId\":\"" + secondRoom + "\"}");
			outbound.tryEmitNext(WsTestExchange.chatMessageFrame(secondRoom, "gone"));
			awaitSize(received, 3);
			outbound.tryEmitNext(WsTestExchange.chatMessageFrame(firstRoom, "still here"));

			assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();

			List<WsFrame> echoes = received.subList(0, 2).stream().map(this::readFrame).toList();
			assertThat(echoes).extracting(frame -> ((ChatMessageFrame) frame).threadId() + ":"
					+ ((ChatMessageFrame) frame).content())
					.containsExactlyInAnyOrder(firstRoom + ":first", secondRoom + ":second");

			ErrorFrame notSubscribed = (ErrorFrame) readFrame(received.get(2));
			assertThat(notSubscribed.code()).isEqualTo("NOT_SUBSCRIBED");
			assertThat(notSubscribed.threadId()).isEqualTo(secondRoom);

			ChatMessageFrame stillHere = (ChatMessageFrame) readFrame(received.get(3));
			assertThat(stillHere.threadId()).isEqualTo(firstRoom);
			assertThat(stillHere.content()).isEqualTo("still here");
		} finally {
			outbound.tryEmitComplete();
			client.dispose();
		}
	}

	@Test
	void rejectsBinaryFramesWithoutClosingTheConnection() throws Exception {
		UUID threadId = rooms.openRoom("binary-user");
		String token = TestJwtSupport.signedJwt("binary-user", List.of("USER"));
		List<String> received = new CopyOnWriteArrayList<>();

		new ReactorNettyWebSocketClient()
				.execute(wsUri(), allowedHeaders(), protocolHandler(token, session -> {
					return WsTestExchange.exchange(session, active -> Flux.just(
							active.textMessage(WsTestExchange.subscribeFrame(threadId)),
							active.binaryMessage(factory -> factory.wrap(new byte[] {1, 2, 3})),
							active.textMessage(WsTestExchange.chatMessageFrame(threadId, "after binary"))),
							2, message -> received.add(message.getPayloadAsText()), () -> {
							}, WsTestExchange.exceptPresenceSnapshot());
				}))
				.block(WsTestTimeouts.BLOCK);

		ErrorFrame error = (ErrorFrame) objectMapper.readValue(received.get(0), WsFrame.class);
		ChatMessageFrame valid = (ChatMessageFrame) objectMapper.readValue(received.get(1), WsFrame.class);
		assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(error.threadId()).isNull();
		assertThat(valid.content()).isEqualTo("after binary");
	}

	@Test
	void broadcastsOneMessageToTwoRealClientsInTheSameRoom() throws Exception {
		UUID threadId = rooms.openRoom("room-user-1", "room-user-2");
		Sinks.Many<String> firstOutbound = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<String> secondOutbound = Sinks.many().unicast().onBackpressureBuffer();
		List<String> firstReceived = new CopyOnWriteArrayList<>();
		List<String> secondReceived = new CopyOnWriteArrayList<>();
		CountDownLatch subscribed = new CountDownLatch(2);
		CountDownLatch received = new CountDownLatch(2);
		CountDownLatch completed = new CountDownLatch(2);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		// 두 클라이언트는 chat.message만 센다. 서버의 방 등록 순서에 따라 서로의 presence.join을
		// 받을 수도 있는데, 이 테스트가 보는 것은 방송이라 그 프레임에 기대면 안 된다(이슈 #25).
		Disposable first = openClient("room-user-1", threadId, firstOutbound,
				firstReceived, subscribed, received, completed, failure);
		Disposable second = openClient("room-user-2", threadId, secondOutbound,
				secondReceived, subscribed, received, completed, failure);

		try {
			// 둘 다 구독을 마친 뒤에 보낸다 — 구독 전에 보내면 늦게 든 쪽이 그 방송을 못 받는다.
			assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
			firstOutbound.tryEmitNext(WsTestExchange.chatMessageFrame(threadId, "for everyone"));
			assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();

			ChatMessageFrame firstFrame = (ChatMessageFrame) objectMapper.readValue(firstReceived.get(0), WsFrame.class);
			ChatMessageFrame secondFrame = (ChatMessageFrame) objectMapper.readValue(secondReceived.get(0), WsFrame.class);
			assertThat(firstFrame).isEqualTo(secondFrame);
			assertThat(firstFrame.threadId()).isEqualTo(threadId);
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
		CountDownLatch subscribed = new CountDownLatch(2);
		CountDownLatch stayingReceivedBoth = new CountDownLatch(2);
		CountDownLatch leavingReceivedOne = new CountDownLatch(1);
		CountDownLatch completed = new CountDownLatch(2);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		// 남는 쪽은 나가는 사람의 메시지와 퇴장 통보를 차례로 받는다. presence.join은 세지 않는다 —
		// 두 구독 중 어느 쪽이 먼저 걸리는지 클라이언트가 정할 수 없어 도착 여부가 정해지지 않는다.
		// 입장 통보 자체는 RoomSessionRegistryTest가 결정적으로 검증한다.
		Disposable staying = openClient("presence-staying", threadId, stayingOutbound, stayingReceived,
				subscribed, stayingReceivedBoth, completed, failure, 2,
				frameTypes("chat.message", "presence.leave"));
		Disposable leaving = openClient("presence-leaving", threadId, leavingOutbound, leavingReceived,
				subscribed, leavingReceivedOne, completed, failure, 1, frameTypes("chat.message"));

		try {
			assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

			// 둘 다 구독을 마쳤으니 이 메시지는 남는 쪽에도 닿는다. 그 뒤에 나가는 쪽을 끊어야
			// 퇴장 통보를 받을 상대가 있다고 확신할 수 있다.
			leavingOutbound.tryEmitNext(WsTestExchange.chatMessageFrame(threadId, "before leaving"));
			assertThat(leavingReceivedOne.await(5, TimeUnit.SECONDS)).isTrue();

			leavingOutbound.tryEmitComplete();
			leaving.dispose();

			assertThat(stayingReceivedBoth.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();

			ChatMessageFrame message =
					(ChatMessageFrame) objectMapper.readValue(stayingReceived.get(0), WsFrame.class);
			PresenceLeaveFrame left =
					(PresenceLeaveFrame) objectMapper.readValue(stayingReceived.get(1), WsFrame.class);

			assertThat(left.threadId()).isEqualTo(threadId);
			// 방금 말하고 나간 그 사람이다.
			assertThat(left.subject()).isEqualTo(message.from());
		} finally {
			stayingOutbound.tryEmitComplete();
			leavingOutbound.tryEmitComplete();
			staying.dispose();
			leaving.dispose();
		}
	}

	/**
	* 참가자가 아닌 방의 구독은 그 방 threadId로 FORBIDDEN을 받지만, 커넥션은 닫히지 않는다(이슈 #161) —
	* 다른 방 구독까지 끊을 이유가 없다. 거부 뒤에 자기 방으로 보낸 발화의 에코가 오는 것으로 확인한다.
	*/
	@Test
	void answersForbiddenForARoomTheUserIsNotInWithoutClosingTheConnection() throws Exception {
		UUID foreignRoom = rooms.openRoom("room-owner-user");
		UUID ownRoom = rooms.openRoom("outsider-user");

		List<String> received = exchange("outsider-user", ownRoom, List.of(
				WsTestExchange.subscribeFrame(foreignRoom),
				WsTestExchange.chatMessageFrame(foreignRoom, "let me in"),
				WsTestExchange.chatMessageFrame(ownRoom, "own room")), 3);

		ErrorFrame forbidden = (ErrorFrame) objectMapper.readValue(received.get(0), WsFrame.class);
		ErrorFrame notSubscribed = (ErrorFrame) objectMapper.readValue(received.get(1), WsFrame.class);
		ChatMessageFrame echo = (ChatMessageFrame) objectMapper.readValue(received.get(2), WsFrame.class);
		assertThat(forbidden.threadId()).isEqualTo(foreignRoom);
		assertThat(forbidden.code()).isEqualTo("FORBIDDEN");
		// 거부된 방에는 구독이 없으니 발화도 들어가지 않는다.
		assertThat(notSubscribed.threadId()).isEqualTo(foreignRoom);
		assertThat(notSubscribed.code()).isEqualTo("NOT_SUBSCRIBED");
		assertThat(echo.threadId()).isEqualTo(ownRoom);
	}

	/** threadId가 UUID가 아닌 구독은 형식 오류다. 방을 특정할 수 없어 threadId 없이 돌려주고 커넥션은 유지한다. */
	@Test
	void answersMalformedRequestForAnInvalidThreadIdWithoutClosing() throws Exception {
		UUID threadId = rooms.openRoom("invalid-thread-user");

		List<String> received = exchange("invalid-thread-user", threadId, List.of(
				"{\"type\":\"room.subscribe\",\"threadId\":\"not-a-uuid\"}",
				WsTestExchange.chatMessageFrame(threadId, "after invalid")), 2);

		ErrorFrame error = (ErrorFrame) objectMapper.readValue(received.get(0), WsFrame.class);
		ChatMessageFrame echo = (ChatMessageFrame) objectMapper.readValue(received.get(1), WsFrame.class);
		assertThat(error.threadId()).isNull();
		assertThat(error.code()).isEqualTo("MALFORMED_REQUEST");
		assertThat(echo.content()).isEqualTo("after invalid");
	}

	/**
	* 구독 인가는 구독할 때 한 번뿐이라, 구독을 유지한 채 참가자에서 빠진 사람이 계속 말할 수
	* 있었다(이슈 #20). 두 번 보내고 두 번 다 거부당하는 것으로, 첫 거부가 연결을 닫지 않았다는
	* 것까지 함께 본다 — 이미 걸린 구독으로 방송이 계속 가는 것(받기)은 이슈 #135 몫이다.
	*/
	@Test
	void refusesMessagesFromSomeoneRemovedWhileStillConnected() throws Exception {
		UUID threadId = rooms.openRoom("revoked-ws-owner", "revoked-ws-member");
		Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch subscribed = new CountDownLatch(1);
		CountDownLatch refusedTwice = new CountDownLatch(2);
		CountDownLatch completed = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Disposable member = openClient("revoked-ws-member", threadId, outbound, received,
				subscribed, refusedTwice, completed, failure, 2, frameTypes("error"));

		try {
			assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
			rooms.endParticipation(threadId, "revoked-ws-member", ThrMbrStatus.REVOKED, "OWNER_REVOKED");

			outbound.tryEmitNext(WsTestExchange.chatMessageFrame(threadId, "still here?"));
			outbound.tryEmitNext(WsTestExchange.chatMessageFrame(threadId, "and again"));

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

	/**
	* RoomSessionRegistry.evict(이슈 #135)가 보낸 kicked 신호로 그 방 구독만 풀리는지 확인한다(이슈 #161).
	* 참가자 제거 흐름(ThreadParticipantService)이 evict를 부르는 배선은 별도라, 여기서는 evict를 직접
	* 호출해 ThreadWebSocketHandler 쪽 처리만 본다 — 제거된 방에는 FORBIDDEN이 오고, 같은 커넥션의 다른
	* 방은 계속 발화를 주고받는다.
	*
	* evict 호출 시점은 두 방의 참여자 스냅샷 수신을 기다려 맞춘다 — 스냅샷을 받았다는 것 자체가 서버의
	* registry.join()이 이미 끝났다는 증거라, evict가 "아직 등록 안 된 연결"을 조용히 못 찾는 경합이 없다.
	*/
	@Test
	void unsubscribesOnlyTheEvictedRoomAndKeepsTheConnection() throws Exception {
		UUID evictedRoom = rooms.openRoom("evicted-ws-user");
		UUID keptRoom = rooms.openRoom("evicted-ws-user");
		Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch subscribed = new CountDownLatch(2);
		CountDownLatch echoed = new CountDownLatch(2);
		CountDownLatch completed = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Disposable client = openClient("evicted-ws-user", evictedRoom, outbound, received, subscribed, echoed,
				completed, failure, 2, 2, frameTypes("error", "chat.message"));
		try {
			outbound.tryEmitNext(WsTestExchange.subscribeFrame(keptRoom));
			assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

			roomSessionRegistry.evict(evictedRoom, "evicted-ws-user");
			outbound.tryEmitNext(WsTestExchange.chatMessageFrame(keptRoom, "after eviction"));

			assertThat(echoed.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();

			List<WsFrame> frames = received.stream().map(this::readFrame).toList();
			assertThat(frames).anySatisfy(frame -> assertThat(frame).isInstanceOfSatisfying(ErrorFrame.class,
					error -> {
						assertThat(error.threadId()).isEqualTo(evictedRoom);
						assertThat(error.code()).isEqualTo("FORBIDDEN");
					}));
			assertThat(frames).anySatisfy(frame -> assertThat(frame).isInstanceOfSatisfying(ChatMessageFrame.class,
					echo -> assertThat(echo.threadId()).isEqualTo(keptRoom)));
			// 구독이 실제로 풀려 방에서 빠졌다 — 다시 evict해도 찾을 연결이 없다.
			assertThat(roomSessionRegistry.evict(evictedRoom, "evicted-ws-user")).isFalse();
		} finally {
			outbound.tryEmitComplete();
			client.dispose();
		}
	}

	/**
	* 위 테스트가 ThreadWebSocketHandler 쪽 처리만 좁혀 봤다면, 이 테스트는 참가자 제거 REST
	* 엔드포인트(ThreadParticipantService.remove)가 evict까지 실제로 부르는 배선(이슈 #135)이
	* 이어져 있는지를 REST 호출 하나로 확인한다. 제거 뒤에도 커넥션이 살아 ping에 답한다(이슈 #161).
	*/
	@Test
	void unsubscribesTheRoomWhenTheOwnerRemovesTheParticipantOverRest() throws Exception {
		UUID threadId = rooms.openRoom("evict-owner", "evict-target");
		Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch subscribed = new CountDownLatch(1);
		CountDownLatch forbiddenArrived = new CountDownLatch(1);
		CountDownLatch completed = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Disposable client = openClient("evict-target", threadId, outbound, received, subscribed,
				forbiddenArrived, completed, failure, 2, frameTypes("error", "pong"));
		try {
			assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
			restTestClient.delete()
					.uri("/api/collab/threads/{threadId}/participants/{subject}", threadId, "evict-target")
					.header(HttpHeaders.AUTHORIZATION,
							"Bearer " + TestJwtSupport.signedJwt("evict-owner", List.of("USER")))
					.exchange()
					.expectStatus().isNoContent();

			assertThat(forbiddenArrived.await(5, TimeUnit.SECONDS)).isTrue();
			outbound.tryEmitNext("{\"type\":\"ping\"}");
			assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(failure.get()).isNull();

			ErrorFrame error = (ErrorFrame) readFrame(received.get(0));
			assertThat(error.threadId()).isEqualTo(threadId);
			assertThat(error.code()).isEqualTo("FORBIDDEN");
			assertThat(readFrame(received.get(1))).isEqualTo(new PongFrame());
		} finally {
			outbound.tryEmitComplete();
			client.dispose();
		}
	}

	@Test
	void closesConnectionWithCode1000WhenClientClosesNormally() {
		String token = TestJwtSupport.signedJwt("normal-close-user", List.of("USER"));
		AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient(
				HttpClient.create(ConnectionProvider.newConnection()));

		client.execute(wsUri(), allowedHeaders(), new WebSocketHandler() {
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

	/**
	* [#181] 토큰이 곧(2초 뒤) 만료되면, 클라이언트가 close code 4000을 받아야 한다. 토큰 만료는
	* 커넥션 단위라 방을 구독하지 않은 커넥션에도 똑같이 적용된다(이슈 #161).
	*
	* 회귀 배경: #62(PR #70)에 이 경로의 통합 테스트가 있었으나 #77(방 레지스트리 도입)에서
	* 삭제됐고, 이후 남은 검증은 Mockito 목(session.close()=Mono.empty())뿐이었다. 실제로는
	* 종료 경로마다 session.close()를 부르고 그게 살아 있는 session.send(outbound)와 같은 채널에서
	* 경합해 CloseWebSocketFrame이 이중 해제되고(refCnt: 0, decrement: 1) 클라이언트가 close code를
	* 못 받았다. 이제 모든 종료 사유는 closeReason 한 곳으로 모이고, session.send(outbound)가
	* 완료된 뒤 단일 지점에서 그 사유로 한 번만 닫는다. session.receive()는 outbound와 분리해
	* 독립 구독하므로 outbound가 끊겨도 채널이 온전하다.
	*/
	@Test
	void deliversCode4000WhenTokenExpiresWhileConnected() {
		rooms.user("expiry-soon-user");
		String token = TestJwtSupport.signedJwtExpiringAt("expiry-soon-user", List.of("USER"),
				Instant.now().plusSeconds(2));
		CloseStatus observed = observeCloseCode(token);

		System.out.printf("[#181] token exp +2s -> client close code = %s%n",
				observed == null ? "NONE(null)" : observed.getCode() + " " + observed.getReason());
		assertThat(observed)
				.as("토큰 만료 강제 종료 시 클라이언트가 close code를 받아야 한다(이중 close면 못 받음)")
				.isNotNull();
		assertThat(observed.getCode())
				.as("토큰 만료 강제 종료는 4000(#62)")
				.isEqualTo(4000);
	}

	/**
	* [#181] 이미 만료된 토큰은 만료 폭이 작아도(5초) WS 핸드셰이크에서 401로 거부된다 —
	* JwtTimestampValidator 기본 clock-skew(60초)가 무색하게 사실상 유예가 없다. 따라서 #181의
	* 재연결 루프는 "이미 만료된 토큰이 방에 들어온다"가 아니라 "곧 만료될 토큰이 들어온 뒤 즉시
	* 끊긴다"에서 온다.
	*/
	@Test
	void alreadyExpiredTokensAreRejectedAtHandshakeRegardlessOfSkew() {
		rooms.user("expired-user");
		for (int secondsExpired : new int[] {5, 20, 45, 90}) {
			String token = TestJwtSupport.signedJwtExpiringAt("expired-user", List.of("USER"),
					Instant.now().minusSeconds(secondsExpired));
			assertThatThrownBy(() -> observeCloseCode(token))
					.as("만료 %d초 토큰은 핸드셰이크에서 거부돼야 한다", secondsExpired)
					.isInstanceOf(WebSocketClientHandshakeException.class)
					.hasMessageContaining("401");
		}
	}

	/** receive() demand를 걸어 서버 프레임을 소비하면서 closeStatus()로 종료 코드를 잡는다 — #62 주석대로
	 * receive()를 함께 구독하지 않으면 close 프레임이 도달하지 않는다. 서버가 먼저 닫는 시나리오에선
	 * closeStatus()가 값을 못 받을 수 있어 null 가능성을 호출부가 감안한다. */
	private CloseStatus observeCloseCode(String token) {
		AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient(
				HttpClient.create(ConnectionProvider.newConnection()));
		client.execute(wsUri(), allowedHeaders(), new WebSocketHandler() {
			@Override
			public List<String> getSubProtocols() {
				return List.of("access_token", token);
			}

			@Override
			public Mono<Void> handle(WebSocketSession session) {
				return Mono.when(session.receive().then(),
						session.closeStatus().doOnNext(closeStatus::set).then());
			}
		}).block(WsTestTimeouts.BLOCK);
		return closeStatus.get();
	}

	@Test
	void rejectsHandshakeWithoutSubProtocolAndRejectsTheOldPerRoomPath() {
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

		assertThatThrownBy(() -> client.execute(wsUri(), allowedHeaders(), session -> Mono.empty())
				.block(WsTestTimeouts.BLOCK))
				.isInstanceOf(WebSocketClientHandshakeException.class)
				.hasMessageContaining("401");

		// 방마다 커넥션을 열던 옛 경로는 병행하지 않고 걷었다(이슈 #161).
		String token = TestJwtSupport.signedJwt("old-path-user", List.of("USER"));
		assertThatThrownBy(() -> client.execute(URI.create("ws://localhost:" + port + "/api/ws/" + UUID.randomUUID()),
				allowedHeaders(), protocolHandler(token, session -> Mono.empty())).block(WsTestTimeouts.BLOCK))
				.isInstanceOf(WebSocketClientHandshakeException.class);
	}

	/** 한 방을 구독하고 나서 outbound를 보낸다. 참여자 스냅샷(이슈 #26)은 구독마다 첫 프레임으로 반드시
	 * 온다 — 그것을 보는 테스트가 아니면 세지 않는다. 세면 모든 테스트의 기대 프레임 수가 하나씩 밀린다. */
	private List<String> exchange(String subject, UUID threadId, List<String> outbound, int expectedFrames) {
		return exchange(subject, threadId, outbound, expectedFrames,
				WsTestExchange.exceptPresenceSnapshot());
	}

	private List<String> exchange(String subject, UUID threadId, List<String> outbound, int expectedFrames,
			Predicate<WebSocketMessage> interesting) {
		String token = TestJwtSupport.signedJwt(subject, List.of("USER"));
		List<String> received = new CopyOnWriteArrayList<>();

		new ReactorNettyWebSocketClient()
				.execute(wsUri(), allowedHeaders(), protocolHandler(token, session -> {
					return WsTestExchange.exchange(session,
							active -> Flux.fromIterable(outbound)
									.startWith(WsTestExchange.subscribeFrame(threadId))
									.map(active::textMessage),
							expectedFrames, message -> received.add(message.getPayloadAsText()), () -> {
							}, interesting);
				}))
				.block(WsTestTimeouts.BLOCK);

		return received;
	}

	private Disposable openClient(String subject, UUID threadId, Sinks.Many<String> outbound,
			List<String> frames, CountDownLatch subscribed, CountDownLatch received,
			CountDownLatch completed, AtomicReference<Throwable> failure) {
		return openClient(subject, threadId, outbound, frames, subscribed, received, completed, failure, 1,
				frameTypes("chat.message"));
	}

	private Disposable openClient(String subject, UUID threadId, Sinks.Many<String> outbound,
			List<String> frames, CountDownLatch subscribed, CountDownLatch received,
			CountDownLatch completed, AtomicReference<Throwable> failure, long expectedFrames,
			Predicate<WebSocketMessage> interesting) {
		return openClient(subject, threadId, outbound, frames, subscribed, received, completed, failure,
				expectedFrames, 1, interesting);
	}

	/**
	* 한 방을 구독한 채 열어 두는 클라이언트. 참여자 스냅샷이 올 때마다 subscribed를 내린다 — 스냅샷은
	* 서버가 구독을 마쳤다는 증거라, 테스트는 이것을 기다린 뒤에 보내야 방송을 놓치지 않는다. 스냅샷은
	* frames·received에 넣지 않는다. subscribed는 여러 클라이언트가 함께 쓸 수 있어, 이 클라이언트가 받을
	* 스냅샷 수(구독할 방 수)는 따로 받는다.
	*/
	private Disposable openClient(String subject, UUID threadId, Sinks.Many<String> outbound,
			List<String> frames, CountDownLatch subscribed, CountDownLatch received,
			CountDownLatch completed, AtomicReference<Throwable> failure, long expectedFrames,
			int expectedSnapshots, Predicate<WebSocketMessage> interesting) {
		String token = TestJwtSupport.signedJwt(subject, List.of("USER"));
		Predicate<WebSocketMessage> snapshot = frameTypes("presence.snapshot");
		return new ReactorNettyWebSocketClient()
				.execute(wsUri(), allowedHeaders(), protocolHandler(token, session -> {
					return WsTestExchange.exchange(session,
							active -> outbound.asFlux()
									.startWith(WsTestExchange.subscribeFrame(threadId))
									.map(active::textMessage),
							expectedFrames + expectedSnapshots,
							message -> {
								if (snapshot.test(message)) {
									subscribed.countDown();
									return;
								}
								frames.add(message.getPayloadAsText());
								received.countDown();
							}, () -> {
							}, snapshot.or(interesting))
							.doFinally(ignored -> outbound.tryEmitComplete())
							.then(session.close(CloseStatus.NORMAL));
				}))
				.doOnError(error -> failure.compareAndSet(null, error))
				.doFinally(ignored -> completed.countDown())
				.subscribe();
	}

	private WsFrame readFrame(String payload) {
		return objectMapper.readValue(payload, WsFrame.class);
	}

	/** 비동기로 쌓이는 수신 목록이 그만큼 찰 때까지 기다린다 — 다음 프레임을 보낼 시점을 맞출 때 쓴다. */
	private static void awaitSize(List<String> frames, int size) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (frames.size() < size && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(frames).hasSizeGreaterThanOrEqualTo(size);
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

	private URI wsUri() {
		return URI.create("ws://localhost:" + port + "/api/ws");
	}

	private static HttpHeaders allowedHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setOrigin(ALLOWED_ORIGIN);
		return headers;
	}

}
