package com.onggijonggi.api.chat;

import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Class Name : WsHandshakeRateLimitTest.java
 * Description : 이슈 #6 — /api/ws 핸드셰이크가 sub별로 세어지고 한도를 넘으면 업그레이드 전에 429로
 *               끊기는지, 그리고 그 카운터가 HTTP와 갈려 있는지를 실제 배선으로 확인한다.
 *               윈도우 리셋 같은 고정 윈도우 자체의 동작은 RateLimitWebFilterTest가 이미 본다 —
 *               같은 필터 클래스를 재사용하므로 여기서는 배선과 버킷 분리만 본다.
 *               Origin은 두 경우 모두 화이트리스트 값을 붙인다. Netty의 WebSocketClientHandshaker가
 *               호출자가 안 넘기면 Origin을 스스로 만들어 붙이는데(ws://localhost:임의포트) 그 값은
 *               화이트리스트 밖이라, 안 붙이면 429가 아니라 WsOriginWebFilter의 403이 나온다(이슈 #5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"app.ratelimit.ws-handshake-per-minute=" + WsHandshakeRateLimitTest.WS_LIMIT,
				"app.ratelimit.window-seconds=" + WsHandshakeRateLimitTest.TEST_WINDOW_SECONDS})
@ActiveProfiles("test")
@Import({ChatControllerTest.FakeChatModelConfig.class, FakeJwtDecoderConfig.class})
class WsHandshakeRateLimitTest {

	static final int WS_LIMIT = 2;

	/** 윈도우 리셋은 RateLimitWebFilterTest가 검증하므로 이 배선 테스트에서는 경계를 만들지 않는다. */
	static final long TEST_WINDOW_SECONDS = Long.MAX_VALUE;

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";

	@LocalServerPort
	private int port;

	private RestTestClient restTestClient;

	@BeforeEach
	void setUp() {
		restTestClient = RestTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	/**
	* rejectsHandshakeAfterExceedingLimit: 한도까지는 핸드셰이크가 통과해 핸들러까지 닿고, 그다음
	* 연결은 업그레이드가 성립하기 전에 429로 끊기는지 본다. 브라우저와 달리 Netty 클라이언트는
	* 101이 아닌 응답을 예외로 올려줘서, 여기서는 상태 코드를 직접 확인할 수 있다.
	*/
	@Test
	void rejectsHandshakeAfterExceedingLimit() {
		String sub = "ws-ratelimit-user";

		for (int i = 0; i < WS_LIMIT; i++) {
			connect(sub);
		}

		assertThatThrownBy(() -> connect(sub))
				.isInstanceOf(WebSocketClientHandshakeException.class)
				.hasMessageContaining("429");
	}

	/**
	* keepsHttpBucketSeparate: WS 한도를 다 쓴 sub가 HTTP에서는 멀쩡히 통과하는지 본다. 두 체인이
	* RateLimitWebFilter 인스턴스를 따로 갖는다는 설계가 실제로 그렇게 도는지 확인하는 자리다 —
	* 카운터를 static이나 공용 빈으로 끌어올리면 이 테스트가 먼저 깨진다.
	*/
	@Test
	void keepsHttpBucketSeparate() {
		String sub = "ws-ratelimit-mixed-user";

		for (int i = 0; i < WS_LIMIT; i++) {
			connect(sub);
		}
		assertThatThrownBy(() -> connect(sub))
				.isInstanceOf(WebSocketClientHandshakeException.class)
				.hasMessageContaining("429");

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt(sub, List.of("USER")))
				.body("""
						{
						  "sessionId": "11111111-1111-1111-1111-111111111111",
						  "modelId": "test-model",
						  "messages": [ { "role": "user", "content": "안녕" } ]
						}
						""")
				.exchange()
				.expectStatus().isOk();
	}

	/**
	* writesRateLimitedEnvelopeOnWsPath: 거부 응답의 본문이 HTTP 경로와 같은 RATE_LIMITED 봉투인지,
	* Retry-After가 실리는지 본다. 업그레이드된 소켓이 아니라 평범한 HTTP GET으로 두드리는 이유는,
	* 브라우저도 Netty 클라이언트도 핸드셰이크 응답의 본문을 꺼내주지 않아 소켓으로는 확인할 방법이
	* 없기 때문이다. 토큰은 실제 클라이언트와 같이 Sec-WebSocket-Protocol에 실어 보낸다 —
	* 그래야 WsSubProtocolBearerTokenConverter가 sub를 읽고 이 필터가 셀 수 있다.
	* 한도까지의 응답 상태는 보지 않는다. 업그레이드 헤더가 없어 핸들러 단계에서 어떻게 끝나든
	* 이 테스트의 관심사가 아니고, 세어졌다는 사실만 있으면 된다.
	*/
	@Test
	void writesRateLimitedEnvelopeOnWsPath() {
		String token = TestJwtSupport.signedJwt("ws-ratelimit-envelope-user", List.of("USER"));

		for (int i = 0; i < WS_LIMIT; i++) {
			probeWsPath(token).exchange();
		}

		probeWsPath(token).exchange()
				.expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
				.expectHeader().exists(HttpHeaders.RETRY_AFTER)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("RATE_LIMITED");
	}

	private RestTestClient.RequestHeadersSpec<?> probeWsPath(String token) {
		return restTestClient.get()
				.uri("/api/ws")
				.header("Sec-WebSocket-Protocol", "access_token, " + token);
	}

	/** 유효한 토큰을 서브프로토콜로 실어 핸드셰이크한 뒤 정상 종료한다. */
	private void connect(String sub) {
		String token = TestJwtSupport.signedJwt(sub, List.of("USER"));
		HttpHeaders headers = new HttpHeaders();
		headers.setOrigin(ALLOWED_ORIGIN);

		new ReactorNettyWebSocketClient()
				.execute(URI.create("ws://localhost:" + port + "/api/ws"),
						headers, new WebSocketHandler() {

					@Override
					public List<String> getSubProtocols() {
						return List.of("access_token", token);
					}

					@Override
					public Mono<Void> handle(WebSocketSession session) {
						return session.close(CloseStatus.NORMAL);
					}
				})
				.block(WsTestTimeouts.BLOCK);
	}

}
