package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.UserIdentityService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class Name : ChatControllerTest.java
 * Description : `/api/chat/sessions`(레거시 별칭)·`/api/threads/{id}`(공용 lifecycle) 계약과, 이
 *               컨트롤러가 대표로 검증해 온 02·EDGE 전역 필터(CORS·JWT·에러 봉투) 동작을 실제 서버
 *               기동 상태에서 검증한다. `/api/chat/stream`(이슈 #164 제거)이 있던 시절엔 이 전역
 *               필터 테스트들도 그 엔드포인트에 얹혀 있었는데, 지금은 남아 있는 아무 인증 엔드포인트
 *               (`/api/chat/sessions`)로 옮겨 왔다 — 검증 대상이 컨트롤러 로직이 아니라 필터
 *               체인이라 어느 엔드포인트를 쓰든 결과는 같다.
 *               02·EDGE 보안 통과에 필요한 JWT 디코더는 FakeJwtDecoderConfig(공용)로, 실 LLM
 *               호출 방지는 FakeChatModelConfig(공용)로 교체한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class})
class ChatControllerTest {

	@LocalServerPort
	private int port;

	@Autowired
	private UserIdentityService userIdentityService;

	@Autowired
	private DirectChatTurnService directChatTurnService;

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
	void rejectsRequestWithoutToken() {
		restTestClient.get()
				.uri("/api/chat/sessions")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("UNAUTHENTICATED")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	@Test
	void rejectsRequestWithAudienceMismatch() {
		restTestClient.get()
				.uri("/api/chat/sessions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("testuser", List.of("USER"), List.of("other-client")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("TOKEN_INVALID")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	/** aud 클레임 자체가 없는 토큰(Keycloak admin-cli 등)에서 audienceValidator가 NPE를 던지던 회귀를 막는다. */
	@Test
	void rejectsRequestWithMissingAudienceClaim() {
		restTestClient.get()
				.uri("/api/chat/sessions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwtWithoutAudience("testuser", List.of("USER")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("TOKEN_INVALID")
				.jsonPath("$.error.traceId").isNotEmpty();
	}

	@Test
	void rejectsRequestWithoutRequiredRole() {
		restTestClient.get()
				.uri("/api/chat/sessions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("norole-user", List.of()))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("FORBIDDEN");
	}

	/** 프리플라이트(OPTIONS)는 토큰 없이도 JWT 검증 이전에 CorsWebFilter가 처리해 통과한다(02·EDGE). */
	@Test
	void allowsCorsPreflightWithoutToken() {
		restTestClient.method(HttpMethod.OPTIONS)
				.uri("/api/chat/sessions")
				.header(HttpHeaders.ORIGIN, "http://localhost:3010")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
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

	@Test
	void readsDirectHistoryFromTheCommonThreadEndpoint() {
		String sessionId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
		String subject = "common-history-user";
		sendChatMessage(sessionId, subject, "common history");
		String token = "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER"));

		restTestClient.get()
				.uri("/api/threads/{threadId}/messages?afterSeq=-1", sessionId)
				.header(HttpHeaders.AUTHORIZATION, token)
				.exchange()
				.expectStatus().isBadRequest();

		restTestClient.get()
				.uri("/api/threads/{threadId}/messages", sessionId)
				.header(HttpHeaders.AUTHORIZATION, token)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].athKind").isEqualTo("HUMAN")
				.jsonPath("$[0].content").isEqualTo("common history");
	}

	@Test
	void renamesAndDeletesDirectThreadThroughTheCommonLifecyclePaths() {
		String sessionId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
		String subject = "common-lifecycle-user";
		sendChatMessage(sessionId, subject, "common lifecycle");
		String token = "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER"));

		restTestClient.patch()
				.uri("/api/threads/{threadId}", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, token)
				.body("{ \"title\": \"  new title  \" }")
				.exchange()
				.expectStatus().isNoContent();

		restTestClient.get()
				.uri("/api/chat/sessions")
				.header(HttpHeaders.AUTHORIZATION, token)
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.value(body -> assertThat(body).contains("new title").doesNotContain("  new title  "));

		restTestClient.delete()
				.uri("/api/threads/{threadId}", sessionId)
				.header(HttpHeaders.AUTHORIZATION, token)
				.exchange()
				.expectStatus().isNoContent();

		restTestClient.get()
				.uri("/api/chat/sessions/{sessionId}/messages", sessionId)
				.header(HttpHeaders.AUTHORIZATION, token)
				.exchange()
				.expectStatus().isNotFound();
	}

	/**
	* 조회 테스트용으로 DIRECT 세션·HUMAN 메시지를 미리 만들어 두는 헬퍼. `/api/chat/stream`이
	* 있던 시절엔 실 HTTP 호출로 만들었는데(이슈 #164로 제거), 인증 파이프라인이 하던 subject→
	* userId 프로비저닝(JIT)만 UserIdentityService로 직접 재현하면 되므로 HTTP를 거칠 필요가
	* 없다 — LLM 호출도 없어 FakeChatModelConfig 트리거링도 필요 없어졌다.
	*/
	private void sendChatMessage(String sessionId, String subject, String content) {
		UUID userId = userIdentityService.resolveOrProvision(subject).block();
		directChatTurnService.prepareOrCreateWithPendingAgentBlocking(UUID.fromString(sessionId), userId, content,
				content);
	}

}
