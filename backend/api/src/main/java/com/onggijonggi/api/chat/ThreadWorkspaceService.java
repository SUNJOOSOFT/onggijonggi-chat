package com.onggijonggi.api.chat;

import com.onggijonggi.api.authz.RbacProperties;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadWorkspaceService.java
 * Description : 03·CORE 방이 어느 워크스페이스에 놓이는지를 정한다. 협업방은 만드는 사람이 고르고(THREAD_CREATE가 있는 곳만),
 *               1:1은 common에 둔다. 사람이 볼 수 있는 워크스페이스 목록과 협업방 목록 거르기도 여기서 한다.
 *               조회는 {@link WorkspaceAuthorizer#canView}, 생성은 THREAD_CREATE 판정에 맡긴다.
 *
 *               워크스페이스 트리는 bootstrap이 만든다. 모든 Thread는 Tenant·워크스페이스에 놓이므로(절체 뒤 NOT NULL) 방을
 *               워크스페이스 없이 만들지 않는다. 설정이 없는 기본 배포도 bootstrap이 Tenant 하나와 그 common을 만든다.
 *
 *               1:1의 common은 판정이 켜져 있으면 요청자의 조직 배정(org_unit_mbr)이 속한 Tenant의 것이다(#299). 배정이
 *               없거나 그 Tenant·팀이 비활성이면 1:1을 만들 수 없다. 판정이 꺼져 있으면 ACTIVE Tenant가 하나일 때 그
 *               common에 두고, 정할 수 없으면(Tenant가 없거나 여럿) 503이다.
 */
@Service
public class ThreadWorkspaceService {

	/** bootstrap이 Tenant마다 자동으로 만드는 common의 예약 node_key. */
	private static final String COMMON_KEY = "common";

	private final RbacProperties rbacProperties;
	private final WorkspaceAuthorizer authorizer;
	private final WorkspaceNodeRepository nodes;
	private final TenantRepository tenants;
	private final AppUserRepository appUsers;
	private final OrgUnitMemberRepository members;
	private final OrgUnitRepository orgUnits;

	public ThreadWorkspaceService(RbacProperties rbacProperties, WorkspaceAuthorizer authorizer,
			WorkspaceNodeRepository nodes, TenantRepository tenants, AppUserRepository appUsers,
			OrgUnitMemberRepository members, OrgUnitRepository orgUnits) {
		this.rbacProperties = rbacProperties;
		this.authorizer = authorizer;
		this.nodes = nodes;
		this.tenants = tenants;
		this.appUsers = appUsers;
		this.members = members;
		this.orgUnits = orgUnits;
	}

	/**
	* GET /api/workspaces 항목. 맨 위는 ROOT이고 이름은 Tenant 이름(고객사 이름)이다. parentId는 목록 안에서 가장 가까운
	* 조상이다 — 부모를 볼 수 없는 노드(예: 전사는 못 보고 전사 공지방만 보는 사람)는 ROOT 바로 아래로 온다. depth는 실제
	* 트리에서 ROOT 아래 몇 단인지(ROOT는 0)다.
	*/
	public record WorkspaceView(UUID id, UUID parentId, String name, WorkspaceNodeKind kind, int depth) {
	}

	/**
	* 협업방을 둘 노드를 고른다. workspaceId가 없으면 판정이 켜져 있을 때 400이다. 꺼져 있으면 유일한 ACTIVE Tenant의 common에 둔다
	* — 모든 Thread는 Tenant·워크스페이스에 놓여야 하므로(절체 뒤 NOT NULL) 워크스페이스 없이 만들지 않는다. common을 정할 수
	* 없으면(Tenant가 없거나 둘 이상) 제약 위반(500) 대신 503이다.
	* 방을 만들려면 그 노드의 THREAD_CREATE가 필요하다(#299) — 보기(VIEWER)만으로는 만들 수 없다.
	* 없는 노드·비활성 노드·ROOT·권한 없는 노드는 모두 403이다 — 어느 노드가 있는지 떠보지 못하게 이유를 나누지 않는다.
	*/
	public Mono<WorkspaceNode> collabPlacement(String subject, UUID workspaceId) {
		if (workspaceId == null) {
			return rbacProperties.isEnforce()
					? Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST))
					: Mono.fromCallable(this::defaultCommonOrUnavailable).subscribeOn(Schedulers.boundedElastic());
		}
		return Mono.fromCallable(() -> nodes.findById(workspaceId).filter(ThreadWorkspaceService::canHoldThreads))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(node -> node.isEmpty()
						? Mono.<WorkspaceNode>error(new ResponseStatusException(HttpStatus.FORBIDDEN))
						: authorizer.canCreateThread(subject, workspaceId).flatMap(allowed -> allowed
								? Mono.just(node.get())
								: Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN))));
	}

	/**
	* 1:1을 둘 common. 호출자의 트랜잭션 안에서 부르므로 블로킹이다.
	*
	* 판정이 켜져 있으면 요청자의 배정 Tenant의 ACTIVE common이고, 못 정하면 403을 던진다(#299) — 배정이 없거나,
	* 그 Tenant·팀이 비활성이거나, common이 없을 때다. 여기서는 Casbin에 묻지 않는다. 모든 ACTIVE 팀은 common VIEWER
	* 부여를 가지며 DB가 그 부여의 삭제를 거부하므로, 배정·Tenant·팀이 ACTIVE면 common을 볼 수 있다. 뒤이은 구독·발화는
	* {@link ThreadMembershipService#canEnterWorkspace}가 실제 판정으로 다시 확인한다.
	* 판정이 꺼져 있으면 유일한 ACTIVE Tenant의 common이다. 정할 수 없으면(Tenant가 없거나 둘 이상) 503을 던진다 — 대화는
	* 워크스페이스 없이 만들 수 없다(절체 뒤 NOT NULL).
	*/
	public WorkspaceNode directPlacementBlocking(UUID userId) {
		AppUser user = appUsers.findById(userId)
				.filter(found -> found.getStatus() == AppUserStatus.ACTIVE)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
		if (!rbacProperties.isEnforce()) {
			return defaultCommonOrUnavailable();
		}
		return members.findBySubject(user.getKeycloakSubj()).stream().findFirst()
				.filter(this::isUsableAssignment)
				.flatMap(assignment -> nodes.findByTenantIdAndKey(assignment.getTenantId(), COMMON_KEY))
				.filter(node -> node.getStatus() == WorkspaceNodeStatus.ACTIVE)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
	}

	/** 배정의 Tenant와 팀이 모두 ACTIVE이고 팀이 그 Tenant에 있는가. */
	private boolean isUsableAssignment(OrgUnitMember assignment) {
		boolean tenantActive = tenants.findById(assignment.getTenantId())
				.filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE).isPresent();
		boolean unitActive = orgUnits.findById(assignment.getOrgUnitId())
				.filter(unit -> unit.getTenantId().equals(assignment.getTenantId()) && unit.getStatus() == OrgUnitStatus.ACTIVE)
				.isPresent();
		return tenantActive && unitActive;
	}

	/** 판정이 꺼진 배포에서 새 Thread를 둘 곳. 유일한 ACTIVE Tenant의 common이 없으면 서버가 준비되지 않은 것이라 503이다. */
	private WorkspaceNode defaultCommonOrUnavailable() {
		List<Tenant> active = tenants.findAll().stream()
				.filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE)
				.toList();
		if (active.size() != 1) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		}
		// 사전 검증(CutoverValidationService)은 같은 노드를 kind=COMMON·부모 ROOT로 찾는다. 예약 key 'common'은 bootstrap만 만든다.
		return nodes.findByTenantIdAndKey(active.get(0).getId(), COMMON_KEY)
				.filter(node -> node.getStatus() == WorkspaceNodeStatus.ACTIVE)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
	}

	/**
	* 이 사람이 볼 수 있는 워크스페이스를 트리 순서(부모 다음 자식, 같은 부모 아래는 이름순, common 먼저)로. 그 앞에
	* 볼 수 있는 노드가 하나라도 있는 Tenant의 ROOT를 Tenant 이름으로 붙인다. ROOT는 규칙이 없어 판정으로는 아무도
	* 못 보므로 판정하지 않는다 — 방을 두는 곳이 아니라 트리의 제목일 뿐이고, 방을 두려 하면 collabPlacement가 막는다.
	*/
	public Mono<List<WorkspaceView>> visibleWorkspaces(String subject) {
		return Mono.fromCallable(() -> nodes.findAll().stream()
						.filter(node -> node.getStatus() == WorkspaceNodeStatus.ACTIVE)
						.toList())
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(active -> {
					Map<UUID, WorkspaceNode> byId = active.stream()
							.collect(Collectors.toMap(WorkspaceNode::getId, Function.identity()));
					List<WorkspaceNode> candidates = active.stream()
							.filter(ThreadWorkspaceService::canHoldThreads)
							.sorted(Comparator.comparing(node -> treeKey(node, byId)))
							.toList();
					return Flux.fromIterable(candidates)
							.concatMap(node -> authorizer.canView(subject, node.getId())
									.filter(Boolean::booleanValue)
									.map(ignored -> node))
							.collectList()
							.flatMap(visible -> Mono.fromCallable(() -> withRoots(visible, byId))
									.subscribeOn(Schedulers.boundedElastic()));
				});
	}

	private List<WorkspaceView> withRoots(List<WorkspaceNode> visible, Map<UUID, WorkspaceNode> byId) {
		List<WorkspaceNode> roots = visible.stream()
				.map(node -> byId.get(node.getPath()[0]))
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		Map<UUID, String> tenantNames = tenants.findAllById(roots.stream().map(WorkspaceNode::getTenantId).toList())
				.stream().collect(Collectors.toMap(Tenant::getId, Tenant::getName));
		Set<UUID> shown = new HashSet<>();
		roots.forEach(root -> shown.add(root.getId()));
		visible.forEach(node -> shown.add(node.getId()));
		List<WorkspaceView> views = new ArrayList<>();
		for (WorkspaceNode root : roots) {
			views.add(new WorkspaceView(root.getId(), null,
					tenantNames.getOrDefault(root.getTenantId(), root.getName()), WorkspaceNodeKind.ROOT, 0));
		}
		for (WorkspaceNode node : visible) {
			views.add(new WorkspaceView(node.getId(), nearestShownAncestor(node, shown), node.getName(), node.getKind(),
					node.getPath().length - 1));
		}
		return views;
	}

	/**
	* 협업방 목록에서 워크스페이스를 볼 수 없는 방을 뺀다. 방마다 묻지 않고 서로 다른 노드마다 한 번만 묻는다.
	* 판정이 꺼져 있으면 그대로 돌려준다.
	*/
	public Mono<List<Thr>> filterVisible(List<Thr> threads, String subject) {
		if (!rbacProperties.isEnforce()) {
			return Mono.just(threads);
		}
		Set<UUID> nodeIds = threads.stream().map(Thr::getWorkspaceNodeId).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		return Flux.fromIterable(nodeIds)
				.concatMap(nodeId -> authorizer.canView(subject, nodeId).filter(Boolean::booleanValue).map(ignored -> nodeId))
				.collect(Collectors.toSet())
				.map(visible -> threads.stream().filter(thr -> visible.contains(thr.getWorkspaceNodeId())).toList());
	}

	/** 목록 응답에 붙일 워크스페이스 이름. 노드가 없는 방은 빠진다. */
	public Mono<Map<UUID, String>> namesOf(List<Thr> threads) {
		Set<UUID> nodeIds = threads.stream().map(Thr::getWorkspaceNodeId).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		if (nodeIds.isEmpty()) {
			return Mono.just(Map.of());
		}
		return Mono.fromCallable(() -> nodes.findAllById(nodeIds).stream()
						.collect(Collectors.toMap(WorkspaceNode::getId, WorkspaceNode::getName)))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** ROOT는 트리의 뿌리일 뿐 방을 두는 곳이 아니다. 비활성 노드에는 새 방을 두지 않는다. */
	private static boolean canHoldThreads(WorkspaceNode node) {
		return node.getStatus() == WorkspaceNodeStatus.ACTIVE && node.getKind() != WorkspaceNodeKind.ROOT;
	}

	/** path를 거꾸로 올라가며 목록에 있는 첫 조상. 볼 수 없는 조상은 건너뛴다. */
	private static UUID nearestShownAncestor(WorkspaceNode node, Set<UUID> shown) {
		UUID[] path = node.getPath();
		for (int i = path.length - 2; i >= 0; i--) {
			if (shown.contains(path[i])) {
				return path[i];
			}
		}
		return null;
	}

	/**
	* 정렬 키: Tenant(ROOT id)로 먼저 묶고, 그 안에서 조상 이름을 이어 붙인 순서(PermissionAdminService와 같은 순서).
	* common은 그 Tenant의 맨 앞에 둔다.
	*/
	private static String treeKey(WorkspaceNode node, Map<UUID, WorkspaceNode> byId) {
		UUID[] path = node.getPath();
		String tenant = path[0].toString();
		if (node.getKind() == WorkspaceNodeKind.COMMON) {
			return tenant;
		}
		return tenant + Arrays.stream(path, 1, path.length).map(byId::get).filter(Objects::nonNull)
				.map(name -> "\u0000" + name.getName()).collect(Collectors.joining());
	}
}
