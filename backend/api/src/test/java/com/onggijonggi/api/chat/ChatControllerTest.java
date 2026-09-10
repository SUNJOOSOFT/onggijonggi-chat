package com.onggijonggi.api.chat;

import com.openai.core.http.Headers;
import com.openai.errors.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class Name : ChatControllerTest.java
 * Description : POST /api/chat/stream의 스트리밍 계약(Content-Type, 프레이밍 없는 텍스트 청크)과
 *               요청 검증 실패 시 에러 봉투 응답을 실제 서버 기동 상태에서 검증한다. 실제 LLM에
 *               붙지 않도록 FakeChatModelConfig로 ChatModel을 고정 응답 가짜 구현으로 교체해,
 *               ChatController → LlmChatStreamService → ChatClient 오케스트레이션 경로는 그대로 태우되
 *               네트워크는 타지 않는다. 02·EDGE 보안 통과에 필요한 JWT 디코더는
 *               FakeJwtDecoderConfig(공용)로 교체한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FakeJwtDecoderConfig.class)
class ChatControllerTest {

	private static final String FAKE_REPLY = "(fake) 안녕하세요";

	/** 이 메시지가 오면 fakeChatModel이 강제로 예외를 발생시킨다(500 핸들러 테스트 전용 트리거). */
	private static final String TRIGGER_SERVER_ERROR = "TRIGGER_SERVER_ERROR";

	/** 게이트웨이가 모델 호출을 거절한 상황을 흉내 낸다(502 핸들러 테스트 전용 트리거). */
	private static final String TRIGGER_MODEL_UNAVAILABLE = "TRIGGER_MODEL_UNAVAILABLE";

	/** 실제 LLM 호출 없이 오케스트레이션 경로만 검증하기 위해 ChatModel 빈을 대체한다. */
	@TestConfiguration
	static class FakeChatModelConfig {

		@Bean
		@Primary
		ChatModel fakeChatModel() {
			return new ChatModel() {

				@Override
				public ChatResponse call(Prompt prompt) {
					return new ChatResponse(List.of(new Generation(new AssistantMessage(FAKE_REPLY))));
				}

				@Override
				public Flux<ChatResponse> stream(Prompt prompt) {
					String lastMessage = prompt.getInstructions().isEmpty()
							? ""
							: prompt.getInstructions().get(prompt.getInstructions().size() - 1).getText();
					if (TRIGGER_SERVER_ERROR.equals(lastMessage)) {
						return Flux.error(new IllegalStateException("테스트용 강제 예외"));
					}
					if (TRIGGER_MODEL_UNAVAILABLE.equals(lastMessage)) {
						// 키 미설정 모델을 호출했을 때 게이트웨이가 401로 거절하며 SDK가 던지는 예외.
						// 실물과 같게 CompletionException으로 한 번 감싼다 — SDK가 비동기 호출이라
						// 실제로 이렇게 싸여 올라오고, 감싸지 않으면 LlmChatStreamService의 언랩을
						// 건너뛰어 테스트가 헛통과한다.
						return Flux.error(new CompletionException(UnauthorizedException.builder()
								.headers(Headers.builder().build())
								.build()));
					}
					return Flux.just(call(prompt));
				}
			};
		}

	}

	@LocalServerPort
	private int port;

	private RestTestClient restTestClient;

	/**
	* Boot 4.1의 @AutoConfigureRestTestClient 자동구성 빈은 WebApplicationContext(서블릿)를 요구해
	* WebFlux 랜덤 포트 조합에서 실패하므로, bindToServer()로 직접 바인딩한다.
	*/
	@BeforeEach
	void setUp() {
		restTestClient = RestTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	@Test
	void streamsLlmReplyAsPlainTextChunks() {
		String requestBody = """
				{
				  "sessionId": "11111111-1111-1111-1111-111111111111",
				  "modelId": "test-model",
				  "messages": [ { "role": "user", "content": "안녕" } ]
				}
				""";

		EntityExchangeResult<String> result = restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.body(requestBody)
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.parseMediaType("text/plain;charset=UTF-8"))
				.expectBody(String.class)
				.returnResult();

		assertThat(result.getResponseBody()).contains(FAKE_REPLY);
		assertThat(result.getResponseBody()).doesNotContain("data:");
	}

