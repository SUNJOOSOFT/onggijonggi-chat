package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.UserIdentityService;
import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Class Name : ThreadWorkspacePostgresTest.java
 * Description : 방과 워크스페이스 연결을 판정이 켜진(app.rbac.enforce=true) 배포처럼 실제 PostgreSQL 위에서 HTTP로 검증한다.
 *               워크스페이스 트리는 bootstrap이 만들고, 판정(WorkspaceAuthorizer)만 테스트가 정한다 — 판정 자체는
 *               WorkspaceAuthorizerTest·CasbinServerContainerTest가 맡는다. 여기서 보는 것은 판정 결과가 방 만들기·목록·
 *               이력·참가자 관리·초대에 빠짐없이 걸리는지다. 테스트마다 고유한 Tenant·subject를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.rbac.enforce=true")
@Import(CollabRoomFixture.class)
class ThreadWorkspacePostgresTest extends PostgresSpringTestBase {

	@LocalServerPort
	private int port;
	@MockitoBean
	private WorkspaceAuthorizer authorizer;
	@Autowired
	private RbacBootstrapService bootstrap;
	@Autowired
	private TenantRepository tenants;
	@Autowired
	private WorkspaceNodeRepository nodes;
	@Autowired
	private ThrRepository threads;
	@Autowired
	private ThrMbrRepository members;
	@Autowired
	private UserIdentityService users;
	@Autowired
	private CollabRoomFixture.CollabRooms rooms;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private RestTestClient client;
	private String tenantKey;
	private String suffix;
	private WorkspaceNode root;
	private WorkspaceNode common;
	private WorkspaceNode hr;
	private WorkspaceNode hrLead;
	/** subject → 볼 수 있는 노드. 판정은 이 표만 본다(common도 표에 넣어야 보인다). */
	private final Map<String, Set<UUID>> visible = new ConcurrentHashMap<>();

	@BeforeEach
	void setUp() throws Exception {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
		suffix = UUID.randomUUID().toString().substring(0, 8);
		tenantKey = "ws-" + suffix;
		Files.writeString(BOOTSTRAP_CONFIG, """
				reconcile:
				  enabled: false
				tenants:
				  - tnn_key: %s
				    name: 워크스페이스 시험
				    status: ACTIVE
				    org_units:
				      - { key: hr, name: 인사팀, status: ACTIVE }
				    nodes:
				      - { node_key: hr, kind: ORG, parent: root, name: 인사팀, status: ACTIVE }
				      - { node_key: hr-lead, kind: WORK, parent: hr, name: 인사팀 관리자방, status: ACTIVE }
				    grants:
				      - { org_unit: hr, role: VIEWER, node: common }
				      - { org_unit: hr, role: ADMIN, node: hr }
				""".formatted(tenantKey));
		bootstrap.runCurrentConfiguration();
		UUID tenantId = tenants.findByKey(tenantKey).orElseThrow().getId();
		root = nodes.findByTenantIdAndKey(tenantId, "root").orElseThrow();
		common = nodes.findByTenantIdAndKey(tenantId, "common").orElseThrow();
		hr = nodes.findByTenantIdAndKey(tenantId, "hr").orElseThrow();
		hrLead = nodes.findByTenantIdAndKey(tenantId, "hr-lead").orElseThrow();
		// 실제 판정처럼 워크스페이스가 없으면(null) 거부한다.
		when(authorizer.canView(any(), any())).thenAnswer(call -> Mono.just(call.getArgument(1) != null
				&& visible.getOrDefault(call.<String>getArgument(0), Set.of()).contains(call.<UUID>getArgument(1))));
	}

	// ------------------------------------------------------------------ 만들기

	@Test
	void createsTheThreadInTheChosenVisibleWorkspace() {
		String owner = see("owner", hr);

		String id = create(owner, "인사팀 방", hr.getId()).expectStatus().isCreated()
				.expectBody(CreateCollabThreadResponse.class).returnResult().getResponseBody().id().toString();

		Thr created = threads.findById(UUID.fromString(id)).orElseThrow();
		assertThat(created.getWorkspaceNodeId()).isEqualTo(hr.getId());
		assertThat(created.getTenantId()).isEqualTo(hr.getTenantId());
	}

