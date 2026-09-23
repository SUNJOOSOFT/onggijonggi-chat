package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakPerson;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : PermissionAdminService.java
 * Description : 03·CORE 권한 관리 화면(/admin/permissions)이 보는 한 장. 사람(Keycloak 계정 전체)과 그 배정, 고를 수 있는
 *               팀·직급, workspace 목록, 그리고 "누가 무엇을 보나" 표를 만든다. 표는 실제 판정 API(WorkspaceAuthorizer)를
 *               사람 × workspace마다 불러 만든다 — 화면이 판정을 흉내 내지 않는다. 배정 변경은 배정 서비스를 거친다.
 */
@Service
public class PermissionAdminService {

	/** 화면에 올릴 계정 수 상한. 데모·개발용이라 한 장에 모두 보인다. */
	static final int MAX_PEOPLE = 500;

	private final KeycloakAdminClient keycloak;
	private final OrgUnitRepository orgUnits;
	private final OrgUnitMemberRepository members;
	private final WorkspaceNodeRepository nodes;
	private final WorkspaceAuthorizer authorizer;
	private final OrgUnitMemberService memberService;

	public PermissionAdminService(KeycloakAdminClient keycloak, OrgUnitRepository orgUnits, OrgUnitMemberRepository members,
			WorkspaceNodeRepository nodes, WorkspaceAuthorizer authorizer, OrgUnitMemberService memberService) {
		this.keycloak = keycloak;
		this.orgUnits = orgUnits;
		this.members = members;
		this.nodes = nodes;
		this.authorizer = authorizer;
		this.memberService = memberService;
	}

	public record Team(UUID id, String key, String name) {
	}

	public record RankOption(String code, String label) {
	}

	/** 판정 대상 workspace. depth는 ROOT 아래 몇 단인지(화면 들여쓰기용). */
	public record Workspace(UUID id, String key, String name, int depth) {
	}

	/** teamId·rank가 null이면 미배정. visible은 볼 수 있는 workspace id 목록이다. */
	public record Person(String subject, String username, String name, boolean enabled, UUID teamId, String rank, List<UUID> visible) {
	}

	public record Overview(List<Team> teams, List<RankOption> ranks, List<Workspace> workspaces, List<Person> people) {
	}

	private record Snapshot(List<Team> teams, List<Workspace> workspaces, Map<String, OrgUnitMember> membersBySubject) {
	}

	public Mono<Overview> overview() {
		Mono<Snapshot> snapshot = Mono.fromCallable(this::snapshot).subscribeOn(Schedulers.boundedElastic());
		return Mono.zip(snapshot, keycloak.listPeople(MAX_PEOPLE)).flatMap(tuple -> {
			Snapshot data = tuple.getT1();
			List<KeycloakPerson> people = tuple.getT2().stream()
					.sorted(Comparator.comparing(KeycloakPerson::username)).toList();
			return Flux.fromIterable(people)
					.concatMap(person -> visibleOf(person.subject(), data.workspaces())
							.map(visible -> toPerson(person, data.membersBySubject().get(person.subject()), visible)))
					.collectList()
					.map(list -> new Overview(data.teams(), rankOptions(), data.workspaces(), list));
		});
	}

	/** teamId가 null이면 배정 해제다. 행위자는 화면을 쓰는 사람이다. */
	public Mono<OrgUnitMemberService.Outcome> assign(String subject, UUID teamId, Rank rank, UUID actorUserId) {
		OrgUnitMemberService.Change change = teamId == null ? OrgUnitMemberService.Change.unassign(subject)
				: OrgUnitMemberService.Change.assign(subject, teamId, rank);
		return Mono.fromCallable(() -> memberService.apply(change, OrgUnitMemberService.Actor.user(actorUserId, "admin-screen")).outcome())
				.subscribeOn(Schedulers.boundedElastic());
	}

	private Snapshot snapshot() {
		List<Team> teams = orgUnits.findAll().stream()
				.filter(unit -> unit.getStatus() == OrgUnitStatus.ACTIVE)
				.sorted(Comparator.comparing(OrgUnit::getKey))
				.map(unit -> new Team(unit.getId(), unit.getKey(), unit.getName()))
				.toList();
		// 트리 순서(부모 다음에 자식)로 늘어놓는다. COMMON은 누구나 봐서 표에서 뺀다.
		List<WorkspaceNode> active = nodes.findAll().stream()
				.filter(node -> node.getStatus() == WorkspaceNodeStatus.ACTIVE)
				.filter(node -> node.getKind() != WorkspaceNodeKind.ROOT && node.getKind() != WorkspaceNodeKind.COMMON)
				.toList();
		Map<UUID, WorkspaceNode> byId = active.stream().collect(Collectors.toMap(WorkspaceNode::getId, Function.identity()));
		List<Workspace> workspaces = active.stream()
				.sorted(Comparator.comparing(node -> treeKey(node, byId)))
				.map(node -> new Workspace(node.getId(), node.getKey(), node.getName(), node.getPath().length - 1))
				.toList();
		Map<String, OrgUnitMember> membersBySubject = new HashMap<>();
		for (OrgUnitMember member : members.findAll()) membersBySubject.put(member.getSubject(), member);
		return new Snapshot(teams, workspaces, membersBySubject);
	}

	/** 조상 이름을 이어 붙인 정렬 키. 같은 부모 아래는 이름순이다. */
	private static String treeKey(WorkspaceNode node, Map<UUID, WorkspaceNode> byId) {
		return Arrays.stream(node.getPath()).map(byId::get).filter(ancestor -> ancestor != null)
				.map(WorkspaceNode::getName).collect(Collectors.joining("\u0000"));
	}

	private Mono<List<UUID>> visibleOf(String subject, List<Workspace> workspaces) {
		return Flux.fromIterable(workspaces)
				.concatMap(workspace -> authorizer.canView(subject, workspace.id()).filter(Boolean::booleanValue).map(ignored -> workspace.id()))
				.collectList();
	}

	private static Person toPerson(KeycloakPerson person, OrgUnitMember member, List<UUID> visible) {
		return new Person(person.subject(), person.username(), person.name(), person.enabled(),
				member == null ? null : member.getOrgUnitId(), member == null ? null : member.getRank().name(), visible);
	}

	private static List<RankOption> rankOptions() {
		return Arrays.stream(Rank.values()).map(rank -> new RankOption(rank.name(), rank.label())).toList();
	}
}
