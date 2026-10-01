package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static com.onggijonggi.api.support.JdbcSupport.execute;
import com.onggijonggi.api.support.TestFiles;
import com.onggijonggi.api.support.MigrationVersions;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.api.authz.RbacBootstrapService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : UpgradeWithExistingThreadsPostgresTest.java
 * Description : 대화가 이미 있고 Tenant가 없는 배포(권한 기능을 켜지 않고 쓰던 배포)를 절체 migration까지 올리는 경로를 실제
 *               PostgreSQL 16에서 확인한다. 절체 migration은 기존 Thread가 있으면 유일한 ACTIVE Tenant가 있어야 하는데 Tenant는
 *               앱이 뜬 뒤 bootstrap이 만든다. 그래서 (1) Tenant 없이 migration하면 원인을 알려 주며 멈추고 아무것도 바꾸지 않는다,
 *               (2) Flyway를 절체 migration 앞 버전까지만 적용해 새 앱을 띄워 bootstrap으로 Tenant·COMMON을 만들면, (3) 그 뒤 migration이 성공해 기존 대화를 COMMON에
 *               놓고 자식 행의 Tenant까지 채운다. 테스트 순서가 곧 운영 절차다(INSTALL.md「v0.2에서 올릴 때」).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UpgradeWithExistingThreadsPostgresTest {

	/** 절체 migration 바로 앞 버전. 이 버전까지가 "올리기 전"의 스키마다. */
	private static final String BEFORE_CUTOVER = MigrationVersions.BEFORE_CUTOVER;
	private static final Path SETUP = TestFiles.tempYaml("upgrade-setup-");
	private static boolean legacySeeded;

	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID DIRECT = UUID.randomUUID();
	private static final UUID COLLAB = UUID.randomUUID();

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("upgrade_test").withUsername("test").withPassword("test");

	@DynamicPropertySource
	static void postgres(DynamicPropertyRegistry registry) {
		seedLegacyDatabase();
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		// 올리는 절차(SPRING_FLYWAY_TARGET)로 스키마가 절체 migration 앞에 머문 상태를 재현한다. 앱은 Flyway 없이 그 스키마 위에서 뜬다.
		registry.add("spring.flyway.enabled", () -> "false");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("app.rbac.workspace-setup-path", SETUP::toString);
	}

	@Autowired
	private RbacBootstrapService bootstrap;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@Order(1)
	void migratingWithoutATenantStopsWithGuidanceAndChangesNothing() {
		assertThatThrownBy(() -> flyway(null).migrate())
				.isInstanceOf(FlywayException.class)
				.hasMessageContaining("exactly one ACTIVE tenant")
				.hasMessageContaining("bootstrap");

		// 트랜잭션째 롤백돼 절체 표지 테이블도 NOT NULL도 생기지 않았다.
		assertThat(jdbc.queryForObject("select to_regclass('ctv')::text", String.class)).isNull();
		assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_name = 'thr' and column_name = 'tnn_id'",
				String.class)).isEqualTo("YES");
	}

	@Test
	@Order(2)
	void bootstrapWithFlywayOffPlacesExistingThreadsThenMigrationCompletes() throws Exception {
		Files.writeString(SETUP, Files.readString(Path.of("../../infra/config/workspace-setup.default.yml")));
		var result = bootstrap.runCurrentConfiguration();
		assertThat(result.failures()).isEmpty();
		UUID tenant = jdbc.queryForObject("select id from tnn where tnn_key = 'ogjg'", UUID.class);
		UUID common = jdbc.queryForObject("select id from wrk_node where node_key = 'common'", UUID.class);
		// bootstrap은 Tenant·COMMON만 만든다. 기존 대화는 아직 그대로다 — 옮기는 것은 절체 migration이다.
		assertThat(jdbc.queryForObject("select wrk_node_id from thr where id = ?", UUID.class, DIRECT)).isNull();
		assertThat(jdbc.queryForObject("select tnn_id from thr_mbr where thr_id = ? limit 1", UUID.class, DIRECT)).isNull();
		int messages = jdbc.queryForObject("select count(*) from msg", Integer.class);

		flyway(null).migrate();

		assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success = false", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select wrk_node_id from thr where id = ?", UUID.class, DIRECT)).isEqualTo(common);
		assertThat(jdbc.queryForObject("select wrk_node_id from thr where id = ?", UUID.class, COLLAB)).isEqualTo(common);
		assertThat(jdbc.queryForObject("select tnn_id from thr where id = ?", UUID.class, COLLAB)).isEqualTo(tenant);
		assertThat(jdbc.queryForObject("select tnn_id from thr_mbr where thr_id = ? limit 1", UUID.class, DIRECT)).isEqualTo(tenant);
		assertThat(jdbc.queryForObject("select count(*) from msg where tnn_id = ?", Integer.class, tenant)).isEqualTo(messages);
		assertThat(jdbc.queryForObject("select count(*) from msg where content = 'legacy question'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_name = 'thr' and column_name = 'tnn_id'",
				String.class)).isEqualTo("NO");
	}

	private static Flyway flyway(String target) {
		var configuration = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration");
		if (target != null) configuration.target(target);
		return configuration.load();
	}

	/** 절체 migration 앞까지 스키마를 만들고, Tenant 없이 쓰던 배포처럼 대화를 넣는다. 컨텍스트가 뜨기 전에 한 번만 한다. */
	private static synchronized void seedLegacyDatabase() {
		if (legacySeeded) return;
		flyway(BEFORE_CUTOVER).migrate();
		try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			UUID member = UUID.randomUUID();
			execute(connection, "insert into app_user (id, keycloak_subj) values (?, 'legacy-owner')", OWNER);
			execute(connection, "insert into thr (id, kind, drc_own_user_id, created_user_id, title) values (?, 'DIRECT', ?, ?, 'legacy direct')", DIRECT, OWNER, OWNER);
			execute(connection, "insert into thr (id, kind, created_user_id, title) values (?, 'COLLAB', ?, 'legacy collab')", COLLAB, OWNER);
			execute(connection, "insert into thr_mbr (id, thr_id, user_id, role, created_by_user_id) values (?, ?, ?, 'OWNER', ?)", member, DIRECT, OWNER, OWNER);
			execute(connection, "insert into msg (id, thr_id, seq, ath_kind, thr_mbr_id, status, content, completed_at) values (?, ?, 1, 'HUMAN', ?, 'COMPLETE', 'legacy question', now())", UUID.randomUUID(), DIRECT, member);
		} catch (java.sql.SQLException error) {
			throw new IllegalStateException("이전 버전 데이터를 넣을 수 없다", error);
		}
		legacySeeded = true;
	}
}
