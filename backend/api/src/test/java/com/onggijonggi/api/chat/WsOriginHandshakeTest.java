package com.onggijonggi.api.chat;

import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Class Name : WsOriginHandshakeTest.java
 * Description : 이슈 #5 — /api/ws 핸드셰이크의 Origin 검증(WsOriginWebFilter)을 실제 배선으로 확인한다.
 *               화이트리스트는 application.properties의 app.cors.allowed-origins를 그대로 쓴다
 *               (테스트 프로필이 이 값을 덮지 않는다).
 *               토큰은 두 경우 모두 유효한 것을 보낸다 — Origin 때문에 거부됐는지 토큰 때문인지
 *               헷갈리지 않게 하기 위해서다.
 *               "Origin이 없으면 통과"는 여기서 검증할 수 없다. Netty의 WebSocketClientHandshaker가
 *               호출자가 안 넘기면 Origin을 스스로 만들어 붙이기 때문에(실측: 403), 이 클라이언트로는
 *               헤더가 없는 요청 자체를 만들 수 없다 — 그 규칙은 WsOriginWebFilterTest가 단위로 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ChatControllerTest.FakeChatModelConfig.class, FakeJwtDecoderConfig.class, CollabRoomFixture.class})
class WsOriginHandshakeTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	@LocalServerPort
	private int port;

	@Autowired
	private CollabRoomFixture.CollabRooms rooms;

	@Test
	void acceptsHandshakeWhenOriginIsWhitelisted() {
		HttpHeaders headers = new HttpHeaders();
		headers.setOrigin(ALLOWED_ORIGIN);

		assertThat(connect(headers)).contains("\"type\":\"chat.message\"", "\"content\":\"origin check\"");
	}

	/** 화이트리스트 밖 Origin은 업그레이드 단계에서 403으로 끊는다. 토큰이 유효해도 마찬가지다 —
	 * 이 필터가 인증보다 먼저 돌기 때문이다. */
	@Test
	void rejectsHandshakeWhenOriginIsNotWhitelisted() {
		HttpHeaders headers = new HttpHeaders();
		headers.setOrigin("http://evil.example");

		assertThatThrownBy(() -> connect(headers))
				.isInstanceOf(WebSocketClientHandshakeException.class)
				.hasMessageContaining("403");
	}

	/** 유효한 토큰을 서브프로토콜로 실어 붙고, 핸들러가 돌려주는 첫 프레임을 꺼내온다
	 * (CollabWebSocketHandlerTest와 같은 방식 — 그쪽 주석에 서브프로토콜 구성 이유가 있다). */
	private String connect(HttpHeaders headers) {
		String token = TestJwtSupport.signedJwt("origin-allowed-user", List.of("USER"));
		AtomicReference<String> received = new AtomicReference<>();

		new ReactorNettyWebSocketClient()
				.execute(URI.create("ws://localhost:" + port + "/api/ws/" + rooms.openRoom("origin-allowed-user")),
						headers, new WebSocketHandler() {

					@Override
					public List<String> getSubProtocols() {
						return List.of("access_token", token);
					}

					@Override
					public Mono<Void> handle(WebSocketSession session) {
						return WsTestExchange.exchange(session,
								active -> Mono.just(active.textMessage(
										"{\"type\":\"chat.message\",\"content\":\"origin check\"}")),
								1, message -> received.set(message.getPayloadAsText()), () -> {
								}, WsTestExchange.exceptPresenceSnapshot());
					}
				})
				.block(WsTestTimeouts.BLOCK);

		return received.get();
	}

}