	@Test
	void rejectsRequestMissingModelId() {
		String requestBody = """
				{
				  "sessionId": "11111111-1111-1111-1111-111111111111",
				  "messages": [ { "role": "user", "content": "안녕" } ]
				}
				""";

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.body(requestBody)
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void rejectsRequestWithoutToken() {
		String requestBody = """
				{
				  "sessionId": "11111111-1111-1111-1111-111111111111",
				  "modelId": "test-model",
				  "messages": [ { "role": "user", "content": "안녕" } ]
				}
				""";

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.body(requestBody)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("UNAUTHENTICATED")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	@Test
	void rejectsRequestWithAudienceMismatch() {
		String requestBody = """
				{
				  "sessionId": "11111111-1111-1111-1111-111111111111",
				  "modelId": "test-model",
				  "messages": [ { "role": "user", "content": "안녕" } ]
				}
				""";

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("testuser", List.of("USER"), List.of("other-client")))
				.body(requestBody)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("TOKEN_INVALID")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	/** aud 클레임 자체가 없는 토큰(Keycloak admin-cli 등)에서 audienceValidator가 NPE를 던지던 회귀를 막는다. */
	@Test
	void rejectsRequestWithMissingAudienceClaim() {
		String requestBody = """
				{
				  "sessionId": "11111111-1111-1111-1111-111111111111",
				  "modelId": "test-model",
				  "messages": [ { "role": "user", "content": "안녕" } ]
				}
				""";

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwtWithoutAudience("testuser", List.of("USER")))
				.body(requestBody)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("TOKEN_INVALID")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	@Test
	void rejectsRequestWithoutRequiredRole() {
		String requestBody = """
				{
				  "sessionId": "11111111-1111-1111-1111-111111111111",
				  "modelId": "test-model",
				  "messages": [ { "role": "user", "content": "안녕" } ]
				}
				""";

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("norole-user", List.of()))
				.body(requestBody)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("FORBIDDEN");
	}

	/** 프리플라이트(OPTIONS)는 토큰 없이도 JWT 검증 이전에 CorsWebFilter가 처리해 통과한다(02·EDGE). */
	@Test
	void allowsCorsPreflightWithoutToken() {
		restTestClient.method(HttpMethod.OPTIONS)
				.uri("/api/chat/stream")
				.header(HttpHeaders.ORIGIN, "http://localhost:3010")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.exchange()
				.expectStatus().is2xxSuccessful()
				.expectHeader().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
	}

	/**
	 * CORS 허용 메서드에 PUT이 빠져 소유권 위임(#20)·잠금·보관(#131)이 브라우저에서만 막혀 있었다.
	 * 위 allowsCorsPreflightWithoutToken은 Allow-Origin 존재만 봐서 이 구멍을 통과시켰다.
	 */
	@Test
	void allowsPreflightForPutSoOwnerTransferAndLockReachTheServer() {
		restTestClient.method(HttpMethod.OPTIONS)
				.uri("/api/collab/threads/00000000-0000-0000-0000-000000000000/owner")
				.header(HttpHeaders.ORIGIN, "http://localhost:3010")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
				.exchange()
				.expectStatus().is2xxSuccessful()
				.expectHeader().valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ".*PUT.*");
	}

