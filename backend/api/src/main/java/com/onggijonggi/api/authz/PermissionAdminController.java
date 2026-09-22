package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.common.authz.Rank;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Class Name : PermissionAdminController.java
 * Description : 01·CLIENT ↔ 03·CORE 권한 관리 화면(/admin/permissions)의 API. casbin 프로필에서만 등록된다 — 끄면 주소가 없고,
 *               화면은 status가 404인 것을 보고 메뉴를 숨긴다.
 *               v0.3은 실제 사용자에게 나가지 않아 로그인한 누구나 부를 수 있다(/api/**의 USER). 실제 배포 전에 ADMIN만
 *               부를 수 있게 막는다(부채). 배정 변경은 배정 서비스를 거쳐 USER 행위자 이력을 남긴다.
 */
@RestController
@Profile("casbin")
public class PermissionAdminController {

	private final CurrentActorProvider currentActorProvider;
	private final PermissionAdminService service;

	public PermissionAdminController(CurrentActorProvider currentActorProvider, PermissionAdminService service) {
		this.currentActorProvider = currentActorProvider;
		this.service = service;
	}

	/** 화면이 메뉴를 보일지 정할 때 부른다. 이 컨트롤러가 등록돼 있으면 켜진 것이다. */
	@GetMapping("/api/authz/admin/status")
	public Map<String, Boolean> status() {
		return Map.of("enabled", true);
	}

	@GetMapping("/api/authz/admin/overview")
	public Mono<PermissionAdminService.Overview> overview() {
		return service.overview();
	}

	public record AssignmentRequest(UUID teamId, String rank) {
	}

	@PutMapping("/api/authz/admin/people/{subject}/assignment")
	public Mono<Map<String, String>> assign(@PathVariable String subject, @RequestBody AssignmentRequest request) {
		if (request == null || request.teamId() == null || request.rank() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "teamId와 rank가 필요하다");
		}
		Rank rank;
		try {
			rank = Rank.valueOf(request.rank());
		} catch (IllegalArgumentException invalid) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "없는 직급이다: " + request.rank());
		}
		return currentActorProvider.currentActor()
				.flatMap(actor -> service.assign(subject, request.teamId(), rank, actor.userId()))
				.map(outcome -> Map.of("outcome", outcome.name()))
				.onErrorMap(OrgUnitMemberService.InvalidChangeException.class,
						error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()));
	}

	@DeleteMapping("/api/authz/admin/people/{subject}/assignment")
	public Mono<Map<String, String>> unassign(@PathVariable String subject) {
		return currentActorProvider.currentActor()
				.flatMap(actor -> service.assign(subject, null, null, actor.userId()))
				.map(outcome -> Map.of("outcome", outcome.name()));
	}
}
