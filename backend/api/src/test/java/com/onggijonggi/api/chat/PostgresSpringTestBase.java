package com.onggijonggi.api.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : PostgresSpringTestBase.java
 * Description : 실제 PostgreSQL 16에 전체 Flyway를 적용한 Spring 통합 테스트의 기반이다. H2 테스트가 재현하지 못하는
 *               jsonb·trigger·`FOR UPDATE`·복합 제약이 실제 저장 경로(JPA·JdbcTemplate)에서 동작하는지 확인한다.
 *               Fake 설정 클래스가 이 패키지에 package-private이라 같은 패키지에 둔다. Docker가 없으면 건너뛴다.
 *               bootstrap 설정 파일 경로는 컨텍스트마다 고정된 임시 파일이고, 테스트가 내용을 바꿔 쓴 뒤 서비스를 직접 호출한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
abstract class PostgresSpringTestBase {

	private static PostgreSQLContainer<?> container;

	/**
	 * 컨테이너는 JVM당 한 번만 띄운다. @Container 수명은 테스트 클래스마다 컨테이너를 내렸다 올려 포트가 바뀌는데,
	 * 캐시된 Spring 컨텍스트는 처음 포트를 계속 보기 때문이다. 종료는 Testcontainers의 reaper가 맡는다.
	 */
	static synchronized PostgreSQLContainer<?> postgres() {
		if (container == null) {
			container = new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("rbac_spring")
					.withUsername("test")
					.withPassword("test");
			container.start();
		}
		return container;
	}

	/** 컨텍스트가 뜰 때 비어 있어야 bootstrap이 아무것도 하지 않는다(빈 파일은 설정 없음으로 본다). */
	static final Path BOOTSTRAP_CONFIG = createConfigFile();

	@DynamicPropertySource
	static void postgres(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> postgres().getJdbcUrl());
		registry.add("spring.datasource.username", () -> postgres().getUsername());
		registry.add("spring.datasource.password", () -> postgres().getPassword());
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("app.rbac.bootstrap-config-path", BOOTSTRAP_CONFIG::toString);
	}

	private static Path createConfigFile() {
		try {
			Path file = Files.createTempFile("rbac-bootstrap-", ".yml");
			file.toFile().deleteOnExit();
			return file;
		} catch (IOException exception) {
			throw new IllegalStateException("임시 bootstrap 설정 파일을 만들 수 없다", exception);
		}
	}
}
