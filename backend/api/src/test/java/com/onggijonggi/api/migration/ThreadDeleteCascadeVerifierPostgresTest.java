package com.onggijonggi.api.migration;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : ThreadDeleteCascadeVerifierPostgresTest.java
 * Description : 전체 Flyway 적용 뒤 DIRECT와 COLLAB 삭제 fixture가 새 Tenant 제약에서도 통과하는지 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ThreadDeleteCascadeVerifierPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("delete_cascade_test").withUsername("test").withPassword("test");

	@Test
	void existingDeleteCascadeFixtureWorksAfterTenantCutover() throws Exception {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		try (var connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			ThreadDeleteCascadeVerifier.verify(connection);
		}
	}
}
