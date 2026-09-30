package com.onggijonggi.api.chat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : AuthorizationAuditControllerTest.java
 * Description : 권한 변경 감사 조회(#259)의 HTTP 경계를 Docker 없이(H2) 검증한다 — 토큰 없으면 401, USER는 control plane 경로
 *               403, 틀린 필터·경로 값은 400 봉투, 없는 Workspace·Tenant는 404. 판정·조회 자체는
 *               AuthorizationAuditQueryPostgresTest가 실제 PostgreSQL로 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ FakeChatModelConfig.class, FakeJwtDecoderConfig.class })
class AuthorizationAuditControllerTest {

	@LocalServerPort
	private int port;
	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void bothPathsRequireAToken() {
		for (String path : List.of("/api/workspaces/" + UUID.randomUUID() + "/authorization-audits",
				"/api/platform/rbac/tenants/" + UUID.randomUUID() + "/authorization-audits")) {
			client.get().uri(path).exchange()
					.expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
					.expectBody().jsonPath("$.error.code").isEqualTo("UNAUTHENTICATED");
		}
	}

	@Test
	void aUserCannotUseTheTenantWidePath() {
		get("/api/platform/rbac/tenants/" + UUID.randomUUID() + "/authorization-audits", List.of("USER"))
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unknownWorkspacesAndTenantsAreNotFound() {
		get("/api/workspaces/" + UUID.randomUUID() + "/authorization-audits", List.of("USER"))
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
				.expectBody().jsonPath("$.error.code").isEqualTo("NOT_FOUND");
		get("/api/platform/rbac/tenants/" + UUID.randomUUID() + "/authorization-audits", List.of("PLATFORM_ADMIN"))
				.expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void invalidFiltersAreBadRequestsBeforeAnyLookup() {
		String base = "/api/workspaces/" + UUID.randomUUID() + "/authorization-audits?";
		for (String query : List.of("limit=0", "limit=101", "eventKind=NOPE", "actorKind=ROBOT", "actorUserId=nope",
				"from=yesterday", "from=2026-09-02T00:00:00Z&to=2026-09-01T00:00:00Z", "cursor=garbage")) {
			get(base + query, List.of("USER"))
					.expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
					.expectBody().jsonPath("$.error.code").exists();
		}
		get("/api/workspaces/not-a-uuid/authorization-audits", List.of("USER"))
				.expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
				.expectBody().jsonPath("$.error.code").exists();
	}

	private RestTestClient.ResponseSpec get(String path, List<String> roles) {
		return client.get().uri(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("audit-" + UUID.randomUUID(), roles))
				.exchange();
	}
}
