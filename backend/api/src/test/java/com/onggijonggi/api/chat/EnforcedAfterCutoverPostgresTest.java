package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import com.onggijonggi.api.support.TestFiles;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

/**
 * Class Name : EnforcedAfterCutoverPostgresTest.java
 * Description : 판정이 켜진(app.rbac.enforce=true) 배포를 절체 뒤 스키마(thr.tnn_id·wrk_node_id NOT NULL, Tenant 복합 FK)에서
 *               실제 PostgreSQL 16으로 확인한다. 공유 Postgres 테스트 기반은 절체 앞 스키마에서 돌아 판정을 켠 상태의
 *               새 대화가 제약을 어기지 않는지 볼 수 없었다. 협업방은 고른 워크스페이스에, 1:1은 요청자 배정 Tenant의
 *               COMMON에 놓이고 자식 행이 같은 Tenant를 받는지, 워크스페이스를 빼면 400이고 배정이 없으면 403인지,
 *               다른 Tenant의 노드로 옮기는 것을 DB가 거부하는지를 본다. 판정(WorkspaceAuthorizer)은 모의로 두고 Tenant·노드는
 *               bootstrap이 만든다. 다른 테스트의 Tenant가 섞이지 않도록 전용 컨테이너를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.rbac.enforce=true")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class EnforcedAfterCutoverPostgresTest {

	private static final Path SETUP = TestFiles.tempYaml("enforced-cutover-setup-");

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("enforced_cutover").withUsername("test").withPassword("test");

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
	@MockitoBean
	private WorkspaceAuthorizer authorizer;
	@Autowired
	private RbacBootstrapService bootstrap;
	@Autowired
	private DirectChatTurnService directTurns;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void enforcedThreadsLandInTheirTenantAndTheDatabaseKeepsTenantsApart() throws Exception {
		when(authorizer.canCreateThread(any(), any())).thenReturn(Mono.just(true));
		when(authorizer.canView(any(), any())).thenReturn(Mono.just(true));
		Files.writeString(SETUP, """
				reconcile:
				  enabled: false
				tenants:
				  - tnn_key: alpha
				    name: 알파
				    status: ACTIVE
				    org_units:
				      - { key: hr, name: 인사팀, status: ACTIVE }
				    nodes:
				      - { node_key: hr, kind: ORG, parent: root, name: 인사팀, status: ACTIVE }
				    grants:
				      - { org_unit: hr, role: VIEWER, node: common }
				      - { org_unit: hr, role: ADMIN, node: hr }
				  - tnn_key: beta
				    name: 베타
				    status: ACTIVE
				    org_units:
				      - { key: ops, name: 운영팀, status: ACTIVE }
				    nodes:
				      - { node_key: lab, kind: ORG, parent: root, name: 실험실, status: ACTIVE }
				    grants:
				      - { org_unit: ops, role: VIEWER, node: common }
				      - { org_unit: ops, role: ADMIN, node: lab }
				""");
		assertThat(bootstrap.runCurrentConfiguration().failures()).isEmpty();
		UUID alpha = one("select id from tnn where tnn_key = 'alpha'");
		UUID alphaCommon = one("select id from wrk_node where tnn_id = ? and node_key = 'common'", alpha);
		UUID alphaHr = one("select id from wrk_node where tnn_id = ? and node_key = 'hr'", alpha);
		UUID betaLab = one("select id from wrk_node where node_key = 'lab'");
		UUID hrUnit = one("select id from org_unit where tnn_id = ? and org_unit_key = 'hr'", alpha);

		UUID assigned = user("assigned");
		jdbc.update("insert into org_unit_mbr (id, tnn_id, org_unit_id, subj, rank) values (?, ?, ?, 'assigned', 'S')",
				UUID.randomUUID(), alpha, hrUnit);
		UUID unassigned = user("unassigned");
		RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

		// 협업방은 고른 워크스페이스에 놓이고 자식 행이 같은 Tenant를 받는다.
		UUID collab = UUID.fromString(createCollab(client, "assigned", alphaHr).expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody().get("id").toString());
		assertThat(one("select wrk_node_id from thr where id = ?", collab)).isEqualTo(alphaHr);
		assertThat(one("select tnn_id from thr where id = ?", collab)).isEqualTo(alpha);
		assertThat(one("select tnn_id from thr_mbr where thr_id = ? limit 1", collab)).isEqualTo(alpha);

		// 판정이 켜져 있으면 워크스페이스를 빼도 기본 위치를 추측하지 않는다 — 검증 오류(400)이고 아무것도 만들어지지 않는다.
		long before = jdbc.queryForObject("select count(*) from thr", Long.class);
		createCollab(client, "assigned", null).expectStatus().isBadRequest().expectBody(Map.class)
				.value(body -> assertThat(((Map<?, ?>) body.get("error")).get("code")).isEqualTo("VALIDATION_ERROR"));
		assertThat(jdbc.queryForObject("select count(*) from thr", Long.class)).isEqualTo(before);

		// 1:1은 요청자 배정 Tenant의 COMMON에 놓이고, 배정이 없으면 만들 수 없다(403).
		UUID direct = UUID.randomUUID();
		directTurns.prepareOrCreateWithPendingAgentBlocking(direct, assigned, "안녕", List.of(), "제목", "key-" + UUID.randomUUID());
		assertThat(one("select wrk_node_id from thr where id = ?", direct)).isEqualTo(alphaCommon);
		assertThat(jdbc.queryForObject("select count(*) from msg where thr_id = ? and tnn_id = ?", Integer.class, direct, alpha))
				.isEqualTo(2);
		assertThatThrownBy(() -> directTurns.prepareOrCreateWithPendingAgentBlocking(UUID.randomUUID(), unassigned, "안녕", List.of(), "제목",
				"key-" + UUID.randomUUID()))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

		// 다른 Tenant의 노드로 옮기는 것은 DB의 Tenant 복합 FK가 거부한다.
		assertThatThrownBy(() -> jdbc.update("update thr set wrk_node_id = ? where id = ?", betaLab, collab))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_thr_workspace_tnn");
		assertThat(one("select wrk_node_id from thr where id = ?", collab)).isEqualTo(alphaHr);
	}

	private RestTestClient.ResponseSpec createCollab(RestTestClient client, String subject, UUID workspaceId) {
		Map<String, Object> body = new HashMap<>();
		body.put("title", "판정 켬 방");
		if (workspaceId != null) body.put("workspaceId", workspaceId.toString());
		return client.post().uri("/api/collab/threads")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER")))
				.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	private UUID one(String sql, Object... args) {
		return jdbc.queryForObject(sql, UUID.class, args);
	}

	private UUID user(String subject) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into app_user (id, keycloak_subj) values (?, ?)", id, subject);
		return id;
	}
}
