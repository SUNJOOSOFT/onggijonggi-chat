package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

/**
 * Class Name : RbacSwitchIndependenceTest.java
 * Description : 권한 강제 스위치(app.rbac.enforce)의 기본값과, 배포 1의 PLATFORM_ADMIN 엔드포인트가 스위치와 무관하게
 *               서비스를 호출하고 결과를 그대로 돌려주는지 검증한다(DB-TST-064). 이 컨트롤러는 RbacProperties를 아예
 *               주입받지 않으므로 스위치 값에 의존할 수 없다.
 */
class RbacSwitchIndependenceTest {

	@Test
	void defaultsEnforcementToFalse() {
		assertThat(new RbacProperties().isEnforce()).isFalse();
	}

	@Test
	void bootstrapRetryReturnsTheServiceResultWithoutConsultingTheSwitch() {
		RbacBootstrapService bootstrap = Mockito.mock(RbacBootstrapService.class);
		RbacBootstrapResult result = new RbacBootstrapResult(List.of("acme"), List.of("acme"), List.of(), List.of(),
				List.of());
		when(bootstrap.runCurrentConfiguration()).thenReturn(result);

		ResponseEntity<RbacBootstrapResult> response = controller(bootstrap).retryBootstrap().block();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isSameAs(result);
		verify(bootstrap).runCurrentConfiguration();
	}

	@Test
	void bootstrapRetryExplainsAnInvalidConfigurationWith422() {
		RbacBootstrapService bootstrap = Mockito.mock(RbacBootstrapService.class);
		when(bootstrap.runCurrentConfiguration()).thenThrow(new RbacBootstrapConfigurationException(
				List.of("tenant acme: tnn_key가 중복이다", "tenant acme org_unit sales: COMMON VIEWER 부여가 선언돼 있지 않다")));

		ResponseEntity<RbacBootstrapResult> response = controller(bootstrap).retryBootstrap().block();

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(response.getBody().failures()).containsExactly("tenant acme: tnn_key가 중복이다",
				"tenant acme org_unit sales: COMMON VIEWER 부여가 선언돼 있지 않다");
		assertThat(response.getBody().processedTenants()).isEmpty();
	}

	@Test
	void cutoverValidationPassesOnlyTheEnabledKeycloakSubjectsToTheService() {
		// Keycloak에서는 누가 로그인할 수 있는지만 쓴다. tenant 속성은 넘기지 않는다 — 현재 Tenant는 DB 배정이 정한다(#299).
		KeycloakAdminClient keycloak = Mockito.mock(KeycloakAdminClient.class);
		CutoverValidationService validation = Mockito.mock(CutoverValidationService.class);
		CutoverValidationResult result = new CutoverValidationResult(null, List.of());
		when(keycloak.listEnabledUserSubjects()).thenReturn(Mono.just(List.of("subject")));
		when(validation.validateWithKeycloak(List.of("subject"))).thenReturn(result);

		PlatformRbacController controller = new PlatformRbacController(Mockito.mock(RbacBootstrapService.class), keycloak,
				validation);

		assertThat(controller.validateCutover().block()).isSameAs(result);
		verify(validation).validateWithKeycloak(List.of("subject"));
	}

	private PlatformRbacController controller(RbacBootstrapService bootstrap) {
		return new PlatformRbacController(bootstrap, Mockito.mock(KeycloakAdminClient.class),
				Mockito.mock(CutoverValidationService.class));
	}
}
