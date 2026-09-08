package com.onggijonggi.api.auth.keycloak;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class Name : KeycloakAdminClientTest.java
 * Description : 토큰 발급·재사용과 사용자 조회 응답 해석만 검증한다. WebClient의 exchangeFunction을
 *               가짜로 물려 요청 경로별로 분기하므로 실행 중인 Keycloak이 필요 없다.
 */
class KeycloakAdminClientTest {

	private static final String INTERNAL_URL = "http://keycloak:8080";

	private static final String REALM = "app-realm";

	private static final String CLIENT_ID = "ogjg-client";

	private static final String CLIENT_SECRET = "test-secret";

	private static final String SUBJECT = "b3f2a6b0-3f0e-4a9a-9e0a-2f6c3d1e9a11";

	private final AtomicInteger tokenRequests = new AtomicInteger();

	private final AtomicReference<ClientRequest> lastUserRequest = new AtomicReference<>();

	private KeycloakAdminClient clientReturning(String username, long expiresInSeconds) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().toString().endsWith("/protocol/openid-connect/token")) {
				tokenRequests.incrementAndGet();
				return Mono.just(jsonResponse("""
						{ "access_token": "admin-token", "expires_in": %d, "token_type": "Bearer" }
						""".formatted(expiresInSeconds)));
			}
			lastUserRequest.set(request);
			if (username == null) {
				return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
			}
			return Mono.just(jsonResponse("""
					{ "id": "%s", "username": "%s", "firstName": "무시됨" }
					""".formatted(SUBJECT, username)));
		});
		return new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET);
	}

	private ClientResponse jsonResponse(String body) {
		return ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.body(body)
				.build();
	}

	@Test
	void resolvesDisplayNameFromUsername() {
		KeycloakAdminClient client = clientReturning("sujin", 60);

		StepVerifier.create(client.displayName(SUBJECT))
				.expectNext(Optional.of("sujin"))
				.verifyComplete();

		ClientRequest request = lastUserRequest.get();
		assertThat(request.method()).isEqualTo(HttpMethod.GET);
		assertThat(request.url()).hasToString(INTERNAL_URL + "/admin/realms/" + REALM + "/users/" + SUBJECT);
		assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer admin-token");
	}

	/** 탈퇴 등으로 Keycloak에 그 subject가 없으면 예외가 아니라 빈 값 — 화면이 대체 문구를 정한다. */
	@Test
	void returnsEmptyWhenUserNotFound() {
		KeycloakAdminClient client = clientReturning(null, 60);

		StepVerifier.create(client.displayName(SUBJECT))
				.expectNext(Optional.empty())
				.verifyComplete();
	}

	/** 만료 전 두 번째 조회는 토큰을 다시 받지 않는다 — 매 조회마다 로그인하면 Keycloak에 부하가 쌓인다. */
	@Test
	void reusesTokenUntilItExpires() {
		KeycloakAdminClient client = clientReturning("sujin", 3600);

		client.displayName(SUBJECT).block();
		client.displayName("other-subject").block();

		assertThat(tokenRequests.get()).isEqualTo(1);
	}

	/** 만료 30초 전이면 재사용하지 않는다 — 요청 도중 만료되는 경합을 피하는 안전 여유분이다. */
	@Test
	void refetchesTokenNearExpiry() {
		KeycloakAdminClient client = clientReturning("sujin", 10);

		client.displayName(SUBJECT).block();
		client.displayName("other-subject").block();

		assertThat(tokenRequests.get()).isEqualTo(2);
	}

}
