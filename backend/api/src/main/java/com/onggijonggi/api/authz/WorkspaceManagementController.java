package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.api.common.TraceIdWebFilter;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : WorkspaceManagementController.java
 * Description : Workspace·부여 관리(#260). 일반 ADMIN 경로는 대상 노드의 직접 MANAGE를 스위치와 무관하게 요구한다.
 *               org-unit 관리는 control plane(`/api/platform/**`, PLATFORM_ADMIN)이다. 조직 트리와 Casbin 서버가 있는
 *               casbin 프로필에서만 켜진다. 서비스가 블로킹이라 boundedElastic에서 부른다.
 *               경로를 `/api/rbac/**`로 따로 묶은 것은 채팅 화면이 읽는 `/api/workspaces`(목록·감사 조회)와 관리 쓰기를 가르기
 *               위해서다. 협업방 이동만 방 리소스라 `/api/collab/threads/{id}/workspace`에 둔다.
 */
@RestController
@Profile("casbin")
public class WorkspaceManagementController {
	private final CurrentActorProvider actors;
	private final WorkspaceManagementService service;

	public WorkspaceManagementController(CurrentActorProvider actors, WorkspaceManagementService service) {
		this.actors = actors;
		this.service = service;
	}

	/** key는 받지 않는다 — 서버가 만든다(볼 수 없는 노드의 key를 떠보지 못하게). */
	public record CreateNode(UUID parentId, WorkspaceNodeKind kind, String name) { }
	public record Name(String name) { }
	public record Reparent(UUID parentId) { }
	public record CreateGrant(UUID orgUnitId, WorkspaceRole role) { }
	public record Role(WorkspaceRole role) { }
	public record MoveThread(UUID workspaceId) { }
	public record CreateOrgUnit(String key, String name) { }

	@PostMapping("/api/rbac/workspaces")
	public Mono<ResponseEntity<Map<String, UUID>>> createNode(@RequestBody CreateNode request) {
		return withActor(actor -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",
				service.createNode(actor, request.parentId(), request.kind(), request.name()))));
	}

	@PatchMapping("/api/rbac/workspaces/{nodeId}/name")
	public Mono<ResponseEntity<Void>> renameNode(@PathVariable UUID nodeId, @RequestBody Name request) {
		return withActor(actor -> { service.renameNode(actor, nodeId, request.name()); return ResponseEntity.noContent().build(); });
	}

	@PatchMapping("/api/rbac/workspaces/{nodeId}/parent")
	public Mono<ResponseEntity<Void>> reparent(@PathVariable UUID nodeId, @RequestBody Reparent request) {
		return withActor(actor -> { service.reparentLeaf(actor, nodeId, request.parentId()); return ResponseEntity.noContent().build(); });
	}

	@PostMapping("/api/rbac/workspaces/{nodeId}/deactivate")
	public Mono<ResponseEntity<Void>> deactivate(@PathVariable UUID nodeId) {
		return withActor(actor -> { service.deactivateSubtree(actor, nodeId); return ResponseEntity.noContent().build(); });
	}

	@PostMapping("/api/rbac/workspaces/{nodeId}/reactivate")
	public Mono<ResponseEntity<Void>> reactivate(@PathVariable UUID nodeId) {
		return withActor(actor -> { service.reactivateNode(actor, nodeId); return ResponseEntity.noContent().build(); });
	}

	@PostMapping("/api/rbac/workspaces/{nodeId}/grants")
	public Mono<ResponseEntity<Map<String, UUID>>> addGrant(@PathVariable UUID nodeId, @RequestBody CreateGrant request) {
		return withActor(actor -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",
				service.addGrant(actor, nodeId, request.orgUnitId(), request.role()))));
	}

	@PatchMapping("/api/rbac/grants/{grantId}/role")
	public Mono<ResponseEntity<Void>> changeGrant(@PathVariable UUID grantId, @RequestBody Role request) {
		return withActor(actor -> { service.changeGrantRole(actor, grantId, request.role()); return ResponseEntity.noContent().build(); });
	}

	@DeleteMapping("/api/rbac/grants/{grantId}")
	public Mono<ResponseEntity<Void>> removeGrant(@PathVariable UUID grantId) {
		return withActor(actor -> { service.removeGrant(actor, grantId); return ResponseEntity.noContent().build(); });
	}

	@PatchMapping("/api/collab/threads/{threadId}/workspace")
	public Mono<ResponseEntity<Void>> moveThread(@PathVariable UUID threadId, @RequestBody MoveThread request) {
		return withActor(actor -> { service.moveCollabThread(actor, threadId, request.workspaceId()); return ResponseEntity.noContent().build(); });
	}

	@PostMapping("/api/platform/rbac/tenants/{tenantKey}/org-units")
	public Mono<ResponseEntity<Map<String, UUID>>> createOrgUnit(@PathVariable String tenantKey,
			@RequestBody CreateOrgUnit request) {
		return withActor(actor -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",
				service.createOrgUnit(actor, tenantKey, request.key(), request.name()))));
	}

	@PatchMapping("/api/platform/rbac/tenants/{tenantKey}/org-units/{orgUnitId}/name")
	public Mono<ResponseEntity<Void>> renameOrgUnit(@PathVariable String tenantKey, @PathVariable UUID orgUnitId,
			@RequestBody Name request) {
		return withActor(actor -> { service.changeOrgUnit(actor, tenantKey, orgUnitId, request.name(), null);
			return ResponseEntity.noContent().build(); });
	}

	@PostMapping("/api/platform/rbac/tenants/{tenantKey}/org-units/{orgUnitId}/deactivate")
	public Mono<ResponseEntity<Void>> deactivateOrgUnit(@PathVariable String tenantKey, @PathVariable UUID orgUnitId) {
		return withActor(actor -> { service.changeOrgUnit(actor, tenantKey, orgUnitId, null, OrgUnitStatus.INACTIVE);
			return ResponseEntity.noContent().build(); });
	}

	@PostMapping("/api/platform/rbac/tenants/{tenantKey}/org-units/{orgUnitId}/reactivate")
	public Mono<ResponseEntity<Void>> reactivateOrgUnit(@PathVariable String tenantKey, @PathVariable UUID orgUnitId) {
		return withActor(actor -> { service.changeOrgUnit(actor, tenantKey, orgUnitId, null, OrgUnitStatus.ACTIVE);
			return ResponseEntity.noContent().build(); });
	}

	private <T> Mono<T> withActor(java.util.function.Function<WorkspaceManagementService.Actor, T> operation) {
		// 응답의 X-Trace-Id와 같은 값을 감사 행에 남긴다(TraceIdWebFilter가 Reactor Context에 넣는다).
		Mono<String> traceId = Mono.deferContextual(context -> Mono.just(
				context.getOrDefault(TraceIdWebFilter.TRACE_ID_ATTR, "")));
		return Mono.zip(actors.currentActor(), traceId, ReactiveSecurityContextHolder.getContext()
				.map(context -> context.getAuthentication())
				.filter(JwtAuthenticationToken.class::isInstance).cast(JwtAuthenticationToken.class)
				.switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.FORBIDDEN))))
				.flatMap(tuple -> Mono.fromCallable(() -> {
					List<String> roles = tuple.getT3().getAuthorities().stream().map(authority -> authority.getAuthority())
							.filter(value -> value.startsWith("ROLE_")).map(value -> value.substring(5)).toList();
					return operation.apply(new WorkspaceManagementService.Actor(tuple.getT1().userId(),
							tuple.getT1().subject(), roles, UUID.randomUUID().toString(),
							tuple.getT2().isEmpty() ? null : tuple.getT2()));
				}).subscribeOn(Schedulers.boundedElastic()));
	}
}
