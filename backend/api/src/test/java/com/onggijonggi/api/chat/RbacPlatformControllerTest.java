package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.authz.RbacProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : RbacPlatformControllerTest.java
 * Description : Verifies the phase-1 control plane remains available before RBAC enforcement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class})
class RbacPlatformControllerTest {

	@LocalServerPort
	private int port;
	@Autowired
	private RbacProperties rbacProperties;
	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void platformBootstrapRunsWithDefaultDisabledEnforcement() {
		// DB-TST-064: this first-PR control plane must not wait for tenant request enforcement.
		assertThat(rbacProperties.isEnforce()).isFalse();
		client.post().uri("/api/platform/rbac/bootstrap/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("platform", java.util.List.of("PLATFORM_ADMIN"),
								java.util.List.of("ogjg-client")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.OK)
				.expectBody()
				.jsonPath("$.processedTenants").isArray()
				.jsonPath("$.processedTenants.length()").isEqualTo(0);
	}

	@Test
	void userRoleCannotUsePlatformBootstrapEvenWhenEnforcementIsDisabled() {
		// DB-TST-064: PLATFORM_ADMIN boundary is independent from the future app.rbac.enforce switch.
		client.post().uri("/api/platform/rbac/bootstrap/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("user", java.util.List.of("USER"),
								java.util.List.of("ogjg-client")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void everyPlatformEndpointRejectsRequestsWithoutAToken() {
		for (String path : java.util.List.of("/api/platform/rbac/bootstrap/retry", "/api/platform/rbac/cutover-validation")) {
			client.post().uri(path).exchange()
					.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
					.expectBody().jsonPath("$.error.code").isEqualTo("UNAUTHENTICATED");
		}
	}

	@Test
	void userRoleCannotRunTheCutoverValidationEither() {
		// 사전 검증은 Keycloak 전체 사용자를 읽으므로 USER는 컨트롤러에 닿기 전에 막혀야 한다.
		client.post().uri("/api/platform/rbac/cutover-validation")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
				.expectBody().jsonPath("$.error.code").isEqualTo("FORBIDDEN");
	}

	@Test
	void theBoundaryCoversTheWholePlatformPrefixNotJustTheKnownEndpoints() {
		// 없는 경로나 다른 메서드도 USER에게는 404·405가 아니라 403이다 — 존재 여부를 드러내지 않는다.
		client.post().uri("/api/platform/rbac/anything-else")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
				.exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		client.get().uri("/api/platform/rbac/bootstrap/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
				.exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		client.delete().uri("/api/platform/rbac/cutover-validation")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
				.exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void platformAdminAloneIsNotEnoughWithAnInvalidTokenOrWrongAudience() {
		client.post().uri("/api/platform/rbac/bootstrap/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
				.expectBody().jsonPath("$.error.code").isEqualTo("TOKEN_INVALID");
		client.post().uri("/api/platform/rbac/bootstrap/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("platform", java.util.List.of("PLATFORM_ADMIN"), java.util.List.of("other-client")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void anExpiredPlatformAdminTokenIsRejected() {
		String expired = TestJwtSupport.signedJwtExpiringAt("platform", java.util.List.of("PLATFORM_ADMIN"),
				java.time.Instant.now().minusSeconds(3600));
		client.post().uri("/api/platform/rbac/bootstrap/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void platformAdminCannotCallTheUserApiWithoutTheUserRole() {
		// /api/**는 USER 역할을 요구하고 /api/platform/**는 PLATFORM_ADMIN을 요구한다. 두 경계는 서로를 포함하지 않는다.
		client.get().uri("/api/chat/sessions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("platform", java.util.List.of("PLATFORM_ADMIN"), java.util.List.of("ogjg-client")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}

	private static String userToken() {
		return TestJwtSupport.signedJwt("user", java.util.List.of("USER"), java.util.List.of("ogjg-client"));
	}
}
