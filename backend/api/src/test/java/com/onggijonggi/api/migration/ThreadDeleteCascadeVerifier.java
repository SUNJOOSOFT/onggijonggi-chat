package com.onggijonggi.api.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Class Name : ThreadDeleteCascadeVerifier.java
 * Description : idempotency 키 행이 남아 있는 DIRECT·COLLAB 방도 삭제가 FK 위반 없이 끝나는지
 *               실제 PostgreSQL fixture로 검증하는 CI 전용 실행기(이슈 #251). H2는 이 FK를
 *               재현하지 못해(엔티티에 매핑되지 않는 idempotency 테이블이라 create-drop 스키마
 *               생성 자체가 다르다) FlywayMigrationVerifier가 적용을 끝낸 실제 PostgreSQL 위에서
 *               돈다.
 */
public final class ThreadDeleteCascadeVerifier {

	private static final String JDBC_URL = "FLYWAY_VERIFY_JDBC_URL";
	private static final String USERNAME = "FLYWAY_VERIFY_USERNAME";
	private static final String PASSWORD = "FLYWAY_VERIFY_PASSWORD";

	private ThreadDeleteCascadeVerifier() {
	}

	public static void main(String[] args) throws SQLException {
		String jdbcUrl = requiredEnvironment(JDBC_URL);
		String username = requiredEnvironment(USERNAME);
		String password = requiredEnvironment(PASSWORD);

		try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
			verifyDirectThreadWithMsgIdmKey(connection);
			verifyCollabThreadWithThrIdmKey(connection);
		}
		System.out.println("Thread delete cascade fixtures verified.");
	}

	/** DIRECT 발화 idempotency 키(msg_idm_key)가 남아 있어도 Thread 삭제가 FK 위반 없이 끝나는지 확인한다. */
	private static void verifyDirectThreadWithMsgIdmKey(Connection connection) throws SQLException {
		UUID userId = insertUser(connection, "fixture-251-direct");
		UUID threadId = insertThread(connection, "DIRECT", userId);
		UUID memberId = insertMember(connection, threadId, userId);
		UUID humanMsgId = insertMessage(connection, threadId, 0, "HUMAN", memberId, "사람 발화");
		UUID agentMsgId = insertMessage(connection, threadId, 1, "AGENT", null, "AI 응답");
		insertMsgIdmKey(connection, userId, threadId, humanMsgId, agentMsgId);

		deleteThread(connection, threadId);

		assertGone(connection, "thr", threadId);
		assertGone(connection, "msg_idm_key", "thr_id", threadId);
	}

	/** COLLAB 방 생성 idempotency 키(thr_idm_key)가 남아 있어도 Thread 삭제가 FK 위반 없이 끝나는지 확인한다. */
	private static void verifyCollabThreadWithThrIdmKey(Connection connection) throws SQLException {
		UUID userId = insertUser(connection, "fixture-251-collab");
		UUID threadId = insertThread(connection, "COLLAB", userId);
		insertMember(connection, threadId, userId);
		insertThrIdmKey(connection, userId, threadId);

		deleteThread(connection, threadId);

		assertGone(connection, "thr", threadId);
		assertGone(connection, "thr_idm_key", "thr_id", threadId);
	}

	private static UUID insertUser(Connection connection, String subject) throws SQLException {
		UUID id = UUID.randomUUID();
		try (PreparedStatement statement = connection
				.prepareStatement("insert into app_user (id, keycloak_subj) values (?, ?)")) {
			statement.setObject(1, id);
			statement.setString(2, subject);
			statement.executeUpdate();
		}
		return id;
	}

	private static UUID insertThread(Connection connection, String kind, UUID userId) throws SQLException {
		UUID id = UUID.randomUUID();
		boolean direct = "DIRECT".equals(kind);
		try (PreparedStatement statement = connection.prepareStatement("""
				insert into thr (id, kind, status, drc_own_user_id, created_user_id, title, next_seq)
				values (?, ?, 'ACTIVE', ?, ?, ?, 0)
				""")) {
			statement.setObject(1, id);
			statement.setString(2, kind);
			statement.setObject(3, direct ? userId : null);
			statement.setObject(4, userId);
			statement.setString(5, "#251 fixture");
			statement.executeUpdate();
		}
		return id;
	}

	private static UUID insertMember(Connection connection, UUID threadId, UUID userId) throws SQLException {
		UUID id = UUID.randomUUID();
		try (PreparedStatement statement = connection.prepareStatement("""
				insert into thr_mbr (id, thr_id, user_id, role, status, created_by_user_id)
				values (?, ?, ?, 'OWNER', 'ACTIVE', ?)
				""")) {
			statement.setObject(1, id);
			statement.setObject(2, threadId);
			statement.setObject(3, userId);
			statement.setObject(4, userId);
			statement.executeUpdate();
		}
		return id;
	}

	private static UUID insertMessage(Connection connection, UUID threadId, long seq, String athKind, UUID memberId,
			String content) throws SQLException {
		UUID id = UUID.randomUUID();
		try (PreparedStatement statement = connection.prepareStatement("""
				insert into msg (id, thr_id, seq, ath_kind, thr_mbr_id, status, content, completed_at)
				values (?, ?, ?, ?, ?, 'COMPLETE', ?, now())
				""")) {
			statement.setObject(1, id);
			statement.setObject(2, threadId);
			statement.setLong(3, seq);
			statement.setString(4, athKind);
			statement.setObject(5, memberId);
			statement.setString(6, content);
			statement.executeUpdate();
		}
		return id;
	}

	private static void insertMsgIdmKey(Connection connection, UUID userId, UUID threadId, UUID humanMsgId,
			UUID agentMsgId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				insert into msg_idm_key (id, user_id, idm_key, content, thr_id, hmn_msg_id, hmn_seq, agn_msg_id, agn_seq)
				values (?, ?, ?, ?, ?, ?, 0, ?, 1)
				""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, userId);
			statement.setString(3, "fixture-251-msg-key");
			statement.setString(4, "사람 발화");
			statement.setObject(5, threadId);
			statement.setObject(6, humanMsgId);
			statement.setObject(7, agentMsgId);
			statement.executeUpdate();
		}
	}

	private static void insertThrIdmKey(Connection connection, UUID userId, UUID threadId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				insert into thr_idm_key (id, user_id, idm_key, title, thr_id)
				values (?, ?, ?, ?, ?)
				""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, userId);
			statement.setString(3, "fixture-251-thr-key");
			statement.setString(4, "#251 fixture");
			statement.setObject(5, threadId);
			statement.executeUpdate();
		}
	}

	/** ThreadLifecycleService.delete와 동일한 동작 — Thread 행만 지우고 나머지는 FK cascade에 맡긴다. */
	private static void deleteThread(Connection connection, UUID threadId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("delete from thr where id = ?")) {
			statement.setObject(1, threadId);
			statement.executeUpdate();
		}
	}

	private static void assertGone(Connection connection, String table, UUID id) throws SQLException {
		assertGone(connection, table, "id", id);
	}

	private static void assertGone(Connection connection, String table, String column, UUID id) throws SQLException {
		try (PreparedStatement statement = connection
				.prepareStatement("select count(*) from " + table + " where " + column + " = ?")) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) {
				result.next();
				if (result.getLong(1) != 0) {
					throw new IllegalStateException(table + " still has rows referencing " + id + " after Thread delete");
				}
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
