package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.CurrentActorProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Class Name : AuthorizationAuditController.java
 * Description : 권한 변경 감사 조회(#259). 두 경로는 같은 필터를 쓴다.
 *               - 일반 ADMIN: `/api/workspaces/{id}/authorization-audits` — 그 Workspace의 행만(직접 MANAGE 필요).
 *               - PLATFORM_ADMIN: `/api/platform/rbac/tenants/{id}/authorization-audits` — Tenant 전체(보안 설정이
 *                 `/api/platform/**`를 PLATFORM_ADMIN으로 막는다).
 *               필터 값은 문자열로 받아 AuthorizationAuditQuery가 해석한다 — 틀린 값을 모두 같은 400 봉투로 돌리기 위해서다.
 */
@RestController
public class AuthorizationAuditController {

	private final CurrentActorProvider currentActorProvider;
	private final AuthorizationAuditQueryService queryService;

	public AuthorizationAuditController(CurrentActorProvider currentActorProvider, AuthorizationAuditQueryService queryService) {
		this.currentActorProvider = currentActorProvider;
		this.queryService = queryService;
	}

	@GetMapping("/api/workspaces/{workspaceId}/authorization-audits")
	public Mono<AuthorizationAuditPage> workspaceAudits(@PathVariable UUID workspaceId,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String eventKind, @RequestParam(required = false) String targetKind,
			@RequestParam(required = false) String actorKind, @RequestParam(required = false) String actorUserId,
			@RequestParam(required = false) String requestId, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor) {
		AuthorizationAuditQuery query = AuthorizationAuditQuery.parse(from, to, eventKind, targetKind, actorKind, actorUserId,
				requestId, limit, cursor);
		return currentActorProvider.currentActor()
				.flatMap(actor -> queryService.forWorkspace(actor.subject(), workspaceId, query));
	}

	@GetMapping("/api/platform/rbac/tenants/{tenantId}/authorization-audits")
	public Mono<AuthorizationAuditPage> tenantAudits(@PathVariable UUID tenantId,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String eventKind, @RequestParam(required = false) String targetKind,
			@RequestParam(required = false) String actorKind, @RequestParam(required = false) String actorUserId,
			@RequestParam(required = false) String requestId, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor) {
		AuthorizationAuditQuery query = AuthorizationAuditQuery.parse(from, to, eventKind, targetKind, actorKind, actorUserId,
				requestId, limit, cursor);
		return queryService.forTenant(tenantId, query);
	}
}
