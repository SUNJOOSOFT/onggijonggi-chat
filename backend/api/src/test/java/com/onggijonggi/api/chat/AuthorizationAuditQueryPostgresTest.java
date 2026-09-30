package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.authz.AuthorizationAuditPage;
import com.onggijonggi.api.authz.AuthorizationAuditView;
import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : AuthorizationAuditQueryPostgresTest.java
 * Description : 권한 변경 감사 조회(#259)를 실제 PostgreSQL 위에서 HTTP로 검증한다. 판정 스위치는 기본값(꺼짐)이다 —
 *               감사 조회의 직접 MANAGE 판정은 스위치와 무관하게 DB 부여로 엄격히 동작해야 한다.
 *               트리·부여는 bootstrap이 만들고, 배정과 감사 행은 JDBC로 넣는다. 테스트가 넣는 감사 행은 bootstrap 행과
 *               섞이지 않게 먼 미래 시각에 두고 from 필터로 가른다. Tenant는 지울 수 없어 테스트마다 태그 붙인 key를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationAuditQueryPostgresTest extends PostgresSpringTestBase {

	/** 테스트가 넣는 감사 행의 기준 시각. bootstrap이 지금 남기는 행보다 뒤라 from 필터로 가를 수 있다. */
	private static final Instant BASE = Instant.parse("2031-01-01T00:00:00.123456Z");
	private static final String SINCE_BASE = "from=" + BASE.minusSeconds(3600);

	@LocalServerPort
	private int port;
	@Autowired
	private RbacBootstrapService bootstrap;
	@Autowired
	private TenantRepository tenants;
	@Autowired
	private WorkspaceNodeRepository nodes;
	@Autowired
	private JdbcTemplate jdbc;

	private RestTestClient client;
	private String tag;
	private UUID tenantId;
	private UUID hrTeam;
	private UUID hr;
	private UUID payroll;
	private UUID ops;
	private String hrAdmin;

	@BeforeEach
	void setUp() throws Exception {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		tag = "a" + UUID.randomUUID().toString().substring(0, 8);
		tenantId = bootstrapTenant("audit-" + tag);
		hr = node(tenantId, "hr");
		payroll = node(tenantId, "payroll");
		ops = node(tenantId, "ops");
		hrTeam = jdbc.queryForObject("select id from org_unit where tnn_id = ? and org_unit_key = 'hr'", UUID.class, tenantId);
		hrAdmin = "hr-admin-" + tag;
		assign(hrAdmin, tenantId, hrTeam);
	}

	// ------------------------------------------------------------------ 커서

	@Test
	void pagesThroughTiesWithoutDuplicatesOrGaps() {
		// 같은 시각에 UUID만 다른 행 다섯과 그보다 앞선 행 둘 — 동률은 id로 순서가 고정돼야 한다.
		for (int i = 0; i < 5; i++) audit(tenantId, hr, "NODE_RENAMED", "WORKSPACE", BASE, null, null);
		for (int i = 0; i < 2; i++) audit(tenantId, hr, "NODE_RENAMED", "WORKSPACE", BASE.minusSeconds(1), null, null);

		List<UUID> seen = new ArrayList<>();
		List<Integer> sizes = new ArrayList<>();
		String cursor = null;
		do {
			AuthorizationAuditPage page = ok(workspace(hrAdmin, hr, SINCE_BASE + "&limit=3" + (cursor == null ? "" : "&cursor=" + cursor)));
			page.items().forEach(item -> seen.add(item.id()));
			sizes.add(page.items().size());
			cursor = page.nextCursor();
		} while (cursor != null);

		List<UUID> expected = jdbc.queryForList("""
				select id from authz_adt where tnn_id = ? and wrk_node_id = ? and created_at >= ?
				order by created_at desc, id desc""", UUID.class, tenantId, hr, BASE.minusSeconds(3600).atOffset(ZoneOffset.UTC));
		assertThat(seen).hasSize(7).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
		assertThat(sizes).containsExactly(3, 3, 1);
	}

	@Test
	void anExactPageSizeEndsWithoutANextCursor() {
		for (int i = 0; i < 3; i++) audit(tenantId, hr, "NODE_RENAMED", "WORKSPACE", BASE.plusSeconds(i), null, null);

		AuthorizationAuditPage page = ok(workspace(hrAdmin, hr, SINCE_BASE + "&limit=3"));

		assertThat(page.items()).hasSize(3);
		assertThat(page.nextCursor()).isNull();
		assertThat(page.items().get(0).createdAt()).isEqualTo(BASE.plusSeconds(2));
	}

	// ------------------------------------------------------------------ 범위

	@Test
	void theWorkspacePathSeesOnlyRowsOfThatExactWorkspace() {
		UUID own = audit(tenantId, hr, "POLICY_ADDED", "POLICY", BASE, null, null);
		audit(tenantId, payroll, "POLICY_ADDED", "POLICY", BASE, null, null);
		audit(tenantId, ops, "POLICY_ADDED", "POLICY", BASE, null, null);
		audit(tenantId, null, "ORG_UNIT_RENAMED", "ORG_UNIT", BASE, null, null);

		AuthorizationAuditPage page = ok(workspace(hrAdmin, hr, SINCE_BASE));

		assertThat(page.items()).extracting(AuthorizationAuditView::id).containsExactly(own);
	}

	@Test
	void manageOnTheParentDoesNotOpenTheChildAndOutsidersAreRejected() {
		// hr 팀은 hr에만 ADMIN이다. 하위 payroll의 감사는 볼 수 없다(상속 없음).
		workspace(hrAdmin, payroll, SINCE_BASE).expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		// ops 팀 사람은 hr에 MANAGE가 없고, 배정이 없는 사람도 마찬가지다.
		String opsMember = "ops-" + tag;
		assign(opsMember, tenantId, jdbc.queryForObject("select id from org_unit where tnn_id = ? and org_unit_key = 'ops'",
				UUID.class, tenantId));
		workspace(opsMember, hr, SINCE_BASE).expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		workspace("nobody-" + tag, hr, SINCE_BASE).expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		// PLATFORM_ADMIN 역할만으로 일반 ADMIN 경로를 통과하지 않는다.
		client.get().uri("/api/workspaces/" + hr + "/authorization-audits")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("platform-" + tag, List.of("USER", "PLATFORM_ADMIN")))
				.exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		workspace(hrAdmin, UUID.randomUUID(), SINCE_BASE).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void anInactiveWorkspaceIsClosedToTheAdminButItsHistoryStaysVisibleToThePlatform() {
		UUID row = audit(tenantId, ops, "NODE_DEACTIVATED", "WORKSPACE", BASE, null, null);
		String opsAdmin = "ops-admin-" + tag;
		assign(opsAdmin, tenantId, jdbc.queryForObject("select id from org_unit where tnn_id = ? and org_unit_key = 'ops'",
				UUID.class, tenantId));
		ok(workspace(opsAdmin, ops, SINCE_BASE));

		jdbc.update("update wrk_node set status = 'INACTIVE', inactive_at = now() where id = ?", ops);

		workspace(opsAdmin, ops, SINCE_BASE).expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(ok(platform(tenantId, SINCE_BASE)).items()).extracting(AuthorizationAuditView::id).contains(row);
	}

	@Test
	void thePlatformSeesTheWholeTenantEvenWhenInactiveButNeverAnotherTenant() throws Exception {
		UUID other = bootstrapTenant("other-" + tag);
		UUID onNode = audit(tenantId, hr, "POLICY_ADDED", "POLICY", BASE, null, null);
		UUID onChild = audit(tenantId, payroll, "POLICY_ADDED", "POLICY", BASE, null, null);
		UUID noNode = audit(tenantId, null, "ORG_UNIT_RENAMED", "ORG_UNIT", BASE, null, null);
		UUID elsewhere = audit(other, node(other, "hr"), "POLICY_ADDED", "POLICY", BASE, null, null);
		jdbc.update("update tnn set status = 'INACTIVE', inactive_at = now() where id = ?", tenantId);

		AuthorizationAuditPage page = ok(platform(tenantId, SINCE_BASE));

		assertThat(page.items()).extracting(AuthorizationAuditView::id).containsExactlyInAnyOrder(onNode, onChild, noNode);
		assertThat(page.items()).extracting(AuthorizationAuditView::id).doesNotContain(elsewhere);
		// 비활성 Tenant의 일반 ADMIN 경로는 닫힌다.
		workspace(hrAdmin, hr, SINCE_BASE).expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		platform(UUID.randomUUID(), SINCE_BASE).expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void aCursorFromAnotherTenantNeverLeaksThatTenant() throws Exception {
		UUID other = bootstrapTenant("leak-" + tag);
		for (int i = 0; i < 3; i++) audit(other, null, "ORG_UNIT_RENAMED", "ORG_UNIT", BASE.plusSeconds(i), null, null);
		UUID mine = audit(tenantId, null, "ORG_UNIT_RENAMED", "ORG_UNIT", BASE, null, null);
		String foreignCursor = ok(platform(other, SINCE_BASE + "&limit=1")).nextCursor();
		assertThat(foreignCursor).isNotNull();

		AuthorizationAuditPage page = ok(platform(tenantId, SINCE_BASE + "&cursor=" + foreignCursor));

		assertThat(page.items()).extracting(AuthorizationAuditView::tenantId).containsOnly(tenantId);
		assertThat(page.items()).extracting(AuthorizationAuditView::id).containsExactly(mine);
	}

	// ------------------------------------------------------------------ 필터·응답

	@Test
	void filtersApplyAloneAndTogether() {
		UUID actor = user("actor-" + tag);
		UUID renamed = audit(tenantId, hr, "NODE_RENAMED", "WORKSPACE", BASE, null, "req-1");
		UUID added = audit(tenantId, hr, "POLICY_ADDED", "POLICY", BASE.plusSeconds(10), null, "req-1");
		UUID byUser = audit(tenantId, hr, "POLICY_ADDED", "POLICY", BASE.plusSeconds(20), actor, "req-2");

		assertThat(ids(workspace(hrAdmin, hr, SINCE_BASE + "&eventKind=POLICY_ADDED"))).containsExactly(byUser, added);
		assertThat(ids(workspace(hrAdmin, hr, SINCE_BASE + "&targetKind=WORKSPACE"))).containsExactly(renamed);
		assertThat(ids(workspace(hrAdmin, hr, SINCE_BASE + "&actorKind=USER"))).containsExactly(byUser);
		assertThat(ids(workspace(hrAdmin, hr, SINCE_BASE + "&actorUserId=" + actor))).containsExactly(byUser);
		assertThat(ids(workspace(hrAdmin, hr, SINCE_BASE + "&requestId=req-1"))).containsExactly(added, renamed);
		assertThat(ids(workspace(hrAdmin, hr, "from=" + BASE.plusSeconds(5) + "&to=" + BASE.plusSeconds(20))))
				.containsExactly(added);
		assertThat(ids(workspace(hrAdmin, hr, SINCE_BASE + "&eventKind=POLICY_ADDED&requestId=req-1&actorKind=SYSTEM")))
				.containsExactly(added);
	}

	@Test
	void returnsJsonColumnsAsJsonValuesAndNeverWrites() {
		audit(tenantId, hr, "POLICY_ADDED", "POLICY", BASE, null, "req-json");
		Integer before = jdbc.queryForObject("select count(*) from authz_adt", Integer.class);

		String body = workspace(hrAdmin, hr, SINCE_BASE + "&requestId=req-json").expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();
		JsonNode page = new JsonMapper().readTree(body);
		JsonNode item = page.get("items").get(0);

		assertThat(item.get("targetRef").isObject()).isTrue();
		assertThat(item.get("targetRef").get("node_key").asString()).isEqualTo("hr");
		assertThat(item.get("afterJson").get("role").asString()).isEqualTo("ADMIN");
		assertThat(item.get("actorRoleJson").isArray()).isTrue();
		assertThat(item.get("traceId").asString()).isEqualTo("trace-" + tag);
		// 빈 값은 필드를 빼지 않고 null로 내보낸다 — 마지막 페이지의 nextCursor도 같다.
		assertThat(item.has("beforeJson")).isTrue();
		assertThat(item.get("beforeJson").isNull()).isTrue();
		assertThat(page.has("nextCursor")).isTrue();
		assertThat(page.get("nextCursor").isNull()).isTrue();

		assertThat(jdbc.queryForObject("select count(*) from authz_adt", Integer.class)).isEqualTo(before);
	}

	@Test
	void revokingTheAssignmentClosesTheNextPageEvenWithAValidCursor() {
		for (int i = 0; i < 3; i++) audit(tenantId, hr, "NODE_RENAMED", "WORKSPACE", BASE.plusSeconds(i), null, null);
		String cursor = ok(workspace(hrAdmin, hr, SINCE_BASE + "&limit=1")).nextCursor();

		jdbc.update("delete from org_unit_mbr where subj = ?", hrAdmin);

		workspace(hrAdmin, hr, SINCE_BASE + "&limit=1&cursor=" + cursor).expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------ 도우미

	/** hr(하위 payroll)과 ops 두 팀·노드를 가진 Tenant를 bootstrap으로 만든다. hr 팀은 hr에, ops 팀은 ops에 ADMIN이다. */
	private UUID bootstrapTenant(String key) throws Exception {
		Files.writeString(BOOTSTRAP_CONFIG, """
				reconcile:
				  enabled: false
				tenants:
				  - tnn_key: %s
				    name: 감사 조회 시험
				    status: ACTIVE
				    org_units:
				      - { key: hr, name: 인사팀, status: ACTIVE }
				      - { key: ops, name: 운영팀, status: ACTIVE }
				    nodes:
				      - { node_key: hr, kind: ORG, parent: root, name: 인사팀, status: ACTIVE }
				      - { node_key: payroll, kind: WORK, parent: hr, name: 급여, status: ACTIVE }
				      - { node_key: ops, kind: ORG, parent: root, name: 운영팀, status: ACTIVE }
				    grants:
				      - { org_unit: hr, role: VIEWER, node: common }
				      - { org_unit: ops, role: VIEWER, node: common }
				      - { org_unit: hr, role: ADMIN, node: hr }
				      - { org_unit: ops, role: ADMIN, node: ops }
				      - { org_unit: ops, role: ADMIN, node: payroll }
				""".formatted(key));
		bootstrap.runCurrentConfiguration();
		return tenants.findByKey(key).orElseThrow().getId();
	}

	private UUID node(UUID tenant, String key) {
		return nodes.findByTenantIdAndKey(tenant, key).orElseThrow().getId();
	}

	private void assign(String subject, UUID tenant, UUID team) {
		jdbc.update("insert into org_unit_mbr (id, tnn_id, org_unit_id, subj, rank) values (?, ?, ?, ?, 'S')",
				UUID.randomUUID(), tenant, team, subject);
	}

	private UUID user(String subject) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into app_user (id, keycloak_subj) values (?, ?)", id, subject);
		return id;
	}

	/** 감사 행 하나. actorUserId가 있으면 USER 행, 없으면 SYSTEM 행이다. */
	private UUID audit(UUID tenant, UUID node, String event, String target, Instant at, UUID actorUserId, String requestId) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into authz_adt (id, tnn_id, act_kind, act_user_id, act_role_json, evt_kind, trg_kind, trg_ref,
				                       wrk_node_id, aft_json, req_id, trc_id, created_at)
				values (?, ?, ?, ?, '["ADMIN"]'::jsonb, ?, ?, '{"node_key":"hr"}'::jsonb, ?, '{"role":"ADMIN"}'::jsonb, ?, ?, ?)
				""", id, tenant, actorUserId == null ? "SYSTEM" : "USER", actorUserId, event, target, node, requestId,
				"trace-" + tag, at.atOffset(ZoneOffset.UTC));
		return id;
	}

	private RestTestClient.ResponseSpec workspace(String subject, UUID workspaceId, String query) {
		return client.get().uri("/api/workspaces/" + workspaceId + "/authorization-audits?" + query)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER")))
				.exchange();
	}

	private RestTestClient.ResponseSpec platform(UUID tenant, String query) {
		return client.get().uri("/api/platform/rbac/tenants/" + tenant + "/authorization-audits?" + query)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("platform-" + tag, List.of("PLATFORM_ADMIN")))
				.exchange();
	}

	private static AuthorizationAuditPage ok(RestTestClient.ResponseSpec response) {
		return response.expectStatus().isOk().expectBody(AuthorizationAuditPage.class).returnResult().getResponseBody();
	}

	private static List<UUID> ids(RestTestClient.ResponseSpec response) {
		return ok(response).items().stream().map(AuthorizationAuditView::id).toList();
	}
}