	/**
	 * 협업방 생성이 실어 보내는 Idempotency-Key(#149)가 허용 헤더에 없어, 방 생성이 브라우저에서만
	 * 막혀 있었다. CORS는 브라우저만 강제하므로 서버 대 서버 테스트로는 드러나지 않는다.
	 */
	@Test
	void allowsPreflightForIdempotencyKeySoThreadCreationReachesTheServer() {
		restTestClient.method(HttpMethod.OPTIONS)
				.uri("/api/collab/threads")
				.header(HttpHeaders.ORIGIN, "http://localhost:3010")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Idempotency-Key")
				.exchange()
				.expectStatus().is2xxSuccessful()
				.expectHeader().valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "(?i).*Idempotency-Key.*");
	}

	/** docker-compose 헬스체크가 무토큰 /actuator/health 200 응답에 의존한다(02·EDGE). */
	@Test
	void allowsActuatorHealthWithoutToken() {
		restTestClient.get()
				.uri("/actuator/health")
				.exchange()
				.expectStatus().isOk();
	}

	/** /api/** 안에서 매칭되는 컨트롤러가 없으면 WebFlux 기본 404를 GlobalExceptionHandler가 에러 봉투로 변환한다. */
	@Test
	void returnsNotFoundEnvelopeForUnknownApiPath() {
		restTestClient.post()
				.uri("/api/nonexistent")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("NOT_FOUND")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	/** GlobalExceptionHandler의 catch-all 핸들러는 예외 상세를 응답에 노출하지 않고 고정 안전 문구로 감싼다. */
	@Test
	void returnsInternalErrorEnvelopeWithoutStackTrace() {
		String requestBody = """
				{
				  "sessionId": "11111111-1111-1111-1111-111111111111",
				  "modelId": "test-model",
				  "messages": [ { "role": "user", "content": "%s" } ]
				}
				""".formatted(TRIGGER_SERVER_ERROR);

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.body(requestBody)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("INTERNAL_ERROR")
				.jsonPath("$.error.message").isEqualTo("서버 오류가 발생했습니다.")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	/**
	* 키를 채우지 않은 모델도 화면 목록에 뜨므로 사용자가 고를 수 있다 — 그때 게이트웨이가 거절하면
	* 원인을 노출하지 않고 CLIENT가 안내 문구를 고를 수 있는 전용 코드로 넘긴다. 401이 아니라 502인
	* 것이 중요하다(401은 CLIENT가 세션 만료로 보고 재로그인을 시도한다).
	*/
	@Test
	void returnsModelUnavailableEnvelopeWhenGatewayRejectsModel() {
		String requestBody = """
				{
				  "sessionId": "11111111-1111-1111-1111-111111111111",
				  "modelId": "no-key-model",
				  "messages": [ { "role": "user", "content": "%s" } ]
				}
				""".formatted(TRIGGER_MODEL_UNAVAILABLE);

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.body(requestBody)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("MODEL_UNAVAILABLE")
				.jsonPath("$.error.message").isEqualTo("모델을 호출할 수 없습니다.")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	@Test
	void listsSessionsForAuthenticatedUser() {
		String sessionId = "22222222-2222-2222-2222-222222222222";
		sendChatMessage(sessionId, "testuser", "세션 목록 조회 테스트");

		EntityExchangeResult<String> result = restTestClient.get()
				.uri("/api/chat/sessions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult();

		assertThat(result.getResponseBody()).contains(sessionId);
	}

	/**
	* assistant 응답 저장은 스트림 완료 후 fire-and-forget이라 타이밍이 보장되지 않으므로 이
	* 테스트에선 검증하지 않는다 — user 메시지 저장만 스트림 응답 이전에 동기적으로 완료됨이 보장된다.
	*/
	@Test
	void listsMessagesForOwnedSession() {
		String sessionId = "33333333-3333-3333-3333-333333333333";
		sendChatMessage(sessionId, "testuser", "메시지 조회 테스트");

		EntityExchangeResult<String> result = restTestClient.get()
				.uri("/api/chat/sessions/{sessionId}/messages", sessionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult();

		assertThat(result.getResponseBody()).contains("메시지 조회 테스트");
	}

	/** 타인 세션 존재 여부를 노출하지 않는다. */
	@Test
	void returnsNotFoundForSessionOwnedByAnotherUser() {
		String sessionId = "44444444-4444-4444-4444-444444444444";
		sendChatMessage(sessionId, "testuser", "타인 접근 테스트");

		restTestClient.get()
				.uri("/api/chat/sessions/{sessionId}/messages", sessionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("otheruser", List.of("USER")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("NOT_FOUND");
	}

	@Test
	void deletesOwnedSession() {
		String sessionId = "55555555-5555-5555-5555-555555555555";
		sendChatMessage(sessionId, "testuser", "삭제 테스트");

		restTestClient.delete()
				.uri("/api/chat/sessions/{sessionId}", sessionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

		restTestClient.get()
				.uri("/api/chat/sessions/{sessionId}/messages", sessionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	/** 타인 세션 DELETE는 404이며 삭제되지 않는다 — 원 소유자는 여전히 메시지를 조회할 수 있다. */
	@Test
	void returnsNotFoundWhenDeletingSessionOwnedByAnotherUser() {
		String sessionId = "66666666-6666-6666-6666-666666666666";
		sendChatMessage(sessionId, "testuser", "타인 삭제 시도 테스트");

		restTestClient.delete()
				.uri("/api/chat/sessions/{sessionId}", sessionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("otheruser", List.of("USER")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);

		restTestClient.get()
				.uri("/api/chat/sessions/{sessionId}/messages", sessionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.exchange()
				.expectStatus().isOk();
	}

	@Test
	void renamesOwnedSession() {
		String sessionId = "77777777-7777-7777-7777-777777777777";
		sendChatMessage(sessionId, "testuser", "이름변경 테스트");

		restTestClient.patch()
				.uri("/api/chat/sessions/{sessionId}", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.body("{ \"title\": \"바뀐 제목\" }")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.title").isEqualTo("바뀐 제목");
	}

	/** 타인 세션의 존재 여부를 노출하지 않는다. */
	@Test
	void returnsNotFoundWhenRenamingSessionOwnedByAnotherUser() {
		String sessionId = "88888888-8888-8888-8888-888888888888";
		sendChatMessage(sessionId, "testuser", "타인 이름변경 시도 테스트");

		restTestClient.patch()
				.uri("/api/chat/sessions/{sessionId}", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("otheruser", List.of("USER")))
				.body("{ \"title\": \"뺏은 제목\" }")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void rejectsRenameWithBlankTitle() {
		String sessionId = "99999999-9999-9999-9999-999999999999";
		sendChatMessage(sessionId, "testuser", "빈 제목 검증 테스트");

		restTestClient.patch()
				.uri("/api/chat/sessions/{sessionId}", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("testuser", List.of("USER")))
				.body("{ \"title\": \"\" }")
				.exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
	}

	/** 조회 테스트용으로 세션·메시지를 미리 만들어 두기 위한 헬퍼. */
	private void sendChatMessage(String sessionId, String subject, String content) {
		String requestBody = """
				{
				  "sessionId": "%s",
				  "modelId": "test-model",
				  "messages": [ { "role": "user", "content": "%s" } ]
				}
				""".formatted(sessionId, content);

		restTestClient.post()
				.uri("/api/chat/stream")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER")))
				.body(requestBody)
				.exchange()
				.expectStatus().isOk();
	}

}
