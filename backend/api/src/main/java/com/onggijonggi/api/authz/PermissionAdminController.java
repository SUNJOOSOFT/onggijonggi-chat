package com.onggijonggi.api.authz;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Class Name : PermissionAdminController.java
 * Description : 01·CLIENT ↔ 03·CORE 권한 관리 화면(/admin/permissions)의 API. casbin 프로필에서만 등록된다 — 끄면 주소가 없고,
 *               화면은 status가 실패하는 것(꺼져서 404, 권한이 없어 403)을 보고 메뉴를 숨긴다.
 *               조직·직급 배정은 control plane이라 PLATFORM_ADMIN만 부른다(#299) — /api/platform/** 경계가 막는다.
 *               팀·직급은 읽기만 한다 — 바꾸는 곳은 속성 파일(app.rbac.members-path)이다.
 */
@RestController
@Profile("casbin")
public class PermissionAdminController {

	private final PermissionAdminService service;

	public PermissionAdminController(PermissionAdminService service) {
		this.service = service;
	}

	/** 화면이 메뉴를 보일지 정할 때 부른다. 이 컨트롤러가 등록돼 있으면 켜진 것이다. */
	@GetMapping("/api/platform/rbac/admin/status")
	public Map<String, Boolean> status() {
		return Map.of("enabled", true);
	}

	@GetMapping("/api/platform/rbac/admin/overview")
	public Mono<PermissionAdminService.Overview> overview() {
		return service.overview();
	}
}