	@Test
	void rejectsCreationWithoutAWorkspaceWhenEnforcementIsOn() {
		String owner = see("no-ws", hr);
		long before = threads.count();

		create(owner, "워크스페이스 없는 방", null).expectStatus().isBadRequest();

		assertThat(threads.count()).isEqualTo(before);
	}

	@Test
	void rejectsCreationInAWorkspaceTheCreatorCannotSeeOrInTheRoot() {
		String owner = see("hidden", hr);
		visible.get(owner).add(root.getId());
		long before = threads.count();

		create(owner, "관리자방 방", hrLead.getId()).expectStatus().isForbidden();
		create(owner, "루트 방", root.getId()).expectStatus().isForbidden();
		create(owner, "없는 곳 방", UUID.randomUUID()).expectStatus().isForbidden();

		assertThat(threads.count()).isEqualTo(before);
	}

	/** 같은 Idempotency-Key로 다른 워크스페이스를 고르면 앞 방을 돌려주지 않고 거절한다 — title과 같은 이유다. */
	@Test
	void rejectsTheSameIdempotencyKeyForADifferentWorkspace() {
		String owner = see("idem", hr, common);
		String key = UUID.randomUUID().toString();

		create(owner, "같은 제목", hr.getId(), key).expectStatus().isCreated();
		create(owner, "같은 제목", common.getId(), key).expectStatus().isEqualTo(HttpStatus.CONFLICT)
				.expectBody().jsonPath("$.error.code").isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
	}

	// ------------------------------------------------------------------ 목록·입장

	@Test
	void listShowsOnlyThreadsInVisibleWorkspacesWithTheirNames() {
		String member = see("lister", hr);
		UUID inHr = roomIn(hr, "lister-owner-" + suffix, member);
		UUID inLead = roomIn(hrLead, "lister-owner-" + suffix, member);

		String body = get(member, "/api/collab/threads").expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains(inHr.toString()).contains("\"workspaceName\":\"인사팀\"")
				.contains("\"workspaceId\":\"" + hr.getId() + "\"")
				.doesNotContain(inLead.toString());
	}

	/** 워크스페이스를 못 보게 된 참가자는 참가 기록이 남아 있어도 방을 다루는 모든 경로에서 404다. 권한이 돌아오면 그대로 들어간다. */
	@Test
	void participantWhoLostTheWorkspaceIsTreatedAsANonParticipantUntilAccessReturns() {
		String owner = see("lost-owner", hr);
		String member = see("lost-member", hr);
		UUID room = roomIn(hr, owner, member);
		visible.get(member).remove(hr.getId());

		get(member, "/api/threads/" + room + "/messages").expectStatus().isNotFound();
		get(member, "/api/collab/threads/" + room + "/messages").expectStatus().isNotFound();
		get(member, "/api/collab/threads/" + room + "/participants").expectStatus().isNotFound();
		assertThat(get(member, "/api/collab/threads").expectBody(String.class).returnResult().getResponseBody())
				.doesNotContain(room.toString());
		get(owner, "/api/threads/" + room + "/messages").expectStatus().isOk();

		visible.get(member).add(hr.getId());
		get(member, "/api/threads/" + room + "/messages").expectStatus().isOk();
	}

	/** 워크스페이스가 정해지지 않은 협업방은 판정이 켜지면 들어갈 수 없다(bootstrap이 common으로 옮기기 전의 방). */
	@Test
	void unplacedCollabThreadIsClosedWhenEnforcementIsOn() {
		String owner = see("unplaced", hr, common);
		UUID room = rooms.openRoom(owner);

		get(owner, "/api/threads/" + room + "/messages").expectStatus().isNotFound();
	}

	// ------------------------------------------------------------------ 초대

