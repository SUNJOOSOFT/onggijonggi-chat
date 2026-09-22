package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : PlatformRbacController.java
 * Description : PLATFORM_ADMIN 전용 control-plane 엔드포인트(`/api/platform/**`). bootstrap 명시 재시도와 절체 사전 검증이다.
 *               `app.rbac.enforce` 스위치와 무관하게 동작한다(배포 1에서 필요하다). 재시도는 같은 마운트 설정을 다시
 *               적용할 뿐 drift를 자동으로 해결하지 않는다.
 */
@RestController
public class PlatformRbacController {

	private final RbacBootstrapService bootstrapService;
	private final KeycloakAdminClient keycloakAdminClient;
	private final CutoverValidationService cutoverValidationService;

	public PlatformRbacController(RbacBootstrapService bootstrapService,
			KeycloakAdminClient keycloakAdminClient, CutoverValidationService cutoverValidationService) {
		this.bootstrapService = bootstrapService;
		this.keycloakAdminClient = keycloakAdminClient;
		this.cutoverValidationService = cutoverValidationService;
	}

	/**
	 * 설정이 잘못됐으면 아무것도 실행되지 않았다는 뜻으로 422와 함께 검증 실패 사유를 `failures`에 담아 돌려준다
	 * (운영자가 응답만으로 원인을 고칠 수 있어야 한다).
	 */
	@PostMapping("/api/platform/rbac/bootstrap/retry")
	public Mono<ResponseEntity<RbacBootstrapResult>> retryBootstrap() {
		return Mono.fromCallable(() -> ResponseEntity.ok(bootstrapService.runCurrentConfiguration()))
				.onErrorResume(RbacBootstrapConfigurationException.class,
						exception -> Mono.just(ResponseEntity.unprocessableContent().body(new RbacBootstrapResult(
								List.of(), List.of(), List.of(), List.of(), exception.getProblems()))))
				.subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping("/api/platform/rbac/cutover-validation")
	public Mono<CutoverValidationResult> validateCutover() {
		return keycloakAdminClient.listEnabledTenantUsers()
				.flatMap(users -> Mono.fromCallable(() -> cutoverValidationService.validate(users))
						.subscribeOn(Schedulers.boundedElastic()));
	}
}
