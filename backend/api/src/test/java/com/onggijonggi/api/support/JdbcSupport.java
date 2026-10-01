package com.onggijonggi.api.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Class Name : JdbcSupport.java
 * Description : Spring 컨텍스트 없이 Connection으로 직접 SQL을 실행하는 마이그레이션 테스트용 도우미.
 */
public final class JdbcSupport {

	private JdbcSupport() {
	}

	public static void execute(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			bind(statement, values);
			statement.executeUpdate();
		}
	}

	public static int count(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			bind(statement, values);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getInt(1);
			}
		}
	}

	public static UUID uuid(Connection connection, String sql, UUID id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, id);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getObject(1, UUID.class);
			}
		}
	}

	private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
		for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
	}
}
