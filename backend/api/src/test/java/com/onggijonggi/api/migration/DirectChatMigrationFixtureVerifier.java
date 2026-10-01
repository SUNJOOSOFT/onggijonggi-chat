package com.onggijonggi.api.migration;

import java.sql.Connection;
import com.onggijonggi.api.support.MigrationVersions;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Class Name : DirectChatMigrationFixtureVerifier.java
 * Description : #158의 chat_sess/chat_msg → DIRECT thr/msg 이관을 실제 PostgreSQL fixture로 검증하는
 *               CI 전용 실행기. 빈 DB 전체 적용 검증과 분리된 schema에서 V14까지만 올려 과거 데이터를
 *               심은 뒤 최신 migration을 적용하므로, H2가 보지 못하는 PostgreSQL SQL·이관 중단 조건을
 *               함께 고정한다.
 */
public final class DirectChatMigrationFixtureVerifier {

	private static final String JDBC_URL = "FLYWAY_VERIFY_JDBC_URL";
	private static final String USERNAME = "FLYWAY_VERIFY_USERNAME";
	private static final String PASSWORD = "FLYWAY_VERIFY_PASSWORD";
	private static final String LEGACY_TARGET = "14";

	private DirectChatMigrationFixtureVerifier() {
	}

	public static void main(String[] args) throws SQLException {
		String jdbcUrl = requiredEnvironment(JDBC_URL);
		String username = requiredEnvironment(USERNAME);
		String password = requiredEnvironment(PASSWORD);

		verifySuccessfulMigration(jdbcUrl, username, password);
		verifyTimestampTieStopsMigration(jdbcUrl, username, password);
		verifyTargetIdCollisionStopsMigration(jdbcUrl, username, password);
		System.out.println("DIRECT chat migration fixtures verified.");
	}

	private static void verifySuccessfulMigration(String jdbcUrl, String username, String password) throws SQLException {
		String schema = "direct_chat_fixture_ok";
		resetSchema(jdbcUrl, username, password, schema);
		migrateLegacySchema(jdbcUrl, username, password, schema);

		UUID userId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		UUID humanId = UUID.randomUUID();
		UUID agentId = UUID.randomUUID();
		UUID systemId = UUID.randomUUID();
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
			insertUser(connection, schema, userId, "fixture-ok");
			insertSession(connection, schema, sessionId, userId, "보존할 제목", createdAt);
			insertMessage(connection, schema, humanId, sessionId, "user", "사람", null, createdAt);
			insertMessage(connection, schema, agentId, sessionId, "assistant", "AI", "{\"doc\":1}", createdAt.plusSeconds(1));
			insertMessage(connection, schema, systemId, sessionId, "system", "안내", null, createdAt.plusSeconds(2));
		}

