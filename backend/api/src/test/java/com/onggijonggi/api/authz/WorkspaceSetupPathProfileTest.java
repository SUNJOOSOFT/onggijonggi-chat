package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

/**
 * Class Name : WorkspaceSetupPathProfileTest.java
 * Description : 프로필별 조직 구조 설정 경로를 실제 설정 파일에서 읽어 확인한다. 권한 기능(casbin)을 켜지 않은 기본 배포(prod)는
 *               Tenant 하나만 만드는 기본 설정을, prod에 casbin을 뒤에 붙이면 팀·규칙이 있는 설정을 읽어야 한다. 반대로 잡히면
 *               기존 casbin 사용자가 아무 오류 없이 팀·규칙이 없는 설정으로 돈다. 프로필은 나중에 적은 것이 이긴다.
 */
class WorkspaceSetupPathProfileTest {

	@Test
	void productionWithoutCasbinReadsTheDefaultTenantOnly() {
		assertThat(path("prod")).isEqualTo("/config/workspace-setup.default.yml");
	}

	@Test
	void casbinAfterProdReadsTheFullOrganizationSetup() {
		assertThat(path("prod", "casbin")).isEqualTo("/config/workspace-setup.yml");
	}

	private static String path(String... profiles) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.setActiveProfiles(profiles);
		ConfigDataEnvironmentPostProcessor.applyTo(environment);
		return environment.getProperty("app.rbac.workspace-setup-path");
	}
}
