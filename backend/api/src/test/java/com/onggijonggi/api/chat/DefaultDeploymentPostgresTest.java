package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import com.onggijonggi.api.support.TestFiles;

import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.RbacProperties;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : DefaultDeploymentPostgresTest.java
 * Description : 권한 기능을 켜지 않은 기본 배포(casbin 프로필 없음, app.rbac.enforce=false)에서 새 대화가 만들어지는지 실제
 *               PostgreSQL 16에서 확인한다. 절체 뒤 thr.tnn_id·wrk_node_id가 NOT NULL이라, 대화를 놓을 COMMON이 없으면 INSERT가
 *               제약 위반(500)으로 실패했다. 이제 Tenant가 없으면 명확한 503으로 거절하고, 저장소가 주는 기본 설정
 *               (infra/config/workspace-setup.default.yml)을 bootstrap하면 협업방과 1:1이 모두 그 Tenant의 COMMON에 놓인다.
 *               다른 테스트의 Tenant가 섞이지 않도록 이 클래스가 전용 컨테이너를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class DefaultDeploymentPostgresTest {

	private static final Path SETUP = TestFiles.tempYaml("default-deploy-setup-");

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("default_deploy").withUsername("test").withPassword("test");

	@DynamicPropertySource
	static void postgres(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("app.rbac.workspace-setup-path", SETUP::toString);
	}

	@LocalServerPort
	private int port;
	@Autowired
	private RbacProperties rbac;
	@Autowired
	private RbacBootstrapService bootstrap;
	@Autowired
	private DirectChatTurnService directTurns;
	@Autowired
	private ThrRepository threads;
	@Autowired
	private JdbcTemplate jdbc;

	/** 한 컨테이너 안에서 순서가 의미를 가진다 — 먼저 Tenant가 없는 상태, 그다음 기본 설정을 적용한 상태를 본다. */
	@Test
	void newThreadsNeedTheDefaultTenantAndLandInItsCommon() throws Exception {
		assertThat(rbac.isEnforce()).as("기본 배포는 판정이 꺼져 있다").isFalse();
		assertThat(jdbc.queryForObject("select count(*) from tnn", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from ctv", Integer.class)).as("완료 표지는 migration이 자동으로 기록하지 않는다").isZero();
		RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		UUID user = user("default-user");

		// Tenant가 없으면 제약 위반(500)이 아니라 준비가 안 됐다는 503이다. 아무것도 저장되지 않는다.
		createCollab(client, "default-user", null).expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> directTurns.prepareOrCreateWithPendingAgentBlocking(UUID.randomUUID(), user, "안녕", List.of(), "제목", "key-" + UUID.randomUUID()))
				.isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		assertThat(threads.count()).isZero();

		// 저장소가 주는 기본 설정을 그대로 적용한다 — 이 파일이 유효한지도 함께 확인한다.
		Files.writeString(SETUP, Files.readString(Path.of("../../infra/config/workspace-setup.default.yml")));
		var result = bootstrap.runCurrentConfiguration();
		assertThat(result.failures()).isEmpty();
		UUID common = jdbc.queryForObject("select id from wrk_node where node_key = 'common'", UUID.class);
		UUID tenant = jdbc.queryForObject("select id from tnn where tnn_key = 'ogjg'", UUID.class);

		String collabId = createCollab(client, "default-user", null).expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody().get("id").toString();
		assertThat(jdbc.queryForObject("select wrk_node_id from thr where id = ?", UUID.class, UUID.fromString(collabId)))
				.isEqualTo(common);
		UUID directId = UUID.randomUUID();
		directTurns.prepareOrCreateWithPendingAgentBlocking(directId, user, "안녕", List.of(), "제목", "key-" + UUID.randomUUID());
		assertThat(jdbc.queryForObject("select wrk_node_id from thr where id = ?", UUID.class, directId)).isEqualTo(common);
		assertThat(jdbc.queryForObject("select tnn_id from thr where id = ?", UUID.class, directId)).isEqualTo(tenant);
		// 자식 행의 Tenant도 Thread에서 채워진다.
		assertThat(jdbc.queryForObject("select count(*) from msg where thr_id = ? and tnn_id = ?", Integer.class, directId, tenant))
				.isEqualTo(2);
	}

	private RestTestClient.ResponseSpec createCollab(RestTestClient client, String subject, UUID workspaceId) {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("title", "기본 배포 방");
		if (workspaceId != null) body.put("workspaceId", workspaceId.toString());
		return client.post().uri("/api/collab/threads")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER")))
				.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	private UUID user(String subject) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into app_user (id, keycloak_subj) values (?, ?)", id, subject);
		return id;
	}
}