	@Test
	void rejectsInvitingSomeoneWhoCannotSeeTheWorkspace() {
		String owner = see("inv-owner", hr);
		String outsider = see("inv-outsider", common);
		UUID room = roomIn(hr, owner);

		client.post().uri("/api/collab/threads/{id}/participants", room)
				.header(HttpHeaders.AUTHORIZATION, bearer(owner))
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("subject", outsider))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.CONFLICT)
				.expectBody().jsonPath("$.error.code").isEqualTo("INVITEE_NO_WORKSPACE_ACCESS");
		assertThat(rooms.pendingInvitations(room)).doesNotContain(outsider);
	}

	@Test
	void invitesSomeoneWhoCanSeeTheWorkspace() {
		String owner = see("inv-ok-owner", hr);
		String colleague = see("inv-ok-colleague", hr);
		UUID room = roomIn(hr, owner);

		client.post().uri("/api/collab/threads/{id}/participants", room)
				.header(HttpHeaders.AUTHORIZATION, bearer(owner))
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("subject", colleague))
				.exchange()
				.expectStatus().isNoContent();
	}

	// ------------------------------------------------------------------ 워크스페이스 목록·옮기기

	@Test
	void workspaceListHasOnlyVisibleNodesUnderTheRootNamedAfterTheTenant() {
		String viewer = see("tree", common, hr);

		String body = get(viewer, "/api/workspaces").expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains(common.getId().toString()).contains(hr.getId().toString())
				.doesNotContain(hrLead.getId().toString())
				.contains("{\"id\":\"" + root.getId() + "\",\"parentId\":null,\"name\":\"워크스페이스 시험\",\"kind\":\"ROOT\"");
	}

	/** 다른 Tenant에 이미 정해진 방은 건드리지 않는다. 테스트끼리 DB를 나눠 쓰므로 Tenant 수와 무관한 저장소 쿼리를 직접 본다. */
	@Test
	void placeUnassignedMovesOnlyUnplacedThreadsOfThatTenant() {
		UUID unplaced = rooms.openRoom("move-a-" + suffix);
		UUID otherTenant = saveThread("move-b-" + suffix, new Tenant("other-" + suffix, "다른 곳", TenantStatus.ACTIVE));

		new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> threads.placeUnassigned(common.getTenantId(), common.getId()));

		assertThat(threads.findById(unplaced).orElseThrow().getWorkspaceNodeId()).isEqualTo(common.getId());
		assertThat(threads.findById(unplaced).orElseThrow().getTenantId()).isEqualTo(common.getTenantId());
		assertThat(threads.findById(otherTenant).orElseThrow().getWorkspaceNodeId()).isNull();
	}

	// ------------------------------------------------------------------ 도우미

	/** 테스트 고유 subject를 만들고 그 사람이 볼 노드를 정한다. */
	private String see(String name, WorkspaceNode... workspaces) {
		String subject = name + "-" + suffix;
		Set<UUID> ids = ConcurrentHashMap.newKeySet();
		for (WorkspaceNode node : workspaces) ids.add(node.getId());
		visible.put(subject, ids);
		return subject;
	}

	private UUID roomIn(WorkspaceNode workspace, String owner, String... memberSubjects) {
		UUID ownerId = users.resolveOrProvision(owner).block();
		Thr thread = Thr.collab(ownerId, "시험 방");
		thread.placeIn(workspace.getTenantId(), workspace.getId());
		threads.save(thread);
		members.save(new ThrMbr(thread.getId(), ownerId, ThrMbrRole.OWNER, ownerId));
		for (String member : memberSubjects) {
			UUID memberId = users.resolveOrProvision(member).block();
			members.save(new ThrMbr(thread.getId(), memberId, ThrMbrRole.MEMBER, ownerId));
		}
		return thread.getId();
	}

	/** 다른 Tenant 값만 가진 방. Tenant 행이 있어야 나중에 걸릴 FK와 어긋나지 않는다. */
	private UUID saveThread(String owner, Tenant tenant) {
		tenants.save(tenant);
		UUID ownerId = users.resolveOrProvision(owner).block();
		Thr thread = Thr.collab(ownerId, "다른 Tenant 방");
		thread.placeIn(tenant.getId(), null);
		threads.save(thread);
		return thread.getId();
	}

	private RestTestClient.ResponseSpec create(String subject, String title, UUID workspaceId) {
		return create(subject, title, workspaceId, null);
	}

	private RestTestClient.ResponseSpec create(String subject, String title, UUID workspaceId, String key) {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("title", title);
		if (workspaceId != null) body.put("workspaceId", workspaceId.toString());
		RestTestClient.RequestBodySpec request = client.post().uri("/api/collab/threads")
				.header(HttpHeaders.AUTHORIZATION, bearer(subject))
				.contentType(MediaType.APPLICATION_JSON);
		if (key != null) request = request.header("Idempotency-Key", key);
		return request.body(body).exchange();
	}

	private RestTestClient.ResponseSpec get(String subject, String path) {
		return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer(subject)).exchange();
	}

	private static String bearer(String subject) {
		return "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER"));
	}
}
