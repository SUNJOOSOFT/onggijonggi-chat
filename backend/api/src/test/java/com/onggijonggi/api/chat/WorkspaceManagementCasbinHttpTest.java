package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Class Name : WorkspaceManagementCasbinHttpTest.java
 * Description : Workspace·부여 관리(#260)를 운영과 같은 casbin 프로필로 띄워 HTTP로 검증한다 — 실제 PostgreSQL과 실제
 *               casbin-server 컨테이너를 쓴다. 판정 스위치는 프로필대로 켜져 있다. 확인하는 것:
 *               - 관리 컨트롤러가 casbin 프로필에서 떠 있고, 직접 MANAGE가 없으면 403이다.
 *               - 감사 행에 JWT의 역할이 행위자 역할로 남는다.
 *               - 커밋 뒤 Casbin 규칙이 다시 적재돼 판정이 곧바로 바뀐다(부여 추가 → 보임, 삭제 → 안 보임).
 *               - org-unit 경로는 control plane이라 USER는 403이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({ "test", "casbin" })
class WorkspaceManagementCasbinHttpTest extends PostgresSpringTestBase {

	private static GenericContainer<?> casbin;

	/** Postgres처럼 JVM당 한 번만 띄운다 — 캐시된 컨텍스트가 처음 주소를 계속 쓴다. */
	@SuppressWarnings("resource")
	static synchronized GenericContainer<?> casbin() {
		if (casbin == null) {
			casbin = new GenericContainer<>("casbin/casbin-server:v1.19.0").withExposedPorts(50051)
					.waitingFor(Wait.forListeningPort());
			casbin.start();
		}
		return casbin;
	}

	@DynamicPropertySource
	static void casbinAddress(DynamicPropertyRegistry registry) {
		registry.add("app.casbin.address", () -> casbin().getHost() + ":" + casbin().getMappedPort(50051));
	}

	@LocalServerPort
	private int port;
	@Autowired
	private RbacBootstrapService bootstrap;
	@Autowired
	private TenantRepository tenants;
	@Autowired
	private WorkspaceNodeRepository nodes;
	@Autowired
	private WorkspaceAuthorizer authorizer;
	@Autowired
	private JdbcTemplate jdbc;

	private RestTestClient client;
	private String tag;
	private String tenantKey;
	private UUID tenantId;
	private UUID hr;
	private UUID opsTeam;
	private String hrAdmin;
	private String opsMember;

	@BeforeEach
	void setUp() throws Exception {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		tag = "h" + UUID.randomUUID().toString().substring(0, 8);
		tenantKey = "http-" + tag;
		Files.writeString(BOOTSTRAP_CONFIG, """
				reconcile:
				  enabled: false
				tenants:
				  - tnn_key: %s
				    name: 관리 HTTP 시험
				    status: ACTIVE
				    org_units:
				      - { key: hr, name: 인사팀, status: ACTIVE }
				      - { key: ops, name: 운영팀, status: ACTIVE }
				    nodes:
				      - { node_key: hr, kind: ORG, parent: root, name: 인사팀, status: ACTIVE }
				      - { node_key: ops, kind: ORG, parent: root, name: 운영팀, status: ACTIVE }
				    grants:
				      - { org_unit: hr, role: VIEWER, node: common }
				      - { org_unit: ops, role: VIEWER, node: common }
				      - { org_unit: hr, role: ADMIN, node: hr }
				      - { org_unit: ops, role: ADMIN, node: ops }
				""".formatted(tenantKey));
		bootstrap.runCurrentConfiguration();
		tenantId = tenants.findByKey(tenantKey).orElseThrow().getId();
		hr = nodes.findByTenantIdAndKey(tenantId, "hr").orElseThrow().getId();
		opsTeam = team("ops");
		hrAdmin = assign("hr-admin", team("hr"));
		opsMember = assign("ops-member", opsTeam);
	}

	@Test
	void createsANodeAndTheNewGrantIsEnforcedRightAfterCommit() {
		UUID project = created(post(hrAdmin, List.of("USER"), "/api/rbac/workspaces",
				Map.of("parentId", hr.toString(), "kind", "WORK", "name", "프로젝트")));

		// 커밋 뒤 전체 적재로 새 노드의 ADMIN 부여가 곧바로 판정에 들어온다.
		assertThat(authorizer.canView(hrAdmin, project).block()).isTrue();
		assertThat(authorizer.canView(opsMember, project).block()).isFalse();
		assertThat(jdbc.queryForObject("""
				select act_role_json::text from authz_adt where tnn_id = ? and evt_kind = 'NODE_CREATED' and act_kind = 'USER'
				""", String.class, tenantId)).contains("USER");

		UUID grant = created(post(hrAdmin, List.of("USER"), "/api/rbac/workspaces/" + project + "/grants",
				Map.of("orgUnitId", opsTeam.toString(), "role", "VIEWER")));
		assertThat(authorizer.canView(opsMember, project).block()).isTrue();

		client.delete().uri("/api/rbac/grants/" + grant).header(HttpHeaders.AUTHORIZATION, bearer(hrAdmin, List.of("USER")))
				.exchange().expectStatus().isNoContent();
		assertThat(authorizer.canView(opsMember, project).block()).isFalse();
	}

	@Test
	void auditRowsCarryTheResponseTraceIdAndTheBootstrapKeyShape() {
		String traceId = post(hrAdmin, List.of("USER"), "/api/rbac/workspaces",
				Map.of("parentId", hr.toString(), "kind", "WORK", "name", "추적"))
				.expectStatus().isCreated().returnResult(String.class).getResponseHeaders().getFirst("X-Trace-Id");

		// 한 요청의 두 행(NODE_CREATED·POLICY_ADDED)은 응답의 X-Trace-Id로 찾을 수 있고 같은 req_id로 묶인다.
		assertThat(traceId).isNotBlank();
		assertThat(jdbc.queryForList("select evt_kind from authz_adt where trc_id = ?", String.class, traceId))
				.containsExactlyInAnyOrder("NODE_CREATED", "POLICY_ADDED");
		assertThat(jdbc.queryForObject("select count(distinct req_id) from authz_adt where trc_id = ?", Integer.class, traceId))
				.isEqualTo(1);
		// 대상 참조는 bootstrap의 SYSTEM 행과 같은 키를 쓴다 — 감사 조회가 행위자와 무관하게 같은 키로 읽는다.
		assertThat(jdbc.queryForObject("""
				select trg_ref::text from authz_adt where trc_id = ? and evt_kind = 'NODE_CREATED'""", String.class, traceId))
				.contains("\"wrk_node_id\"", "\"node_key\": \"n-");
		assertThat(jdbc.queryForObject("""
				select trg_ref::text from authz_adt where trc_id = ? and evt_kind = 'POLICY_ADDED'""", String.class, traceId))
				.contains("\"wrk_grn_id\"", "\"org_unit_key\": \"hr\"", "\"role\": \"ADMIN\"", "\"wrk_node_id\"");
		assertThat(jdbc.queryForObject("""
				select aft_json::text from authz_adt where trc_id = ? and evt_kind = 'NODE_CREATED'""", String.class, traceId))
				.contains("\"node_key\"", "\"prn_id\"", "\"inactive_at\"");
	}

	@Test
	void writesWithoutDirectManageAndControlPlaneCallsByUsersAreForbidden() {
		post(opsMember, List.of("USER"), "/api/rbac/workspaces",
				Map.of("parentId", hr.toString(), "kind", "WORK", "name", "남의 방"))
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		post(hrAdmin, List.of("USER"), "/api/platform/rbac/tenants/" + tenantKey + "/org-units",
				Map.of("key", "tmp-" + tag, "name", "임시팀"))
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

		UUID unit = created(post("platform-" + tag, List.of("USER", "PLATFORM_ADMIN"),
				"/api/platform/rbac/tenants/" + tenantKey + "/org-units", Map.of("key", "tmp-" + tag, "name", "임시팀")));
		assertThat(jdbc.queryForObject("select tnn_id from org_unit where id = ?", UUID.class, unit)).isEqualTo(tenantId);
	}

	private UUID team(String key) {
		return jdbc.queryForObject("select id from org_unit where tnn_id = ? and org_unit_key = ?", UUID.class, tenantId, key);
	}

	/** 배정(사원)만 넣는다. app_user는 첫 HTTP 요청이 만든다. */
	private String assign(String name, UUID orgUnit) {
		String subject = name + "-" + tag;
		jdbc.update("insert into org_unit_mbr (id, tnn_id, org_unit_id, subj, rank) values (?, ?, ?, ?, 'S')",
				UUID.randomUUID(), tenantId, orgUnit, subject);
		return subject;
	}

	private RestTestClient.ResponseSpec post(String subject, List<String> roles, String path, Map<String, String> body) {
		return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer(subject, roles))
				.contentType(MediaType.APPLICATION_JSON).body(body).exchange();
	}

	@SuppressWarnings("unchecked")
	private static UUID created(RestTestClient.ResponseSpec response) {
		Map<String, String> body = response.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return UUID.fromString(body.get("id"));
	}

	private static String bearer(String subject, List<String> roles) {
		return "Bearer " + TestJwtSupport.signedJwt(subject, roles);
	}
}
