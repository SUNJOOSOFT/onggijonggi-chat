package com.onggijonggi.api.authz;

import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Class Name : CutoverMarkerGuard.java
 * Description : 완료 표지가 기록된 DB에서 RBAC 강제를 끄고 서버가 기동되는 것을 막는다.
 */
@Component
public class CutoverMarkerGuard implements SmartLifecycle {

	private final JdbcTemplate jdbc;
	private final RbacProperties properties;
	private volatile boolean running;

	public CutoverMarkerGuard(JdbcTemplate jdbc, RbacProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	@Override
	public void start() {
		if (!properties.isEnforce() && hasCutoverMarker()) {
			throw new IllegalStateException("절체 완료 DB는 app.rbac.enforce=false로 기동할 수 없습니다");
		}
		running = true;
	}

	@Override
	public void stop() {
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return 0;
	}

	boolean hasCutoverMarker() {
		// 앱이 쓰는 schema(search_path)의 ctv만 본다. 테이블이 없으면 표지도 없다.
		// 권한 오류 같은 다른 조회 실패는 삼키지 않는다 — 표지를 읽지 못한 채 기동하지 않는다(fail-closed).
		try {
			Integer count = jdbc.queryForObject("select count(*) from ctv", Integer.class);
			return count != null && count > 0;
		} catch (BadSqlGrammarException tableMissing) {
			String sqlState = tableMissing.getSQLException().getSQLState();
			// PostgreSQL undefined_table와 H2의 table-not-found만 구 스키마로 취급한다.
			// 같은 예외로 번역되는 권한 오류(42501) 등은 기동을 차단한다.
			if ("42P01".equals(sqlState) || "42S02".equals(sqlState) || "42S04".equals(sqlState)) {
				return false;
			}
			throw tableMissing;
		}
	}
}
