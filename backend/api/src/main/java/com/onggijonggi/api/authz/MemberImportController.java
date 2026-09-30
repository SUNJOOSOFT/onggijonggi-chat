package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.CurrentActorProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Class Name : MemberImportController.java
 * Description : 01·CLIENT ↔ 03·CORE 팀·직급 배정 CSV 임포트(scripts/import-members.mjs, 데모 배정 화면). 본문은 CSV 텍스트이고
 *               apply=true일 때만 저장한다. casbin 프로필에서만 등록된다 — 끄면 이 주소가 없다.
 *               조직·직급 배정은 control plane이라 PLATFORM_ADMIN만 부른다(#299) — /api/platform/** 경계가 막는다.
 *               실행한 PLATFORM_ADMIN이 USER 행위자로 감사에 남는다.
 */
@RestController
@Profile("casbin")
public class MemberImportController {

	private final CurrentActorProvider currentActorProvider;
	private final MemberImportService importService;

	public MemberImportController(CurrentActorProvider currentActorProvider, MemberImportService importService) {
		this.currentActorProvider = currentActorProvider;
		this.importService = importService;
	}

	@PostMapping(path = "/api/platform/rbac/members/import", consumes = { "text/csv", "text/plain" })
	public Mono<MemberImportService.Report> importMembers(@RequestBody(required = false) String csv,
			@RequestParam(defaultValue = "false") boolean apply) {
		return currentActorProvider.currentActor().flatMap(actor -> importService.run(csv, apply, actor.userId()));
	}
}