		migrateLatest(jdbcUrl, username, password, schema);
		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
			assertThread(connection, schema, sessionId, userId, createdAt);
			assertMessages(connection, schema, sessionId, humanId, agentId, systemId);
		}
	}

	private static void verifyTimestampTieStopsMigration(String jdbcUrl, String username, String password)
			throws SQLException {
		String schema = "direct_chat_fixture_tie";
		resetSchema(jdbcUrl, username, password, schema);
		migrateLegacySchema(jdbcUrl, username, password, schema);
		UUID userId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
			insertUser(connection, schema, userId, "fixture-tie");
			insertSession(connection, schema, sessionId, userId, "동률", createdAt);
			insertMessage(connection, schema, UUID.randomUUID(), sessionId, "user", "첫째", null, createdAt);
			insertMessage(connection, schema, UUID.randomUUID(), sessionId, "assistant", "둘째", null, createdAt);
		}

		expectMigrationFailure(jdbcUrl, username, password, schema);
		assertCount(jdbcUrl, username, password, schema, "thr", 0);
	}

	private static void verifyTargetIdCollisionStopsMigration(String jdbcUrl, String username, String password)
			throws SQLException {
		String schema = "direct_chat_fixture_collision";
		resetSchema(jdbcUrl, username, password, schema);
		migrateLegacySchema(jdbcUrl, username, password, schema);
		UUID userId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
			insertUser(connection, schema, userId, "fixture-collision");
			insertSession(connection, schema, sessionId, userId, "충돌", createdAt);
			insertMessage(connection, schema, UUID.randomUUID(), sessionId, "user", "메시지", null, createdAt);
			try (PreparedStatement statement = connection.prepareStatement("""
					insert into %s.thr (id, kind, status, drc_own_user_id, created_user_id, title, next_seq, created_at, updated_at)
					values (?, 'DIRECT', 'ACTIVE', ?, ?, '기존 방', 0, ?, ?)
					""".formatted(schema))) {
				statement.setObject(1, sessionId);
				statement.setObject(2, userId);
				statement.setObject(3, userId);
				statement.setObject(4, createdAt);
				statement.setObject(5, createdAt);
				statement.executeUpdate();
			}
		}

		expectMigrationFailure(jdbcUrl, username, password, schema);
		assertCount(jdbcUrl, username, password, schema, "thr", 1);
	}

	private static void resetSchema(String jdbcUrl, String username, String password, String schema) throws SQLException {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
			PreparedStatement drop = connection.prepareStatement("drop schema if exists " + schema + " cascade");
			PreparedStatement create = connection.prepareStatement("create schema " + schema)) {
			drop.execute();
			create.execute();
		}
	}

	private static void migrateLegacySchema(String jdbcUrl, String username, String password, String schema) {
		flyway(jdbcUrl, username, password, schema).target(LEGACY_TARGET).load().migrate();
	}

	private static void migrateLatest(String jdbcUrl, String username, String password, String schema) {
		// 이 fixture는 #158 DIRECT 이관과 절체 전 확장 스키마만 검증한다.
		Flyway flyway = flyway(jdbcUrl, username, password, schema).target(MigrationVersions.BEFORE_CUTOVER).load();
		flyway.migrate();
		flyway.validate();
	}

	private static void expectMigrationFailure(String jdbcUrl, String username, String password, String schema) {
		try {
			migrateLatest(jdbcUrl, username, password, schema);
			throw new IllegalStateException("Expected DIRECT chat migration to fail");
		} catch (FlywayException expected) {
			// Preflight failure is the contract; Flyway rolls the migration transaction back.
		}
	}

	private static FluentConfiguration flyway(String jdbcUrl, String username, String password, String schema) {
		return Flyway.configure()
				.dataSource(jdbcUrl, username, password)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration");
	}

	private static void insertUser(Connection connection, String schema, UUID id, String subject) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"insert into " + schema + ".app_user (id, keycloak_subj) values (?, ?)")) {
			statement.setObject(1, id);
			statement.setString(2, subject);
			statement.executeUpdate();
		}
	}

	private static void insertSession(Connection connection, String schema, UUID id, UUID userId, String title,
			OffsetDateTime createdAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				insert into %s.chat_sess (id, user_id, title, created_at, updated_at, next_seq)
				values (?, ?, ?, ?, ?, 0)
				""".formatted(schema))) {
			statement.setObject(1, id);
			statement.setObject(2, userId);
			statement.setString(3, title);
			statement.setObject(4, createdAt);
			statement.setObject(5, createdAt);
			statement.executeUpdate();
		}
	}

	private static void insertMessage(Connection connection, String schema, UUID id, UUID sessionId, String role,
			String content, String sourceJson, OffsetDateTime createdAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				insert into %s.chat_msg (id, sess_id, role, content, src_json, created_at, seq)
				values (?, ?, ?, ?, cast(? as jsonb), ?, null)
				""".formatted(schema))) {
			statement.setObject(1, id);
			statement.setObject(2, sessionId);
			statement.setString(3, role);
			statement.setString(4, content);
			statement.setString(5, sourceJson);
			statement.setObject(6, createdAt);
			statement.executeUpdate();
		}
	}

	private static void assertThread(Connection connection, String schema, UUID sessionId, UUID userId,
			OffsetDateTime createdAt) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				select kind, status, drc_own_user_id, created_user_id, title, next_seq, created_at
				from %s.thr where id = ?
				""".formatted(schema))) {
			statement.setObject(1, sessionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()
						|| !"DIRECT".equals(result.getString("kind"))
						|| !"ACTIVE".equals(result.getString("status"))
						|| !userId.equals(result.getObject("drc_own_user_id", UUID.class))
						|| !userId.equals(result.getObject("created_user_id", UUID.class))
						|| !"보존할 제목".equals(result.getString("title"))
						|| result.getLong("next_seq") != 3
						|| !createdAt.toInstant().equals(result.getObject("created_at", OffsetDateTime.class).toInstant())) {
					throw new IllegalStateException("DIRECT thread fixture was not preserved");
				}
			}
		}
	}

	private static void assertMessages(Connection connection, String schema, UUID sessionId, UUID humanId, UUID agentId,
			UUID systemId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				select id, seq, ath_kind, thr_mbr_id, status, content, pyl_json, src_json, completed_at
				from %s.msg where thr_id = ? order by seq
				""".formatted(schema))) {
			statement.setObject(1, sessionId);
			try (ResultSet result = statement.executeQuery()) {
				assertMessage(result, humanId, 0, "HUMAN", sessionId, "사람", null);
				assertMessage(result, agentId, 1, "AGENT", null, "AI", "{\"doc\": 1}");
				assertMessage(result, systemId, 2, "SYSTEM", null, "안내", null);
				if (result.next()) {
					throw new IllegalStateException("Unexpected extra migrated message");
				}
			}
		}
	}

	private static void assertMessage(ResultSet result, UUID id, long seq, String kind, UUID membershipId, String content,
			String sourceJson) throws SQLException {
		if (!result.next()
				|| !id.equals(result.getObject("id", UUID.class))
				|| result.getLong("seq") != seq
				|| !kind.equals(result.getString("ath_kind"))
				|| (membershipId == null ? result.getObject("thr_mbr_id") != null
						: !membershipId.equals(result.getObject("thr_mbr_id", UUID.class)))
				|| !"COMPLETE".equals(result.getString("status"))
				|| !content.equals(result.getString("content"))
				|| result.getObject("pyl_json") != null
				|| (sourceJson == null ? result.getObject("src_json") != null
						: !sourceJson.equals(result.getObject("src_json").toString()))
				|| result.getObject("completed_at") == null) {
			throw new IllegalStateException("Migrated message fixture did not match: " + id);
		}
	}

	private static void assertCount(String jdbcUrl, String username, String password, String schema, String table,
			long expected) throws SQLException {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
			PreparedStatement statement = connection.prepareStatement("select count(*) from " + schema + "." + table);
			ResultSet result = statement.executeQuery()) {
			result.next();
			if (result.getLong(1) != expected) {
				throw new IllegalStateException("Unexpected " + table + " count after failed migration");
			}
		}
	}

	private static String requiredEnvironment(String key) {
		String value = System.getenv(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(key + " must be set");
		}
		return value;
	}

}
