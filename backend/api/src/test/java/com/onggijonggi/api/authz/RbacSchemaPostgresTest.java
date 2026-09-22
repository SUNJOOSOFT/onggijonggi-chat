package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : RbacSchemaPostgresTest.java
 * Description : H2가 재현하지 못하는 v0.3 DB 규칙(trigger·CHECK·unique·FK·부분 인덱스)을 실제 PostgreSQL 16에
 *               전체 Flyway를 적용해 검증한다. 거부는 SQLState와 메시지로 원인까지 확인한다(엉뚱한 이유로 실패해도
 *               통과하는 느슨한 단정을 피한다). 테스트마다 빈 DB에서 시작한다.
 *               P0001 = trigger의 raise exception, 23505 = unique 위반, 23514 = CHECK 위반, 23503 = FK 위반.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacSchemaPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
				.withDatabaseName("rbac_test")
				.withUsername("test")
				.withPassword("test");

	@BeforeEach
	void migrateEmptyDatabase() {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").cleanDisabled(false).load().clean();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
	}

	// ------------------------------------------------------------------ Workspace 트리

	@Test
	void invalidNodeShapesAreRejected() throws SQLException {
		// DB-TST-002(자기·조상 참조, path 위조), 003(활성 형제 이름·두 번째 ROOT·COMMON), 029(kind 불변·물리 삭제 금지),
		// 053(깊이 상한), 017(ROOT·COMMON 상태 변경 금지), DB-INV-WS(node_key 예약·표시명)
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "shape");
			UUID root = root(c, tenant);
			UUID common = node(c, tenant, root, "common", "COMMON", "Common");
			UUID sales = node(c, tenant, root, "sales", "ORG", "Sales");

			// path 위조: 부모 경로를 따르지 않는 경로
			assertRejected("P0001", "path must follow the parent path", () -> execute(c,
					"insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'forged', 'WORK', 'Forged', array[?, ?]::uuid[])",
					UUID.randomUUID(), tenant, sales, root, UUID.randomUUID()));
			// 자기 자신을 부모로: 순환
			assertRejected("P0001", "creates a cycle", () -> execute(c, "update wrk_node set prn_id = id where id = ?", sales));
			// COMMON은 ROOT 직속 leaf만
			assertRejected("P0001", "COMMON must be a direct ROOT child", () -> execute(c,
					"insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) select ?, ?, id, 'common2', 'COMMON', 'Common2', path || ?::uuid from wrk_node where id = ?",
					UUID.randomUUID(), tenant, UUID.randomUUID(), sales));
			assertRejected("P0001", "COMMON is a leaf workspace", () -> execute(c,
					"insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) select ?, ?, id, 'under-common', 'WORK', 'UnderCommon', path || ?::uuid from wrk_node where id = ?",
					UUID.randomUUID(), tenant, UUID.randomUUID(), common));
			// 두 번째 ROOT·COMMON은 unique가 거부한다
			assertRejected("23505", null, () -> execute(c,
					"insert into wrk_node (id, tnn_id, node_key, kind, name, path) select g, ?, 'root', 'ROOT', 'Root2', array[g] from (select gen_random_uuid() g) x",
					tenant));
			assertRejected("23505", null, () -> node(c, tenant, root, "common", "COMMON", "Common2"));
			// 같은 부모 아래 활성 형제 이름은 종류와 무관하고 대소문자를 무시하고 유일하다
			assertRejected("23505", null, () -> node(c, tenant, root, "sales-2", "WORK", "SALES"));
			// node_key는 root·common을 kind가 독점하고, 표시명은 비어 있으면 안 된다
			assertRejected("23514", "wrk_node_key_reserved", () -> node(c, tenant, root, "common", "ORG", "Other"));
			assertRejected("23514", "wrk_node_key_reserved", () -> node(c, tenant, root, "root", "ORG", "Other2"));
			assertRejected("23514", "wrk_node_name_trimmed", () -> node(c, tenant, root, "blank", "ORG", "   "));
			assertRejected("23514", "wrk_node_name_trimmed", () -> node(c, tenant, root, "blank-tab", "ORG", "\t"));
			assertRejected("23514", "wrk_node_name_trimmed", () -> node(c, tenant, root, "padded", "ORG", " Padded"));
			assertRejected("23514", "wrk_node_name_trimmed", () -> node(c, tenant, root, "padded-2", "ORG", "Padded\n"));
			// 가운데 개행·제어문자도 막는다. 표시명은 목록·감사 로그에 그대로 나간다.
			assertRejected("23514", "wrk_node_name_trimmed", () -> node(c, tenant, root, "inner-nl", "ORG", "a\nb"));
			assertRejected("23514", "wrk_node_name_trimmed", () -> node(c, tenant, root, "inner-ctl", "ORG", "a\u0001b"));
			// 가운데 보통 공백은 정상이다.
			node(c, tenant, root, "spaced", "ORG", "영업 본부");
			// kind 불변, 물리 삭제 금지, ROOT·COMMON 상태 변경 금지
			assertRejected("P0001", "kind is immutable", () -> execute(c, "update wrk_node set kind = 'WORK' where id = ?", sales));
			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from wrk_node where id = ?", sales));
			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from wrk_node where id = ?", root));
			assertRejected("P0001", "ROOT and COMMON follow tenant lifecycle only", () -> execute(c,
					"update wrk_node set status = 'INACTIVE', inactive_at = now() where id = ?", common));
		}
	}

	@Test
	void treeDepthIsCappedAtTenLevelsUnderRoot() throws SQLException {
		// DB-TST-053: ROOT 아래 10단(path 길이 11)은 허용, 11단은 거부한다.
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "depth");
			UUID parent = root(c, tenant);
			for (int level = 1; level <= 10; level++) parent = node(c, tenant, parent, "n" + level, "WORK", "Level " + level);
			UUID deepest = parent;
			assertThat(pathLength(c, deepest)).isEqualTo(11);
			assertRejected("P0001", "depth exceeds 11", () -> node(c, tenant, deepest, "n11", "WORK", "Level 11"));
		}
	}

	@Test
	void deactivationRunsBottomUpAndInactiveParentsStillAllowChildMaintenance() throws SQLException {
		// DB-TST-039(비활성 부모 아래 생성 거부), 040(같은 이름 활성 형제가 있으면 재활성화 거부),
		// 017(비활성 부모 아래 재활성화 거부). 부모가 꺼진 뒤에도 자식의 이름 변경·비활성화는 되어야 한다.
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "lifecycle");
			UUID root = root(c, tenant);
			node(c, tenant, root, "common", "COMMON", "Common");
			UUID parent = node(c, tenant, root, "parent", "ORG", "Parent");
			UUID child = node(c, tenant, parent, "child", "WORK", "Child");

			assertRejected("P0001", "deactivate child workspaces first", () -> deactivate(c, parent));
			deactivate(c, child);
			deactivate(c, parent);

			execute(c, "update wrk_node set name = 'Child Renamed' where id = ?", child);
			assertRejected("P0001", "workspace parent must be active", () -> node(c, tenant, parent, "late", "WORK", "Late"));
			assertRejected("P0001", "workspace parent must be active", () -> activate(c, child));

			// 부모를 다시 켜기 전에, 같은 이름의 활성 형제가 새로 생기면 재활성화가 거부된다.
			node(c, tenant, root, "parent-2", "ORG", "Parent2");
			execute(c, "update wrk_node set name = 'Taken' where id = ?", parent);
			node(c, tenant, root, "taken", "ORG", "Taken");
			assertRejected("23505", null, () -> activate(c, parent));
		}
	}

	@Test
	void aParentCannotBeDeactivatedWhileAConcurrentChildInsertIsUncommitted() throws Exception {
		// 자식 INSERT가 커밋 전이어도 부모 비활성화는 그 자식을 기다렸다가 보고 거부해야 한다(ACTIVE 노드의 조상은 ACTIVE).
		UUID tenant;
		UUID parent;
		try (Connection setup = connect()) {
			tenant = tenant(setup, "race");
			UUID root = root(setup, tenant);
			node(setup, tenant, root, "common", "COMMON", "Common");
			parent = node(setup, tenant, root, "parent", "ORG", "Parent");
		}
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Connection inserter = connect(); Connection deactivator = connect()) {
			inserter.setAutoCommit(false);
			node(inserter, tenant, parent, "child", "WORK", "Child"); // 커밋하지 않는다
			Future<?> deactivation = executor.submit(() -> {
				deactivate(deactivator, parent);
				return null;
			});
			assertThatThrownBy(() -> deactivation.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			inserter.commit();
			assertThatThrownBy(() -> deactivation.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
					.rootCause().isInstanceOf(SQLException.class).hasMessageContaining("deactivate child workspaces first");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void onlyAnUnreferencedLeafCanBeReparented() throws SQLException {
		// DB-TST-004: 하위 노드·부여·Thread가 있는 노드는 reparent할 수 없다. 참조 없는 leaf는 옮길 수 있다.
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "reparent");
			UUID root = root(c, tenant);
			UUID unit = orgUnit(c, tenant, "unit");
			UUID a = node(c, tenant, root, "a", "ORG", "A");
			UUID b = node(c, tenant, root, "b", "ORG", "B");
			UUID withChild = node(c, tenant, root, "with-child", "ORG", "WithChild");
			node(c, tenant, withChild, "kid", "WORK", "Kid");
			UUID withGrant = node(c, tenant, root, "with-grant", "ORG", "WithGrant");
			grant(c, tenant, unit, withGrant, "VIEWER");
			UUID withThread = node(c, tenant, root, "with-thread", "ORG", "WithThread");
			UUID user = UUID.randomUUID();
			execute(c, "insert into app_user (id, keycloak_subj) values (?, 'reparent-user')", user);
			execute(c, "insert into thr (id, kind, created_user_id, title, wrk_node_id) values (?, 'COLLAB', ?, 't', ?)", UUID.randomUUID(), user, withThread);

			for (UUID blocked : new UUID[] {withChild, withGrant, withThread}) {
				assertRejected("P0001", "only an unreferenced workspace leaf can be reparented", () -> move(c, blocked, b));
			}
			move(c, a, b);
			assertThat(pathLength(c, a)).isEqualTo(3);
		}
	}

	// ------------------------------------------------------------------ 부여

	@Test
	void grantsRequireActiveTargetsAndAllowOnlyRoleChanges() throws SQLException {
		// DB-TST-001(다른 Tenant 대상), 004(ROOT·INACTIVE 대상·잘못된 role), 038(VIEWER·ADMIN 공존·중복 거부),
		// 059(role 변경 때 updated_at 갱신, 비활성화 뒤 재활성화하면 부여가 그대로 남는다)
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "grants");
			UUID other = tenant(c, "grants-other");
			UUID root = root(c, tenant);
			UUID sales = node(c, tenant, root, "sales", "ORG", "Sales");
			UUID retired = node(c, tenant, root, "retired", "ORG", "Retired");
			deactivate(c, retired);
			UUID unit = orgUnit(c, tenant, "unit");
			UUID idle = orgUnit(c, tenant, "idle");
			execute(c, "update org_unit set status = 'INACTIVE', inactive_at = now() where id = ?", idle);
			UUID foreignUnit = orgUnit(c, other, "foreign");

			UUID viewer = grant(c, tenant, unit, sales, "VIEWER");
			grant(c, tenant, unit, sales, "ADMIN");
			assertRejected("23505", null, () -> grant(c, tenant, unit, sales, "VIEWER"));
			assertRejected("23514", "wrk_grn_role_value", () -> grant(c, tenant, unit, sales, "OWNER"));
			assertRejected("P0001", "active non-ROOT node", () -> grant(c, tenant, unit, root, "VIEWER"));
			assertRejected("P0001", "active non-ROOT node", () -> grant(c, tenant, unit, retired, "VIEWER"));
			assertRejected("P0001", "active organization unit", () -> grant(c, tenant, idle, sales, "VIEWER"));
			assertRejected("P0001", "active organization unit", () -> grant(c, tenant, foreignUnit, sales, "VIEWER"));

			// role 변경은 그 행의 UPDATE이며 updated_at이 갱신된다. 대상(org-unit·노드)은 바꿀 수 없다.
			execute(c, "update wrk_grn set role = 'CONTRIBUTOR' where id = ?", viewer);
			assertThat(query(c, "select updated_at > created_at from wrk_grn where id = ?", viewer)).isEqualTo(true);
			assertRejected("P0001", "only the role of a workspace grant can change",
					() -> execute(c, "update wrk_grn set org_unit_id = ? where id = ?", idle, viewer));

			// org-unit을 껐다 켜도 부여 행은 그대로 남는다(자동 복구).
			execute(c, "update org_unit set status = 'INACTIVE', inactive_at = now() where id = ?", unit);
			execute(c, "update org_unit set status = 'ACTIVE', inactive_at = null where id = ?", unit);
			assertThat(query(c, "select count(*) from wrk_grn where org_unit_id = ?", unit)).isEqualTo(2L);
		}
	}

	// ------------------------------------------------------------------ 상태·시각 CHECK

	@Test
	void statusAndInactiveTimeMustAgree() throws SQLException {
		// DB-TST-055: status와 inactive_at이 어긋난 행은 tnn·org_unit·wrk_node 모두에서 거부한다.
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "status");
			UUID root = root(c, tenant);
			assertRejected("23514", "tnn_status_time", () -> execute(c,
					"insert into tnn (id, tnn_key, name, status) values (?, 'status-2', 'S', 'INACTIVE')", UUID.randomUUID()));
			assertRejected("23514", "tnn_status_time", () -> execute(c,
					"insert into tnn (id, tnn_key, name, inactive_at) values (?, 'status-3', 'S', now())", UUID.randomUUID()));
			assertRejected("23514", "org_unit_status_time", () -> execute(c,
					"insert into org_unit (id, tnn_id, org_unit_key, name, status) values (?, ?, 'u', 'U', 'INACTIVE')",
					UUID.randomUUID(), tenant));
			assertRejected("23514", "wrk_node_status_time", () -> execute(c,
					"update wrk_node set inactive_at = now() where id = ?", root));
		}
	}

	// ------------------------------------------------------------------ 권한 변경 감사

	// ------------------------------------------------------------------ 불변 key
	// tnn_key·org_unit_key·node_key는 bootstrap 설정과 Keycloak claim이 가리키는 안정 식별자다. 값이 바뀌는 UPDATE는
	// 거부하고, 이름·상태 변경과 같은 값으로 다시 쓰는 UPDATE는 통과해야 한다(SQLState P0001 = trigger의 raise exception).

	@Test
	void tenantKeyCannotBeChanged() throws SQLException {
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "acme");

			assertRejected("P0001", "tnn_key is immutable", () -> execute(c, "update tnn set tnn_key = 'globex' where id = ?", tenant));
			// 형식이 올바르지 않은 값이어도 key 불변이 먼저 거부한다.
			assertRejected("P0001", "tnn_key is immutable", () -> execute(c, "update tnn set tnn_key = 'Bad Key' where id = ?", tenant));

			execute(c, "update tnn set name = 'ACME Corp', tnn_key = tnn_key where id = ?", tenant);
			assertThat(query(c, "select tnn_key from tnn where id = ?", tenant)).isEqualTo("acme");
		}
	}

	@Test
	void orgUnitKeyCannotBeChanged() throws SQLException {
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "acme");
			UUID unit = orgUnit(c, tenant, "sales");

			assertRejected("P0001", "org_unit_key is immutable", () -> execute(c, "update org_unit set org_unit_key = 'ops' where id = ?", unit));

			execute(c, "update org_unit set name = '영업본부', org_unit_key = org_unit_key where id = ?", unit);
			assertThat(query(c, "select org_unit_key from org_unit where id = ?", unit)).isEqualTo("sales");
		}
	}

	// ------------------------------------------------------------------ Tenant 삭제·필수 부여

	@Test
	void tenantsAreNeverPhysicallyDeletedEvenWhenEmpty() throws SQLException {
		try (Connection c = connect()) {
			UUID empty = tenant(c, "empty");
			UUID used = tenant(c, "used");
			root(c, used);

			// 하위 행이 없는 Tenant는 FK가 막지 못하므로 trigger가 직접 거부한다.
			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from tnn where id = ?", empty));
			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from tnn where id = ?", used));
			assertThat(count(c, "tnn", "id", empty)).isEqualTo(1);
			// 끄는 것은 status로 한다.
			execute(c, "update tnn set status = 'INACTIVE', inactive_at = now() where id = ?", empty);
		}
	}

	@Test
	void theCommonViewerGrantOfAnActiveOrgUnitCannotBeRemovedOrChanged() throws SQLException {
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "required");
			UUID root = root(c, tenant);
			UUID common = node(c, tenant, root, "common", "COMMON", "Common");
			UUID sales = node(c, tenant, root, "sales", "ORG", "Sales");
			UUID unit = orgUnit(c, tenant, "sales-unit");
			UUID commonViewer = grant(c, tenant, unit, common, "VIEWER");
			UUID commonAdmin = grant(c, tenant, unit, common, "ADMIN");
			UUID salesViewer = grant(c, tenant, unit, sales, "VIEWER");

			assertRejected("P0001", "COMMON VIEWER grant", () -> execute(c, "delete from wrk_grn where id = ?", commonViewer));
			assertRejected("P0001", "COMMON VIEWER grant", () -> execute(c, "update wrk_grn set role = 'CONTRIBUTOR' where id = ?", commonViewer));
			assertThat(count(c, "wrk_grn", "id", commonViewer)).isEqualTo(1);

			// 필수 부여만 보호한다: COMMON의 다른 역할, COMMON이 아닌 노드의 VIEWER는 지우거나 바꿀 수 있다.
			execute(c, "update wrk_grn set role = 'CONTRIBUTOR' where id = ?", commonAdmin);
			execute(c, "delete from wrk_grn where id = ?", commonAdmin);
			execute(c, "update wrk_grn set role = 'CONTRIBUTOR' where id = ?", salesViewer);
			execute(c, "delete from wrk_grn where id = ?", salesViewer);

			// org-unit을 껐다 켜는 우회로도 막힌다. 허용하면 "끄고 → 지우고 → 켠다"로 ACTIVE org-unit에 필수 부여가 없어진다.
			execute(c, "update org_unit set status = 'INACTIVE', inactive_at = now() where id = ?", unit);
			assertRejected("P0001", "COMMON VIEWER grant", () -> execute(c, "delete from wrk_grn where id = ?", commonViewer));
			execute(c, "update org_unit set status = 'ACTIVE', inactive_at = null where id = ?", unit);
			assertThat(count(c, "wrk_grn", "id", commonViewer)).isEqualTo(1);
		}
	}

	@Test
	void guardsAreNotBypassedByReplicationRoleOrTruncate() throws SQLException {
		// guard가 ENABLE ALWAYS가 아니면 session_replication_role = replica로 전부 건너뛴다. 이 저장소의 앱 계정은
		// superuser라 그 설정을 실제로 바꿀 수 있다. TRUNCATE는 행 trigger를 부르지 않으므로 따로 확인한다.
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "bypass");
			UUID root = root(c, tenant);
			node(c, tenant, root, "common", "COMMON", "Common");
			UUID unit = orgUnit(c, tenant, "staff");

			execute(c, "set session_replication_role = replica");
			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from wrk_node where id = ?", root));
			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from org_unit where id = ?", unit));
			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from tnn where id = ?", tenant));
			assertRejected("P0001", "tnn_key is immutable", () -> execute(c, "update tnn set tnn_key = 'hijacked' where id = ?", tenant));
			assertRejected("P0001", "workspace kind is immutable", () -> execute(c, "update wrk_node set kind = 'WORK' where id = ?", root));
			execute(c, "set session_replication_role = origin");

			for (String table : new String[] { "tnn", "org_unit", "wrk_node", "authz_adt" }) {
				assertRejected("P0001", null, () -> execute(c, "truncate " + table + " cascade"));
			}
			assertThat(count(c, "tnn", "id", tenant)).isEqualTo(1);
		}
	}

	// ------------------------------------------------------------------ org_unit 수명주기
	// org-unit은 비활성화·보존 대상이다. 다른 Tenant로 옮기거나 물리 삭제하면 그 key를 가진 token과 부여·감사의 근거가 사라진다.
	// 부여가 붙은 행은 복합 FK가 막아 주지만 부여가 없는 행은 막는 것이 없으므로 trigger가 직접 거부해야 한다.

	@Test
	void orgUnitCannotMoveToAnotherTenantWithOrWithoutGrants() throws SQLException {
		try (Connection c = connect()) {
			UUID acme = tenant(c, "acme");
			UUID globex = tenant(c, "globex");
			UUID ungranted = orgUnit(c, acme, "ops");
			UUID granted = orgUnit(c, acme, "sales");
			UUID root = root(c, acme);
			UUID common = node(c, acme, root, "common", "COMMON", "Common");
			grant(c, acme, granted, common, "VIEWER");

			assertRejected("P0001", "tnn_id is immutable", () -> execute(c, "update org_unit set tnn_id = ? where id = ?", globex, ungranted));
			assertRejected("P0001", "tnn_id is immutable", () -> execute(c, "update org_unit set tnn_id = ? where id = ?", globex, granted));

			assertThat(query(c, "select tnn_id from org_unit where id = ?", ungranted)).isEqualTo(acme);
			// 이름·상태 변경은 그대로 된다.
			execute(c, "update org_unit set name = '운영본부', tnn_id = tnn_id where id = ?", ungranted);
		}
	}

	@Test
	void orgUnitsAreNeverPhysicallyDeletedEvenWithoutGrants() throws SQLException {
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "acme");
			UUID ungranted = orgUnit(c, tenant, "ops");
			UUID granted = orgUnit(c, tenant, "sales");
			UUID root = root(c, tenant);
			UUID common = node(c, tenant, root, "common", "COMMON", "Common");
			grant(c, tenant, granted, common, "VIEWER");

			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from org_unit where id = ?", ungranted));
			assertRejected("P0001", "never physically deleted", () -> execute(c, "delete from org_unit where id = ?", granted));

			assertThat(count(c, "org_unit", "tnn_id", tenant)).isEqualTo(2);
			// 삭제 대신 비활성화는 그대로 된다.
			execute(c, "update org_unit set status = 'INACTIVE', inactive_at = now() where id = ?", ungranted);
		}
	}

	@Test
	void nodeKeyCannotBeChanged() throws SQLException {
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "acme");
			UUID root = root(c, tenant);
			UUID common = node(c, tenant, root, "common", "COMMON", "Common");
			UUID sales = node(c, tenant, root, "sales-hq", "ORG", "Sales HQ");

			assertRejected("P0001", "node_key is immutable", () -> execute(c, "update wrk_node set node_key = 'sales-two' where id = ?", sales));
			// ROOT·COMMON의 예약 key도 다른 값으로 바꿀 수 없다.
			assertRejected("P0001", "node_key is immutable", () -> execute(c, "update wrk_node set node_key = 'top' where id = ?", root));
			assertRejected("P0001", "node_key is immutable", () -> execute(c, "update wrk_node set node_key = 'shared' where id = ?", common));

			execute(c, "update wrk_node set name = 'Sales Headquarters', node_key = node_key where id = ?", sales);
			assertThat(query(c, "select node_key from wrk_node where id = ?", sales)).isEqualTo("sales-hq");
		}
	}

	@Test
	void authorizationAuditRowsAreImmutableEvenForSuperusers() throws SQLException {
		// DB-TST-014A(UPDATE·DELETE 거부, FK는 tnn·행위자만), 058(계정과 무관한 append-only)
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "audit");
			UUID user = UUID.randomUUID();
			execute(c, "insert into app_user (id, keycloak_subj) values (?, 'audit-user')", user);
			UUID audit = audit(c, tenant, "TENANT_CREATED", "TENANT");

			assertRejected("P0001", "append-only", () -> execute(c, "update authz_adt set trg_kind = 'ORG_UNIT' where id = ?", audit));
			assertRejected("P0001", "append-only", () -> execute(c, "delete from authz_adt where id = ?", audit));
			assertRejected("P0001", "append-only", () -> execute(c, "truncate authz_adt"));
			// 이 세션은 superuser라 session_replication_role로 일반 trigger를 끌 수 있다 — ENABLE ALWAYS라 우회되지 않아야 한다.
			execute(c, "set session_replication_role = replica");
			assertRejected("P0001", "append-only", () -> execute(c, "delete from authz_adt where id = ?", audit));
			execute(c, "set session_replication_role = origin");
			assertThat(query(c, "select count(*) from authz_adt", new Object[0])).isEqualTo(1L);

			// 대상은 FK가 아니라 snapshot이고, Tenant·행위자만 FK다.
			assertRejected("23503", null, () -> audit(c, UUID.randomUUID(), "TENANT_CREATED", "TENANT"));
			assertRejected("23503", null, () -> execute(c,
					"insert into authz_adt (id, tnn_id, act_kind, act_user_id, act_role_json, evt_kind, trg_kind, trg_ref) values (?, ?, 'USER', ?, '[]'::jsonb, 'NODE_RENAMED', 'WORKSPACE', '{}'::jsonb)",
					UUID.randomUUID(), tenant, UUID.randomUUID()));
			execute(c, "insert into authz_adt (id, tnn_id, act_kind, act_user_id, act_role_json, evt_kind, trg_kind, trg_ref, bfr_json, aft_json) values (?, ?, 'USER', ?, '[\"ADMIN\"]'::jsonb, 'POLICY_REPLACED', 'POLICY', '{}'::jsonb, '{\"role\":\"VIEWER\"}'::jsonb, '{\"role\":\"ADMIN\"}'::jsonb)",
					UUID.randomUUID(), tenant, user);
		}
	}

	@Test
	void authorizationAuditValuesAreRestrictedToTheDocumentedSet() throws SQLException {
		// DB-TST-035: 허용값 밖의 evt_kind·trg_kind·act_kind 조합은 CHECK가 거부한다.
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "audit-values");
			assertRejected("23514", "authz_adt_evt_value", () -> audit(c, tenant, "SOMETHING_ELSE", "TENANT"));
			assertRejected("23514", "authz_adt_target_value", () -> audit(c, tenant, "TENANT_CREATED", "DOCUMENT"));
			assertRejected("23514", "authz_adt_actor_value", () -> execute(c,
					"insert into authz_adt (id, tnn_id, act_kind, act_role_json, evt_kind, trg_kind, trg_ref) values (?, ?, 'USER', '[]'::jsonb, 'NODE_RENAMED', 'WORKSPACE', '{}'::jsonb)",
					UUID.randomUUID(), tenant));
			for (String event : new String[] {"TENANT_CREATED", "TENANT_RENAMED", "TENANT_DEACTIVATED", "TENANT_REACTIVATED",
					"ORG_UNIT_CREATED", "ORG_UNIT_RENAMED", "ORG_UNIT_DEACTIVATED", "ORG_UNIT_REACTIVATED", "NODE_CREATED",
					"NODE_RENAMED", "NODE_REPARENTED", "NODE_DEACTIVATED", "NODE_REACTIVATED", "POLICY_ADDED", "POLICY_REMOVED",
					"POLICY_REPLACED", "THREAD_MOVED", "OWNER_TRANSFERRED", "TENANT_DRIFT_DETECTED"}) {
				audit(c, tenant, event, "TENANT");
			}
			assertThat(query(c, "select count(*) from authz_adt where tnn_id = ?", tenant)).isEqualTo(19L);
		}
	}

	// ------------------------------------------------------------------ Thread 계열(DB-TST-065·056)

	@Test
	void childRowsCopyTheThreadTenantOnInsert() throws SQLException {
		// DB-TST-065 ①: 자식 6개는 호출자가 넣은 tnn_id와 무관하게 Thread의 tnn_id로 채워진다.
		UUID tenant = UUID.randomUUID();
		UUID unrelated = UUID.randomUUID();
		UUID user = UUID.randomUUID();
		UUID thread = UUID.randomUUID();
		UUID legacyThread = UUID.randomUUID();
		UUID member = UUID.randomUUID();
		UUID firstMsg = UUID.randomUUID();
		UUID secondMsg = UUID.randomUUID();
		UUID thrKey = UUID.randomUUID();
		UUID msgKey = UUID.randomUUID();
		UUID invitation = UUID.randomUUID();
		try (Connection connection = connect()) {
			execute(connection, "insert into tnn (id, tnn_key, name) values (?, 'copy', 'Copy')", tenant);
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'copy-user')", user);
			execute(connection, "insert into thr (id, kind, created_user_id, title, tnn_id) values (?, 'COLLAB', ?, 't', ?)", thread, user, tenant);

			// 값을 다른 Tenant로 넣어도 Thread 값으로 덮어쓴다.
			execute(connection, "insert into thr_mbr (id, thr_id, user_id, role, created_by_user_id, tnn_id) values (?, ?, ?, 'OWNER', ?, ?)", member, thread, user, user, unrelated);
			execute(connection, "insert into msg (id, thr_id, seq, ath_kind, status, completed_at, tnn_id) values (?, ?, 1, 'SYSTEM', 'COMPLETE', now(), ?)", firstMsg, thread, unrelated);
			execute(connection, "insert into msg (id, thr_id, seq, ath_kind, status, completed_at) values (?, ?, 2, 'SYSTEM', 'COMPLETE', now())", secondMsg, thread);
			execute(connection, "insert into thr_idm_key (id, user_id, idm_key, title, thr_id, tnn_id) values (?, ?, 'k', 't', ?, ?)", thrKey, user, thread, unrelated);
			execute(connection, "insert into msg_idm_key (id, user_id, idm_key, content, thr_id, hmn_msg_id, hmn_seq, agn_msg_id, agn_seq, tnn_id) values (?, ?, 'k', 'c', ?, ?, 1, ?, 2, ?)", msgKey, user, thread, firstMsg, secondMsg, unrelated);
			execute(connection, "insert into thr_inv (id, thr_id, subj, created_by_user_id, tnn_id) values (?, ?, 'someone', ?, ?)", invitation, thread, user, unrelated);
			execute(connection, "insert into thr_risk_crs (thr_id, tnn_id) values (?, ?)", thread, unrelated);

			assertThat(tenantOf(connection, "thr_mbr", "id", member)).isEqualTo(tenant);
			assertThat(tenantOf(connection, "msg", "id", firstMsg)).isEqualTo(tenant);
			assertThat(tenantOf(connection, "msg", "id", secondMsg)).isEqualTo(tenant);
			assertThat(tenantOf(connection, "thr_idm_key", "id", thrKey)).isEqualTo(tenant);
			assertThat(tenantOf(connection, "msg_idm_key", "id", msgKey)).isEqualTo(tenant);
			assertThat(tenantOf(connection, "thr_inv", "id", invitation)).isEqualTo(tenant);
			assertThat(tenantOf(connection, "thr_risk_crs", "thr_id", thread)).isEqualTo(tenant);

			// 절체 전: Thread의 tnn_id가 null이면 자식도 null이다(호출자가 값을 줘도).
			execute(connection, "insert into thr (id, kind, created_user_id, title) values (?, 'COLLAB', ?, 'legacy')", legacyThread, user);
			execute(connection, "insert into thr_inv (id, thr_id, subj, created_by_user_id, tnn_id) values (?, ?, 'legacy-invitee', ?, ?)", UUID.randomUUID(), legacyThread, user, unrelated);
			assertThat(tenantOf(connection, "thr_inv", "thr_id", legacyThread)).isNull();
		}
	}

	@Test
	void deletingAThreadIsNotBlockedByItsCutoverStagingRow() throws SQLException {
		// DB-TST-065 ②: staging에 적재된 방을 삭제해도 막히지 않고 그 매핑도 함께 지워진다.
		UUID user = UUID.randomUUID();
		UUID thread = UUID.randomUUID();
		try (Connection connection = connect()) {
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'staging-user')", user);
			execute(connection, "insert into thr (id, kind, created_user_id, title) values (?, 'COLLAB', ?, 't')", thread, user);
			execute(connection, "insert into stg_thr_tnn (thr_id, tnn_key) values (?, 'acme')", thread);

			execute(connection, "delete from thr where id = ?", thread);

			assertThat(count(connection, "stg_thr_tnn", "thr_id", thread)).isZero();
		}
	}

	@Test
	void pendingReasonMustBeClearedWhenAnInvitationLeavesPending() throws SQLException {
		// DB-TST-065 ③, 056: pnd_rsn은 PENDING일 때만, NO_ACCESS 값만 가질 수 있다.
		UUID user = UUID.randomUUID();
		UUID thread = UUID.randomUUID();
		UUID invitation = UUID.randomUUID();
		try (Connection connection = connect()) {
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'invite-user')", user);
			execute(connection, "insert into thr (id, kind, created_user_id, title) values (?, 'COLLAB', ?, 't')", thread, user);
			execute(connection, "insert into thr_inv (id, thr_id, subj, created_by_user_id, pnd_rsn) values (?, ?, 'invitee', ?, 'NO_ACCESS')", invitation, thread, user);

			assertRejected("23514", "thr_inv_pending_reason_value", () -> execute(connection,
					"insert into thr_inv (id, thr_id, subj, created_by_user_id, pnd_rsn) values (?, ?, 'other', ?, 'SOMETHING')",
					UUID.randomUUID(), thread, user));
			assertRejected("23514", "thr_inv_pending_reason_only", () -> execute(connection,
					"update thr_inv set status = 'ACCEPTED', ended_at = now(), end_rsn = 'FIRST_LOGIN' where id = ?", invitation));

			execute(connection,
					"update thr_inv set status = 'ACCEPTED', ended_at = now(), end_rsn = 'FIRST_LOGIN', pnd_rsn = null where id = ?", invitation);
			assertThat(count(connection, "thr_inv", "id", invitation)).isEqualTo(1);
		}
	}

	// ------------------------------------------------------------------ Casbin 배정·직급 규칙
	// org_unit_mbr은 사람의 팀·직급, rank_grn은 직급 서열 규칙이다. 겸직이 없어 subject당 배정은 한 행이다.

	@Test
	void orgUnitMembersRequireAnActiveOrgUnitAndOneRowPerSubject() throws SQLException {
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "members");
			UUID other = tenant(c, "members-other");
			UUID hr = orgUnit(c, tenant, "hr");
			UUID fin = orgUnit(c, tenant, "fin");
			UUID idle = orgUnit(c, tenant, "idle");
			execute(c, "update org_unit set status = 'INACTIVE', inactive_at = now() where id = ?", idle);
			UUID foreignUnit = orgUnit(c, other, "foreign");

			UUID member = member(c, tenant, hr, "sub-kim", "TL");
			assertRejected("23505", "uq_org_unit_mbr_subj", () -> member(c, tenant, fin, "sub-kim", "K"));
			assertRejected("23514", "org_unit_mbr_rank_value", () -> member(c, tenant, hr, "sub-lee", "X"));
			assertRejected("P0001", "active organization unit", () -> member(c, tenant, idle, "sub-lee", "K"));
			assertRejected("P0001", "active organization unit", () -> member(c, tenant, foreignUnit, "sub-lee", "K"));

			// 인사이동은 그 행의 UPDATE다. 비활성 org-unit으로는 옮길 수 없다.
			execute(c, "update org_unit_mbr set org_unit_id = ?, rank = 'B' where id = ?", fin, member);
			assertThat(query(c, "select updated_at > created_at from org_unit_mbr where id = ?", member)).isEqualTo(true);
			assertRejected("P0001", "active organization unit",
					() -> execute(c, "update org_unit_mbr set org_unit_id = ? where id = ?", idle, member));

			// 팀이 꺼져도 직급만 바꾸는 UPDATE는 된다(배정을 정리하기 전에도 직급 기록은 고칠 수 있어야 한다).
			execute(c, "update org_unit set status = 'INACTIVE', inactive_at = now() where id = ?", fin);
			execute(c, "update org_unit_mbr set rank = 'C' where id = ?", member);
			assertThat(query(c, "select rank from org_unit_mbr where id = ?", member)).isEqualTo("C");
		}
	}

	@Test
	void rankGrantsRequireActiveNonRootNodesAndAllowOnlyRankChanges() throws SQLException {
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "ranks");
			UUID root = root(c, tenant);
			UUID exec = node(c, tenant, root, "exec", "ORG", "Exec");
			UUID other = node(c, tenant, root, "other", "ORG", "Other");
			UUID retired = node(c, tenant, root, "retired", "ORG", "Retired");
			deactivate(c, retired);

			UUID rule = rankGrant(c, tenant, exec, "K");
			rankGrant(c, tenant, exec, "B");
			assertRejected("23505", "uq_rank_grn_policy", () -> rankGrant(c, tenant, exec, "K"));
			assertRejected("23514", "rank_grn_rank_value", () -> rankGrant(c, tenant, exec, "X"));
			assertRejected("P0001", "active non-ROOT node", () -> rankGrant(c, tenant, root, "K"));
			assertRejected("P0001", "active non-ROOT node", () -> rankGrant(c, tenant, retired, "K"));

			execute(c, "update rank_grn set rank = 'C' where id = ?", rule);
			assertThat(query(c, "select updated_at > created_at from rank_grn where id = ?", rule)).isEqualTo(true);
			assertRejected("P0001", "only the rank of a rank grant can change",
					() -> execute(c, "update rank_grn set wrk_node_id = ? where id = ?", other, rule));
		}
	}

	@Test
	void assignmentGuardsAreNotBypassedByReplicationRole() throws SQLException {
		try (Connection c = connect()) {
			UUID tenant = tenant(c, "assign-bypass");
			UUID root = root(c, tenant);
			UUID idle = orgUnit(c, tenant, "idle");
			execute(c, "update org_unit set status = 'INACTIVE', inactive_at = now() where id = ?", idle);

			execute(c, "set session_replication_role = replica");
			assertRejected("P0001", "active organization unit", () -> member(c, tenant, idle, "sub-bypass", "S"));
			assertRejected("P0001", "active non-ROOT node", () -> rankGrant(c, tenant, root, "S"));
			execute(c, "set session_replication_role = origin");
		}
	}

	// ------------------------------------------------------------------ 헬퍼

	private Connection connect() throws SQLException {
		return POSTGRES.createConnection("");
	}

	/** 거부 원인을 SQLState와(주어지면) 메시지 조각으로 확인한다. */
	private static void assertRejected(String sqlState, String messagePart, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(SQLException.class, error -> {
			assertThat(error.getSQLState()).as("SQLState of: %s", error.getMessage()).isEqualTo(sqlState);
			if (messagePart != null) assertThat(error.getMessage()).contains(messagePart);
		});
	}

	private UUID tenant(Connection c, String key) throws SQLException {
		UUID id = UUID.randomUUID();
		execute(c, "insert into tnn (id, tnn_key, name) values (?, ?, ?)", id, key, key);
		return id;
	}

	private UUID root(Connection c, UUID tenant) throws SQLException {
		UUID id = UUID.randomUUID();
		execute(c, "insert into wrk_node (id, tnn_id, node_key, kind, name, path) values (?, ?, 'root', 'ROOT', 'Root', array[?]::uuid[])", id, tenant, id);
		return id;
	}

	/** 부모의 path에 자기 id를 이어 붙여 정상 path로 노드를 만든다. */
	private UUID node(Connection c, UUID tenant, UUID parent, String key, String kind, String name) throws SQLException {
		UUID id = UUID.randomUUID();
		execute(c, "insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) select ?, ?, id, ?, ?, ?, path || ?::uuid from wrk_node where id = ?",
				id, tenant, key, kind, name, id, parent);
		return id;
	}

	private UUID orgUnit(Connection c, UUID tenant, String key) throws SQLException {
		UUID id = UUID.randomUUID();
		execute(c, "insert into org_unit (id, tnn_id, org_unit_key, name) values (?, ?, ?, ?)", id, tenant, key, key);
		return id;
	}

	private UUID grant(Connection c, UUID tenant, UUID unit, UUID node, String role) throws SQLException {
		UUID id = UUID.randomUUID();
		execute(c, "insert into wrk_grn (id, tnn_id, org_unit_id, wrk_node_id, role) values (?, ?, ?, ?, ?)", id, tenant, unit, node, role);
		return id;
	}

	private UUID member(Connection c, UUID tenant, UUID unit, String subject, String rank) throws SQLException {
		UUID id = UUID.randomUUID();
		execute(c, "insert into org_unit_mbr (id, tnn_id, org_unit_id, subj, rank) values (?, ?, ?, ?, ?)", id, tenant, unit, subject, rank);
		return id;
	}

	private UUID rankGrant(Connection c, UUID tenant, UUID node, String rank) throws SQLException {
		UUID id = UUID.randomUUID();
		execute(c, "insert into rank_grn (id, tnn_id, wrk_node_id, rank) values (?, ?, ?, ?)", id, tenant, node, rank);
		return id;
	}

	private UUID audit(Connection c, UUID tenant, String event, String target) throws SQLException {
		UUID id = UUID.randomUUID();
		execute(c, "insert into authz_adt (id, tnn_id, act_kind, act_role_json, evt_kind, trg_kind, trg_ref) values (?, ?, 'SYSTEM', '[]'::jsonb, ?, ?, '{}'::jsonb)", id, tenant, event, target);
		return id;
	}

	private void deactivate(Connection c, UUID node) throws SQLException {
		execute(c, "update wrk_node set status = 'INACTIVE', inactive_at = now() where id = ?", node);
	}

	private void activate(Connection c, UUID node) throws SQLException {
		execute(c, "update wrk_node set status = 'ACTIVE', inactive_at = null where id = ?", node);
	}

	/** 새 부모 아래로 옮긴다(path도 부모 path + 자기 id로 다시 계산해 넣는다). */
	private void move(Connection c, UUID node, UUID newParent) throws SQLException {
		execute(c, "update wrk_node set prn_id = p.id, path = p.path || ?::uuid from wrk_node p where p.id = ? and wrk_node.id = ?",
				node, newParent, node);
	}

	private int pathLength(Connection c, UUID node) throws SQLException {
		return ((Number) query(c, "select cardinality(path) from wrk_node where id = ?", node)).intValue();
	}

	private Object query(Connection c, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = c.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
			try (ResultSet rows = statement.executeQuery()) {
				assertThat(rows.next()).isTrue();
				return rows.getObject(1);
			}
		}
	}

	private UUID tenantOf(Connection connection, String table, String keyColumn, UUID key) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select tnn_id from " + table + " where " + keyColumn + " = ?")) {
			statement.setObject(1, key);
			try (ResultSet rows = statement.executeQuery()) {
				assertThat(rows.next()).as("%s row for %s", table, key).isTrue();
				return rows.getObject(1, UUID.class);
			}
		}
	}

	private long count(Connection connection, String table, String keyColumn, UUID key) throws SQLException {
		return ((Number) query(connection, "select count(*) from " + table + " where " + keyColumn + " = ?", key)).longValue();
	}

	private void execute(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
			statement.executeUpdate();
		}
	}
}
