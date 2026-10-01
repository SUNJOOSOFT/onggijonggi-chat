package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static com.onggijonggi.api.support.JdbcSupport.count;
import static com.onggijonggi.api.support.JdbcSupport.execute;
import static com.onggijonggi.api.support.JdbcSupport.uuid;
import com.onggijonggi.api.support.MigrationVersions;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : CutoverMigrationPostgresTest.java
 * Description : PostgreSQL 16에서 절체 전 데이터를 만든 뒤 #262 migration의 성공·중단·제약을 검증한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class CutoverMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("cutover_test").withUsername("test").withPassword("test");

	@BeforeEach
	void clean() {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").cleanDisabled(false).load().clean();
	}

	@Test
	void emptyDatabaseWithoutTenantAcceptsCompleteMigration() throws SQLException {
		migrateAll();
		try (Connection connection = connect()) {
			assertThat(count(connection, "select count(*) from ctv")).isZero();
			assertThat(count(connection, "select count(*) from flyway_schema_history where success = false")).isZero();
		}
	}

	@Test
	void preservesCollabPlacementAndBackfillsDirectAndChildren() throws SQLException {
		migrateBeforeCutover();
		UUID tenant = UUID.randomUUID();
		UUID root = UUID.randomUUID();
		UUID common = UUID.randomUUID();
		UUID team = UUID.randomUUID();
		UUID owner = UUID.randomUUID();
		UUID direct = UUID.randomUUID();
		UUID collab = UUID.randomUUID();
		UUID message = UUID.randomUUID();
		UUID member = UUID.randomUUID();
		UUID human = UUID.randomUUID();
		UUID agent = UUID.randomUUID();
		UUID threadKey = UUID.randomUUID();
		UUID messageKey = UUID.randomUUID();
		UUID invitation = UUID.randomUUID();
		try (Connection connection = connect()) {
			seedTenant(connection, tenant, root, common, team);
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'cutover-owner')", owner);
			execute(connection, "insert into thr (id, kind, drc_own_user_id, created_user_id, title) values (?, 'DIRECT', ?, ?, 'direct')", direct, owner, owner);
			execute(connection, "insert into thr (id, kind, created_user_id, title, tnn_id, wrk_node_id) values (?, 'COLLAB', ?, 'collab', ?, ?)", collab, owner, tenant, team);
			execute(connection, "insert into msg (id, thr_id, seq, ath_kind, status, content, completed_at) values (?, ?, 0, 'SYSTEM', 'COMPLETE', 'original', now())", message, direct);
			execute(connection, "insert into thr_mbr (id, thr_id, user_id, role, created_by_user_id) values (?, ?, ?, 'OWNER', ?)", member, direct, owner, owner);
			execute(connection, "insert into msg (id, thr_id, seq, ath_kind, thr_mbr_id, status, content, completed_at) values (?, ?, 1, 'HUMAN', ?, 'COMPLETE', 'question', now())", human, direct, member);
			execute(connection, "insert into msg (id, thr_id, seq, ath_kind, status, content, completed_at) values (?, ?, 2, 'AGENT', 'COMPLETE', 'answer', now())", agent, direct);
			execute(connection, "insert into thr_idm_key (id, user_id, idm_key, title, thr_id) values (?, ?, 'thread-key', 'collab', ?)", threadKey, owner, collab);
			execute(connection, "insert into msg_idm_key (id, user_id, idm_key, content, thr_id, hmn_msg_id, hmn_seq, agn_msg_id, agn_seq) values (?, ?, 'message-key', 'question', ?, ?, 1, ?, 2)", messageKey, owner, direct, human, agent);
			execute(connection, "insert into thr_inv (id, thr_id, subj, created_by_user_id) values (?, ?, 'invitee', ?)", invitation, collab, owner);
			execute(connection, "insert into thr_risk_crs (thr_id, last_seq) values (?, 0)", collab);
		}
		migrateAll();
		try (Connection connection = connect()) {
			assertThat(uuid(connection, "select tnn_id from thr where id = ?", direct)).isEqualTo(tenant);
			assertThat(uuid(connection, "select wrk_node_id from thr where id = ?", direct)).isEqualTo(common);
			assertThat(uuid(connection, "select wrk_node_id from thr where id = ?", collab)).isEqualTo(team);
			assertThat(uuid(connection, "select tnn_id from msg where id = ?", message)).isEqualTo(tenant);
			assertThat(uuid(connection, "select tnn_id from thr_mbr where id = ?", member)).isEqualTo(tenant);
			assertThat(uuid(connection, "select tnn_id from thr_idm_key where id = ?", threadKey)).isEqualTo(tenant);
			assertThat(uuid(connection, "select tnn_id from msg_idm_key where id = ?", messageKey)).isEqualTo(tenant);
			assertThat(uuid(connection, "select tnn_id from thr_inv where id = ?", invitation)).isEqualTo(tenant);
			assertThat(uuid(connection, "select tnn_id from thr_risk_crs where thr_id = ?", collab)).isEqualTo(tenant);
			assertThat(count(connection, "select count(*) from msg where id = ? and content = 'original'", message)).isEqualTo(1);
			assertThatThrownBy(() -> execute(connection, "update msg set content = 'changed' where id = ?", message))
					.isInstanceOf(SQLException.class);
			assertThat(count(connection, "select count(*) from ctv")).isZero();
			UUID pending = UUID.randomUUID();
			execute(connection, "insert into msg (id, thr_id, seq, ath_kind, status) values (?, ?, 3, 'AGENT', 'PENDING')", pending, direct);
			assertThatThrownBy(() -> execute(connection, "update msg set tnn_id = ? where id = ?", UUID.randomUUID(), pending))
					.isInstanceOf(SQLException.class)
					.satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23503"))
					.hasMessageContaining("fk_msg_tnn_thr");
			assertThat(count(connection, "select count(*) from pg_trigger where tgname in ('trg_thr_tnn_immutable', 'trg_ctv_no_update_delete', 'trg_ctv_no_truncate') and tgenabled = 'A'"))
					.isEqualTo(3);
			execute(connection, "insert into ctv (id, tnn_id) values (1, ?)", tenant);
			assertThatThrownBy(() -> execute(connection, "update ctv set completed_at = now() where id = 1"))
					.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> execute(connection, "delete from ctv where id = 1"))
					.isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> execute(connection, "truncate ctv"))
					.isInstanceOf(SQLException.class);
			assertThat(count(connection, "select count(*) from ctv")).isEqualTo(1);
		}
	}

	@Test
	void existingThreadWithoutActiveTenantAbortsWithoutPartialBackfill() throws SQLException {
		migrateBeforeCutover();
		UUID user = UUID.randomUUID();
		UUID thread = UUID.randomUUID();
		try (Connection connection = connect()) {
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'orphan-owner')", user);
			execute(connection, "insert into thr (id, kind, drc_own_user_id, created_user_id, title) values (?, 'DIRECT', ?, ?, 'legacy')", thread, user, user);
		}
		assertThatThrownBy(this::migrateAll).hasMessageContaining("exactly one ACTIVE tenant");
		try (Connection connection = connect()) {
			assertThat(uuid(connection, "select tnn_id from thr where id = ?", thread)).isNull();
		}
	}

	@Test
	void invalidCollabPlacementAbortsBeforeBackfill() throws SQLException {
		migrateBeforeCutover();
		UUID tenant = UUID.randomUUID();
		UUID root = UUID.randomUUID();
		UUID common = UUID.randomUUID();
		UUID team = UUID.randomUUID();
		UUID user = UUID.randomUUID();
		UUID thread = UUID.randomUUID();
		try (Connection connection = connect()) {
			seedTenant(connection, tenant, root, common, team);
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'collab-author')", user);
			execute(connection, "insert into thr (id, kind, created_user_id, title, wrk_node_id) values (?, 'COLLAB', ?, 'invalid', ?)", thread, user, root);
		}
		assertThatThrownBy(this::migrateAll).hasMessageContaining("invalid workspace placement");
		try (Connection connection = connect()) {
			assertThat(uuid(connection, "select tnn_id from thr where id = ?", thread)).isNull();
		}
	}

	@Test
	void threadsAndActiveNodesStayConsistentInBothDirections() throws SQLException {
		migrateAll();
		UUID tenant = UUID.randomUUID();
		UUID root = UUID.randomUUID();
		UUID common = UUID.randomUUID();
		UUID team = UUID.randomUUID();
		UUID spare = UUID.randomUUID();
		UUID owner = UUID.randomUUID();
		try (Connection connection = connect()) {
			seedTenant(connection, tenant, root, common, team);
			execute(connection, "insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'spare', 'WORK', 'Spare', array[?, ?]::uuid[])", spare, tenant, root, root, spare);
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'guard-owner')", owner);
			execute(connection, "insert into thr (id, kind, created_user_id, title, tnn_id, wrk_node_id) values (?, 'COLLAB', ?, 'placed', ?, ?)", UUID.randomUUID(), owner, tenant, team);

			// Thread가 놓인 노드는 끌 수 없다.
			assertThatThrownBy(() -> execute(connection, "update wrk_node set status = 'INACTIVE', inactive_at = now() where id = ?", team))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("a workspace with threads cannot be deactivated");

			// 비어 있는 노드는 끌 수 있고, 꺼진 노드에는 Thread를 놓을 수 없다.
			execute(connection, "update wrk_node set status = 'INACTIVE', inactive_at = now() where id = ?", spare);
			assertThatThrownBy(() -> execute(connection, "insert into thr (id, kind, created_user_id, title, tnn_id, wrk_node_id) values (?, 'COLLAB', ?, 'late', ?, ?)", UUID.randomUUID(), owner, tenant, spare))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("thread requires an active workspace node");
			assertThat(count(connection, "select count(*) from pg_trigger where tgname in ('trg_thr_node_active', 'trg_wrk_node_thr') and tgenabled = 'A'")).isEqualTo(2);
		}
	}

	private void migrateBeforeCutover() {
		flyway().target(MigrationVersions.BEFORE_CUTOVER).load().migrate();
	}

	private void migrateAll() {
		Flyway flyway = flyway().load();
		flyway.migrate();
		flyway.validate();
	}

	private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
		return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration");
	}

	private Connection connect() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static void seedTenant(Connection connection, UUID tenant, UUID root, UUID common, UUID team) throws SQLException {
		execute(connection, "insert into tnn (id, tnn_key, name) values (?, 'cutover', 'Cutover')", tenant);
		execute(connection, "insert into wrk_node (id, tnn_id, node_key, kind, name, path) values (?, ?, 'root', 'ROOT', 'Root', array[?]::uuid[])", root, tenant, root);
		execute(connection, "insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'common', 'COMMON', 'Common', array[?, ?]::uuid[])", common, tenant, root, root, common);
		execute(connection, "insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'team', 'WORK', 'Team', array[?, ?]::uuid[])", team, tenant, root, root, team);
	}
}
