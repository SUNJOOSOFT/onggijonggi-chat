package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Class Name : CutoverMarkerGuardTest.java
 * Description : 절체 표지가 있는 DB에서 비강제 기동을 차단하고, 표지가 없는 DB는 기존 동작을 유지한다.
 */
class CutoverMarkerGuardTest {

	@Test
	void markerReadPermissionFailureMustNotBeTreatedAsAbsentTable() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		BadSqlGrammarException denied = new BadSqlGrammarException("query", "select count(*) from ctv",
				new SQLException("permission denied for table ctv", "42501"));
		when(jdbc.queryForObject("select count(*) from ctv", Integer.class)).thenThrow(denied);
		CutoverMarkerGuard guard = new CutoverMarkerGuard(jdbc, new RbacProperties());
		assertThatThrownBy(guard::start).isSameAs(denied);
		assertThat(guard.isRunning()).isFalse();
	}

	@Test
	void postgresUndefinedTableAllowsPreCutoverStartup() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForObject("select count(*) from ctv", Integer.class)).thenThrow(
				new BadSqlGrammarException("query", "select count(*) from ctv",
						new SQLException("relation ctv does not exist", "42P01")));
		CutoverMarkerGuard guard = new CutoverMarkerGuard(jdbc, new RbacProperties());
		guard.start();
		assertThat(guard.isRunning()).isTrue();
	}

	@Test
	void absentTableOrMarkerDoesNotForceEnforcement() {
		JdbcTemplate jdbc = database();
		RbacProperties properties = new RbacProperties();
		new CutoverMarkerGuard(jdbc, properties).start();
		jdbc.execute("create table ctv (id smallint primary key)");
		new CutoverMarkerGuard(jdbc, properties).start();
	}

	@Test
	void markerRejectsDisabledEnforcementButAllowsEnabledEnforcement() {
		JdbcTemplate jdbc = database();
		jdbc.execute("create table ctv (id smallint primary key)");
		jdbc.update("insert into ctv (id) values (1)");
		RbacProperties properties = new RbacProperties();
		CutoverMarkerGuard guard = new CutoverMarkerGuard(jdbc, properties);
		assertThat(guard.getPhase()).isLessThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE);
		assertThatThrownBy(guard::start)
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("app.rbac.enforce=false");
		assertThat(guard.isRunning()).isFalse();
		properties.setEnforce(true);
		guard.start();
		assertThat(guard.isRunning()).isTrue();
		guard.stop();
		assertThat(guard.isRunning()).isFalse();
	}

	@Test
	void markerFailureStopsLifecycleBeforeWebServerStartPhase() {
		JdbcTemplate jdbc = database();
		jdbc.execute("create table ctv (id smallint primary key)");
		jdbc.update("insert into ctv (id) values (1)");
		AtomicBoolean webServerStarted = new AtomicBoolean();
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(CutoverMarkerGuard.class, () -> new CutoverMarkerGuard(jdbc, new RbacProperties()));
			context.registerBean("webServerPhase", SmartLifecycle.class, () -> new SmartLifecycle() {
				@Override public void start() { webServerStarted.set(true); }
				@Override public void stop() { webServerStarted.set(false); }
				@Override public boolean isRunning() { return webServerStarted.get(); }
				@Override public int getPhase() { return WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE; }
			});
			assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalStateException.class);
			assertThat(webServerStarted).isFalse();
		}
	}

	private static JdbcTemplate database() {
		DriverManagerDataSource source = new DriverManagerDataSource();
		source.setDriverClassName("org.h2.Driver");
		source.setUrl("jdbc:h2:mem:cutover_marker_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
		return new JdbcTemplate(source);
	}
}
