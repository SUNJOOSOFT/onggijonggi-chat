package com.onggijonggi.api.migration;

import jakarta.persistence.Entity;
import java.util.List;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Class Name : HibernateMappingVerifier.java
 * Description : Flyway가 적용을 끝낸 PostgreSQL 스키마를 기준으로 JPA 엔티티의 테이블·컬럼·타입
 * 매핑을 Hibernate {@code ddl-auto=validate}로 검증하는 CI 전용 실행기. Keycloak·LLM 같은 무관한
 * 외부 의존성을 배제하기 위해 {@code ApiApplication}을 띄우지 않고 Spring 컨텍스트 없이 Hibernate
 * 네이티브 부트스트랩 API만으로 SessionFactory를 만든다. FlywayMigrationVerifier(같은 디렉터리)와
 * 대칭인 구조다.
 */
public final class HibernateMappingVerifier {

	private static final String JDBC_URL = "FLYWAY_VERIFY_JDBC_URL";
	private static final String USERNAME = "FLYWAY_VERIFY_USERNAME";
	private static final String PASSWORD = "FLYWAY_VERIFY_PASSWORD";

	// ApiApplication의 @EntityScan("com.onggijonggi")와 정확히 같은 범위를 봐야 프로덕션과
	// 같은 매핑을 검증한 게 된다 — backend/common으로 좁히지 않는다.
	private static final String ENTITY_SCAN_BASE_PACKAGE = "com.onggijonggi";

	// 스캔이 base package 설정 실수로 빈 결과를 내면 buildSessionFactory()는 검증할 대상이
	// 없어서 조용히 성공해 버린다 — 이 검증기의 존재 이유(거짓 통과 방지)가 스스로에게는
	// 적용 안 되는 사각지대라 최소 개수로 막는다. 하한선이라 엔티티가 늘어나는 쪽은 이 값을
	// 안 올려도 계속 통과한다 — 올려야 하는 경우는 스캔 범위를 실수로 좁혔을 때뿐이다.
	// 9에서 7로 내린 것은 스캔 범위 축소가 아니라, ChatSess·ChatMsg 엔티티가 실제로 죽은
	// 코드가 되어 제거됐기 때문이다(이슈 #164).
	private static final int MIN_EXPECTED_ENTITIES = 7;

	private HibernateMappingVerifier() {
	}

	public static void main(String[] args) {
		var jdbcUrl = requiredEnvironment(JDBC_URL);
		var username = requiredEnvironment(USERNAME);
		var password = requiredEnvironment(PASSWORD);

		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
			.applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
			.applySetting("hibernate.connection.url", jdbcUrl)
			.applySetting("hibernate.connection.username", username)
			.applySetting("hibernate.connection.password", password)
			.applySetting("hibernate.hbm2ddl.auto", "validate")
			// Spring Boot 기본 naming strategy와 맞추지 않으면 camelCase → snake_case 변환이
			// 안 걸린 순수 Hibernate 기본값으로 돌아가 버려, 실제로는 맞는 매핑을 전부 틀렸다고
			// 오판하거나 반대로 진짜 문제를 놓칠 수 있다.
			// Spring Boot 4(Hibernate 7)부터 Spring 전용 physical naming strategy가 없어지고
			// Hibernate 자체 제공 클래스로 대체됐다 — HibernateProperties.Naming 기본값을
			// jar 안에서 직접 확인(org.springframework.boot:spring-boot-hibernate:4.0.7).
			.applySetting("hibernate.physical_naming_strategy",
				"org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
			.applySetting("hibernate.implicit_naming_strategy",
				"org.springframework.boot.hibernate.SpringImplicitNamingStrategy")
			.build();

		try {
			var sources = new MetadataSources(registry);
			var entityClasses = scanEntities(ENTITY_SCAN_BASE_PACKAGE);
			if (entityClasses.size() < MIN_EXPECTED_ENTITIES) {
				throw new IllegalStateException(
					"Expected at least %d @Entity classes under %s but found %d — check the scan base package."
						.formatted(MIN_EXPECTED_ENTITIES, ENTITY_SCAN_BASE_PACKAGE, entityClasses.size()));
			}
			entityClasses.forEach(sources::addAnnotatedClass);

			try (var sessionFactory = sources.buildMetadata().buildSessionFactory()) {
				System.out.printf("Hibernate mapping matches the PostgreSQL schema (%d entities):%n",
					entityClasses.size());
				entityClasses.forEach(entityClass -> System.out.println("- " + entityClass.getName()));
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}

	private static String requiredEnvironment(String key) {
		var value = System.getenv(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(key + " must be set");
		}
		return value;
	}

	// Spring 컨텍스트를 띄우지 않고 클래스만 찾는다 — 어떤 Bean도 등록되지 않으므로
	// "Keycloak·LLM 같은 무관한 외부 의존성 없이" 제약과 충돌하지 않는다. 새 엔티티를 추가했는데
	// 등록을 잊는 실수를 원천적으로 막기 위해 명시적 나열 대신 스캔을 쓴다.
	private static List<Class<?>> scanEntities(String basePackage) {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		return scanner.findCandidateComponents(basePackage).stream()
			.<Class<?>>map(beanDefinition -> loadClass(beanDefinition.getBeanClassName()))
			.toList();
	}

	private static Class<?> loadClass(String className) {
		try {
			return Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Failed to load scanned entity class: " + className, e);
		}
	}

}
