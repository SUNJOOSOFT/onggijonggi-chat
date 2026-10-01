package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.CurrentActorProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : WorkspaceManagementQueryController.java
 * Description : 관리용 최소 조회 경로. 일반 조회는 직접 MANAGE, 플랫폼 경로는 SecurityConfig의 PLATFORM_ADMIN 보호를 사용한다.
 */
@RestController
@Profile("casbin")
public class WorkspaceManagementQueryController {
	private final CurrentActorProvider actors;
	private final WorkspaceManagementQueryService service;
	public WorkspaceManagementQueryController(CurrentActorProvider actors, WorkspaceManagementQueryService service) {
		this.actors = actors; this.service = service;
	}
	@GetMapping("/api/rbac/admin/context")
	public Mono<Map<String, Boolean>> context() {
		return withSubject(subject -> Map.of("workspaceManagement", !service.workspaces(subject).isEmpty()));
	}
	@GetMapping("/api/rbac/workspaces")
	public Mono<List<WorkspaceManagementQueryService.NodeView>> workspaces() { return withSubject(service::workspaces); }
	@GetMapping("/api/rbac/workspaces/{nodeId}/grants")
	public Mono<List<WorkspaceManagementQueryService.GrantView>> grants(@PathVariable UUID nodeId) {
		return withSubject(subject -> service.grants(subject, nodeId));
	}
	@GetMapping("/api/rbac/workspaces/{nodeId}/org-units")
	public Mono<List<WorkspaceManagementQueryService.OrgView>> organizations(@PathVariable UUID nodeId) {
		return withSubject(subject -> service.organizations(subject, nodeId));
	}
	@GetMapping("/api/rbac/workspaces/{nodeId}/threads")
	public Mono<List<WorkspaceManagementQueryService.ThreadView>> threads(@PathVariable UUID nodeId) {
		return withSubject(subject -> service.threads(subject, nodeId));
	}
	@GetMapping("/api/platform/rbac/tenants")
	public Mono<List<WorkspaceManagementQueryService.TenantView>> tenants() { return blocking(service::tenants); }
	@GetMapping("/api/platform/rbac/tenants/{tenantKey}/org-units")
	public Mono<List<WorkspaceManagementQueryService.PlatformOrgView>> platformOrganizations(@PathVariable String tenantKey) {
		return blocking(() -> service.platformOrganizations(tenantKey));
	}
	private <T> Mono<T> withSubject(java.util.function.Function<String, T> operation) {
		return actors.currentActor().flatMap(actor -> blocking(() -> operation.apply(actor.subject())));
	}
	private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
		return Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic());
	}
}
