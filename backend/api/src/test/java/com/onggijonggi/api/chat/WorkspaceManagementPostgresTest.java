package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.RbacPolicyRefresh;
import com.onggijonggi.api.authz.WorkspaceManagementService;
import com.onggijonggi.api.authz.WorkspaceManagementService.Actor;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceRole;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : WorkspaceManagementPostgresTest.java
 * Description : Workspace·부여 관리(#260)를 실제 PostgreSQL의 trigger 위에서 검증한다. 판정 스위치는 기본값(꺼짐)이다 —
 *               관리 쓰기의 직접 MANAGE는 스위치와 무관하게 엄격해야 한다. 확인하는 것:
 *               - reparent: DB guard가 요구하는 "이동 순간 부여 0건"을 같은 트랜잭션의 삭제·복원으로 만족하고, 부여 구성을
 *                 그대로 되살리며, 감사는 NODE_REPARENTED 한 건뿐이다. 권한이 없으면 원래 부모·부여·감사가 그대로다.
 *               - 하위 트리 비활성화: 하위 노드 하나라도 직접 MANAGE가 없거나 협업방이 남으면 전체를 거부하고 아무것도 남기지 않는다.
 *               - 재활성화는 대상 노드만, 비활성 자식은 그대로다. 마지막 직접 ADMIN은 지울 수 없다. 선언 노드는 못 바꾼다.
 *               - org-unit: 만들 때 COMMON VIEWER 부여를 함께 만들고, ACTIVE 사용자 배정이 남으면 비활성화를 거부한다.
 *               Casbin 반영(RbacPolicyRefresh)은 mock으로 두고 커밋된 변경에만 불리는지 본다. 트리는 bootstrap이 만든다.
 */
class WorkspaceManagementPostgresTest extends PostgresSpringTestBase {

	@Autowired
	private WorkspaceManagementService service;
	@Autowired
	private RbacBootstrapService bootstrap;
	@Autowired
	private TenantRepository tenants;
	@Autowired
	private WorkspaceNodeRepository nodes;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private FakeMemberAttributesConfig.FakeMemberAttributes members;
	@MockitoBean
	private RbacPolicyRefresh refresh;

	private String tag;
	private String tenantKey;
	private UUID tenantId;
	private UUID hr;
	private UUID ops;
	private UUID hrTeam;
	private UUID opsTeam;
	private Actor hrAdmin;
	private Actor opsAdmin;

	@BeforeEach
	void setUp() throws Exception {
		tag = "m" + UUID.randomUUID().toString().substring(0, 8);
		tenantKey = "manage-" + tag;
		Files.writeString(BOOTSTRAP_CONFIG, """
				reconcile:
				  enabled: false
				tenants:
				  - tnn_key: %s
				    name: 관리 시험
				    status: ACTIVE
				    org_units:
				      - { key: hr, name: 인사팀, status: ACTIVE }
				      - { key: ops, name: 운영팀, status: ACTIVE }
				    nodes:
				      - { node_key: hr, kind: ORG, parent: root, name: 인사팀, status: ACTIVE }
				      - { node_key: ops, kind: ORG, parent: root, name: 운영팀, status: ACTIVE }
				    grants:
				      - { org_unit: hr, role: VIEWER, node: common }
				      - { org_unit: ops, role: VIEWER, node: common }
				      - { org_unit: hr, role: ADMIN, node: hr }
				      - { org_unit: ops, role: ADMIN, node: ops }
				""".formatted(tenantKey));
		bootstrap.runCurrentConfiguration();
		tenantId = tenants.findByKey(tenantKey).orElseThrow().getId();
		hr = nodes.findByTenantIdAndKey(tenantId, "hr").orElseThrow().getId();
		ops = nodes.findByTenantIdAndKey(tenantId, "ops").orElseThrow().getId();
		hrTeam = team("hr");
		opsTeam = team("ops");
		hrAdmin = actor("hr-admin", hrTeam);
		opsAdmin = actor("ops-admin", opsTeam);
	}

	// ------------------------------------------------------------------ 생성·이름

	@Test
	void createsAChildWithTheCreatorsAdminGrantAndAuditsBoth() {
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "프로젝트");

		assertThat(grants(project)).containsExactly(hrTeam + ":ADMIN");
		// 한 트랜잭션의 두 행은 시각이 같아 순서를 보지 않는다.
		assertThat(events()).containsExactlyInAnyOrder("NODE_CREATED", "POLICY_ADDED");
		assertThat(jdbc.queryForObject("select count(*) from authz_adt where tnn_id = ? and act_kind = 'USER' and act_user_id = ?",
				Integer.class, tenantId, hrAdmin.userId())).isEqualTo(2);
		verify(refresh).publish(eq(tenantId), eq(Set.of(project)), any());
	}

	@Test
	void declaredNodesCannotBeChangedAndOutsidersCannotCreate() {
		assertStatus(HttpStatus.CONFLICT, () -> service.renameNode(hrAdmin, hr, "새 이름"));
		assertStatus(HttpStatus.FORBIDDEN, () -> service.createNode(opsAdmin, hr, WorkspaceNodeKind.WORK, "남의 방"));
		assertThat(events()).isEmpty();
	}

	// ------------------------------------------------------------------ reparent

	@Test
	void reparentRestoresTheSameGrantsAndAuditsOnlyTheMove() {
		UUID leaf = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "옮길 방");
		UUID target = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "새 부모");
		service.addGrant(hrAdmin, leaf, opsTeam, WorkspaceRole.VIEWER);
		List<String> before = grants(leaf);
		List<String> createdBefore = grantCreatedAt(leaf);
		int auditsBefore = events().size();

		service.reparentLeaf(hrAdmin, leaf, target);

		// 지웠다 되살린 부여도 원래 생성 시각을 지킨다.
		assertThat(grantCreatedAt(leaf)).containsExactlyElementsOf(createdBefore);

		assertThat(parentOf(leaf)).isEqualTo(target);
		assertThat(jdbc.queryForObject("select path[array_length(path, 1) - 1] from wrk_node where id = ?", UUID.class, leaf))
				.isEqualTo(target);
		assertThat(grants(leaf)).containsExactlyInAnyOrderElementsOf(before).hasSize(2);
		assertThat(events().subList(auditsBefore, events().size())).containsExactly("NODE_REPARENTED");
	}

	@Test
	void reparentWithoutManageOnTheNewParentChangesNothing() {
		UUID leaf = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "옮길 방");
		List<String> before = grants(leaf);
		int auditsBefore = events().size();

		assertStatus(HttpStatus.FORBIDDEN, () -> service.reparentLeaf(hrAdmin, leaf, ops));

		assertThat(parentOf(leaf)).isEqualTo(hr);
		assertThat(grants(leaf)).containsExactlyElementsOf(before);
		assertThat(events()).hasSize(auditsBefore);
	}

	// ------------------------------------------------------------------ 하위 트리 비활성화·재활성화

	@Test
	void subtreeDeactivationIsAllOrNothingOnManage() {
		UUID parent = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "상위");
		UUID child = service.createNode(hrAdmin, parent, WorkspaceNodeKind.WORK, "하위");
		// 하위 노드의 관리를 운영팀에 넘기고 인사팀 ADMIN을 뺀다 — 인사팀은 이제 하위에 직접 MANAGE가 없다.
		service.addGrant(hrAdmin, child, opsTeam, WorkspaceRole.ADMIN);
		service.removeGrant(hrAdmin, grantId(child, hrTeam));
		int auditsBefore = events().size();

		assertStatus(HttpStatus.FORBIDDEN, () -> service.deactivateSubtree(hrAdmin, parent));

		assertThat(status(parent)).isEqualTo("ACTIVE");
		assertThat(status(child)).isEqualTo("ACTIVE");
		assertThat(events()).hasSize(auditsBefore);
	}

	@Test
	void aRemainingCollabRoomBlocksDeactivationUntilItIsMoved() {
		UUID parent = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "상위");
		UUID child = service.createNode(hrAdmin, parent, WorkspaceNodeKind.WORK, "하위");
		UUID room = collabRoom(child);

		assertStatus(HttpStatus.CONFLICT, () -> service.deactivateSubtree(hrAdmin, parent));

		service.moveCollabThread(hrAdmin, room, hr);
		assertThat(jdbc.queryForObject("select wrk_node_id from thr where id = ?", UUID.class, room)).isEqualTo(hr);
		assertThat(jdbc.queryForObject("select count(*) from authz_adt where tnn_id = ? and evt_kind = 'THREAD_MOVED'",
				Integer.class, tenantId)).isEqualTo(2);

		service.deactivateSubtree(hrAdmin, parent);
		assertThat(status(parent)).isEqualTo("INACTIVE");
		assertThat(status(child)).isEqualTo("INACTIVE");
		// 하위부터 끈 두 노드의 감사는 한 요청 ID로 묶인다.
		assertThat(jdbc.queryForObject("""
				select count(distinct req_id) from authz_adt where tnn_id = ? and evt_kind = 'NODE_DEACTIVATED'
				""", Integer.class, tenantId)).isEqualTo(1);

		service.reactivateNode(hrAdmin, parent);
		assertThat(status(parent)).isEqualTo("ACTIVE");
		assertThat(status(child)).as("비활성 자식은 자동으로 되살리지 않는다").isEqualTo("INACTIVE");
	}

	@Test
	void rootAndCommonAreNotManagedAndRootCannotBeAReparentEnd() {
		UUID root = nodes.findByTenantIdAndKey(tenantId, "root").orElseThrow().getId();
		UUID common = nodes.findByTenantIdAndKey(tenantId, "common").orElseThrow().getId();
		// ROOT·COMMON의 상태는 Tenant lifecycle만 따른다. 권한부터 보므로 ADMIN 부여가 없는 두 노드는 403이다.
		assertStatus(HttpStatus.FORBIDDEN, () -> service.deactivateSubtree(hrAdmin, root));
		assertStatus(HttpStatus.FORBIDDEN, () -> service.deactivateSubtree(hrAdmin, common));
		assertStatus(HttpStatus.FORBIDDEN, () -> service.reactivateNode(hrAdmin, common));
		assertThat(status(root)).isEqualTo("ACTIVE");
		assertThat(status(common)).isEqualTo("ACTIVE");
		// ROOT에는 직접 부여가 없어 ROOT가 어느 쪽 부모든 일반 ADMIN은 옮길 수 없다(권한부터 보므로 403). hr은 ROOT 바로 아래다.
		UUID leaf = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "옮길 방");
		assertStatus(HttpStatus.FORBIDDEN, () -> service.reparentLeaf(hrAdmin, leaf, root));
		assertStatus(HttpStatus.FORBIDDEN, () -> service.reparentLeaf(hrAdmin, hr, ops));
		assertThat(parentOf(leaf)).isEqualTo(hr);
		assertThat(parentOf(hr)).isEqualTo(root);
	}

	// ------------------------------------------------------------------ 부여

	@Test
	void theLastDirectAdminCannotBeRemovedOrDemoted() {
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "프로젝트");
		UUID onlyAdmin = grantId(project, hrTeam);

		assertStatus(HttpStatus.CONFLICT, () -> service.removeGrant(hrAdmin, onlyAdmin));
		assertStatus(HttpStatus.CONFLICT, () -> service.changeGrantRole(hrAdmin, onlyAdmin, WorkspaceRole.VIEWER));

		assertThat(grants(project)).containsExactly(hrTeam + ":ADMIN");
	}

	@Test
	void protectedGrantsAndInvalidTargetsAreRefusedWithoutChanges() {
		UUID root = nodes.findByTenantIdAndKey(tenantId, "root").orElseThrow().getId();
		int auditsBefore = events().size();
		// 선언된 부여(hr 팀의 hr ADMIN)는 지우지도 바꾸지도 못한다.
		UUID declared = grantId(hr, hrTeam);
		assertStatus(HttpStatus.CONFLICT, () -> service.removeGrant(hrAdmin, declared));
		assertStatus(HttpStatus.CONFLICT, () -> service.changeGrantRole(hrAdmin, declared, WorkspaceRole.VIEWER));
		// ROOT에는 부여할 권한 자체가 없고, 필수 입력이 비면 400이다.
		assertStatus(HttpStatus.FORBIDDEN, () -> service.addGrant(hrAdmin, root, opsTeam, WorkspaceRole.VIEWER));
		assertStatus(HttpStatus.BAD_REQUEST, () -> service.addGrant(hrAdmin, hr, null, WorkspaceRole.VIEWER));
		assertStatus(HttpStatus.BAD_REQUEST, () -> service.createNode(hrAdmin, null, WorkspaceNodeKind.WORK, "이름"));
		// 비활성 org-unit에는 부여할 수 없다.
		UUID closedUnit = service.createOrgUnit(hrAdmin, tenantKey, "closed-" + tag, "닫힌 팀");
		service.changeOrgUnit(hrAdmin, tenantKey, closedUnit, null, OrgUnitStatus.INACTIVE);
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "프로젝트");
		assertStatus(HttpStatus.CONFLICT, () -> service.addGrant(hrAdmin, project, closedUnit, WorkspaceRole.VIEWER));
		// 같은 key의 org-unit은 다시 만들 수 없다.
		assertStatus(HttpStatus.CONFLICT, () -> service.createOrgUnit(hrAdmin, tenantKey, "closed-" + tag, "또 닫힌 팀"));
		assertThat(grants(project)).containsExactly(hrTeam + ":ADMIN");
		// 거부된 연산은 감사를 남기지 않는다(org-unit 생성·비활성화·노드 생성의 성공분만 남는다).
		assertThat(events().subList(auditsBefore, events().size()))
				.containsExactlyInAnyOrder("ORG_UNIT_CREATED", "POLICY_ADDED", "ORG_UNIT_DEACTIVATED", "NODE_CREATED", "POLICY_ADDED");
	}

	@Test
	void subtreeDeactivationPublishesExactlyTheDeactivatedNodes() {
		UUID parent = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "상위");
		UUID child = service.createNode(hrAdmin, parent, WorkspaceNodeKind.WORK, "하위");
		UUID grandchild = service.createNode(hrAdmin, child, WorkspaceNodeKind.WORK, "손자");
		UUID sibling = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "형제");
		clearInvocations(refresh);

		service.deactivateSubtree(hrAdmin, parent);

		// 구독 해제 범위는 비활성화된 하위 트리 전체이고, 옆 노드는 들어가지 않는다.
		verify(refresh).publish(eq(tenantId), eq(Set.of(parent, child, grandchild)), any());
		assertThat(status(sibling)).isEqualTo("ACTIVE");
	}

	@Test
	void reparentNeedsManageOnTheLeafItself() {
		UUID leaf = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "옮길 방");
		UUID target = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "새 부모");
		// leaf의 관리를 운영팀에 넘긴다 — 인사팀은 두 부모에는 MANAGE가 있지만 leaf에는 없다.
		service.addGrant(hrAdmin, leaf, opsTeam, WorkspaceRole.ADMIN);
		service.removeGrant(hrAdmin, grantId(leaf, hrTeam));
		List<String> before = grants(leaf);
		int auditsBefore = events().size();

		assertStatus(HttpStatus.FORBIDDEN, () -> service.reparentLeaf(hrAdmin, leaf, target));

		assertThat(parentOf(leaf)).isEqualTo(hr);
		assertThat(grants(leaf)).containsExactlyElementsOf(before);
		assertThat(events()).hasSize(auditsBefore);
	}

	@Test
	void aNodeComesBackOnlyUnderAnActiveParent() {
		UUID parent = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "상위");
		UUID child = service.createNode(hrAdmin, parent, WorkspaceNodeKind.WORK, "하위");
		service.deactivateSubtree(hrAdmin, parent);

		assertStatus(HttpStatus.CONFLICT, () -> service.reactivateNode(hrAdmin, child));
		assertThat(status(child)).isEqualTo("INACTIVE");

		service.reactivateNode(hrAdmin, parent);
		service.reactivateNode(hrAdmin, child);
		assertThat(status(child)).isEqualTo("ACTIVE");
	}

	@Test
	void outsidersGetTheSameForbiddenWhateverTheNodesState() {
		UUID root = nodes.findByTenantIdAndKey(tenantId, "root").orElseThrow().getId();
		UUID closed = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "닫힌 방");
		service.deactivateSubtree(hrAdmin, closed);
		UUID withRoom = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "방이 남은 곳");
		collabRoom(withRoom);

		// 권한 없는 사람은 노드가 비활성이든, 방이 남았든, ROOT든 같은 403만 받는다 — 상태를 알 수 없다.
		for (UUID node : List.of(closed, withRoom, root)) {
			assertThatThrownBy(() -> service.deactivateSubtree(opsAdmin, node))
					.isInstanceOfSatisfying(ResponseStatusException.class, error -> {
						assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
						assertThat(error.getReason()).isNull();
					});
		}
	}

	@Test
	void anOrgUnitThatIsTheLastAdminOfANodeCannotBeDeactivated() {
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "프로젝트");
		UUID unit = service.createOrgUnit(hrAdmin, tenantKey, "owner-" + tag, "새 관리팀");
		service.addGrant(hrAdmin, project, unit, WorkspaceRole.ADMIN);
		service.removeGrant(hrAdmin, grantId(project, hrTeam));

		// 이 팀이 프로젝트의 유일한 ACTIVE ADMIN이다 — 비활성화하면 아무도 관리하지 못한다.
		assertStatus(HttpStatus.CONFLICT, () -> service.changeOrgUnit(hrAdmin, tenantKey, unit, null, OrgUnitStatus.INACTIVE));
		assertThat(jdbc.queryForObject("select status from org_unit where id = ?", String.class, unit)).isEqualTo("ACTIVE");

		// 다른 ACTIVE 팀에 ADMIN을 준 뒤에는 비활성화할 수 있다. 그 부여는 새 관리팀 ADMIN이 준다.
		Actor newOwner = actor("new-owner", unit);
		service.addGrant(newOwner, project, opsTeam, WorkspaceRole.ADMIN);
		members.unassign(newOwner.subject());
		service.changeOrgUnit(hrAdmin, tenantKey, unit, null, OrgUnitStatus.INACTIVE);
		assertThat(jdbc.queryForObject("select status from org_unit where id = ?", String.class, unit)).isEqualTo("INACTIVE");
	}

	@Test
	void nodeKeysAreGeneratedByTheServer() {
		UUID first = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "첫째");
		UUID second = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "둘째");

		String firstKey = jdbc.queryForObject("select node_key from wrk_node where id = ?", String.class, first);
		String secondKey = jdbc.queryForObject("select node_key from wrk_node where id = ?", String.class, second);
		assertThat(firstKey).startsWith("n-").matches("[a-z][a-z0-9-]{0,62}");
		assertThat(secondKey).startsWith("n-").isNotEqualTo(firstKey);
	}

	@Test
	void anAdminGrantOfAnInactiveTeamDoesNotCountAsTheRemainingAdmin() {
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "프로젝트");
		UUID unit = service.createOrgUnit(hrAdmin, tenantKey, "old-" + tag, "곧 닫을 팀");
		service.addGrant(hrAdmin, project, unit, WorkspaceRole.ADMIN);
		service.changeOrgUnit(hrAdmin, tenantKey, unit, null, OrgUnitStatus.INACTIVE);

		// 남은 ADMIN이 비활성 팀뿐이면 실제로 관리할 사람이 없으므로 인사팀 ADMIN을 뺄 수 없다.
		assertStatus(HttpStatus.CONFLICT, () -> service.removeGrant(hrAdmin, grantId(project, hrTeam)));
		assertThat(grants(project)).contains(hrTeam + ":ADMIN");
	}

	@Test
	void inputsThatTheDatabaseWouldRejectAreRefusedBeforeReachingIt() {
		// 아래는 모두 DB 제약(깊이 11, 부여 유일, 자기 순환, 이름 NOT NULL)이 거부해 500이 되던 경우다 — 먼저 409·400으로 막는다.
		UUID deepest = hr;
		for (int depth = 3; depth <= 11; depth++) {
			deepest = service.createNode(hrAdmin, deepest, WorkspaceNodeKind.WORK, "깊이 " + depth);
		}
		UUID leafAtLimit = deepest;
		assertStatus(HttpStatus.CONFLICT, () -> service.createNode(hrAdmin, leafAtLimit, WorkspaceNodeKind.WORK, "너무 깊음"));
		UUID mover = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "옮길 방");
		assertStatus(HttpStatus.CONFLICT, () -> service.reparentLeaf(hrAdmin, mover, leafAtLimit));
		assertStatus(HttpStatus.CONFLICT, () -> service.reparentLeaf(hrAdmin, mover, mover));

		UUID viewer = service.addGrant(hrAdmin, mover, opsTeam, WorkspaceRole.VIEWER);
		assertStatus(HttpStatus.CONFLICT, () -> service.addGrant(hrAdmin, mover, opsTeam, WorkspaceRole.VIEWER));
		service.addGrant(hrAdmin, mover, opsTeam, WorkspaceRole.CONTRIBUTOR);
		assertStatus(HttpStatus.CONFLICT, () -> service.changeGrantRole(hrAdmin, viewer, WorkspaceRole.CONTRIBUTOR));

		UUID unit = service.createOrgUnit(hrAdmin, tenantKey, "rename-" + tag, "이름 바꿀 팀");
		assertStatus(HttpStatus.BAD_REQUEST, () -> service.changeOrgUnit(hrAdmin, tenantKey, unit, null, null));
		assertThat(parentOf(mover)).isEqualTo(hr);
	}

	@Test
	void reparentIsRefusedWhenAGrantCannotBeRestored() {
		UUID leaf = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "옮길 방");
		UUID target = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "새 부모");
		UUID unit = service.createOrgUnit(hrAdmin, tenantKey, "gone-" + tag, "곧 닫을 팀");
		service.addGrant(hrAdmin, leaf, unit, WorkspaceRole.VIEWER);
		service.changeOrgUnit(hrAdmin, tenantKey, unit, null, OrgUnitStatus.INACTIVE);
		List<String> before = grants(leaf);

		// 비활성 org-unit의 부여는 DB가 다시 넣지 못하므로 이동 전에 거부하고 원래 부모·부여를 지킨다.
		assertStatus(HttpStatus.CONFLICT, () -> service.reparentLeaf(hrAdmin, leaf, target));

		assertThat(parentOf(leaf)).isEqualTo(hr);
		assertThat(grants(leaf)).containsExactlyElementsOf(before);
	}

	@Test
	void onlyCollabRoomsMoveAndOnlyToManagedNodes() {
		UUID direct = UUID.randomUUID();
		UUID common = nodes.findByTenantIdAndKey(tenantId, "common").orElseThrow().getId();
		jdbc.update("""
				insert into thr (id, kind, created_user_id, drc_own_user_id, title, tnn_id, wrk_node_id)
				values (?, 'DIRECT', ?, ?, 't', ?, ?)""", direct, hrAdmin.userId(), hrAdmin.userId(), tenantId, common);
		UUID room = collabRoom(hr);

		assertStatus(HttpStatus.NOT_FOUND, () -> service.moveCollabThread(hrAdmin, direct, hr));
		assertStatus(HttpStatus.FORBIDDEN, () -> service.moveCollabThread(hrAdmin, room, ops));
		assertStatus(HttpStatus.BAD_REQUEST, () -> service.moveCollabThread(hrAdmin, room, null));
		assertThat(jdbc.queryForObject("select wrk_node_id from thr where id = ?", UUID.class, room)).isEqualTo(hr);
	}

	// ------------------------------------------------------------------ org-unit(control plane)

	@Test
	void anOrgUnitGetsItsCommonViewerGrantAndCannotCloseWithActiveMembers() {
		UUID unit = service.createOrgUnit(hrAdmin, tenantKey, "tmp-" + tag, "임시팀");
		UUID common = nodes.findByTenantIdAndKey(tenantId, "common").orElseThrow().getId();
		assertThat(grants(common)).contains(unit + ":VIEWER");

		Actor member = actor("tmp-member", unit);
		assertStatus(HttpStatus.CONFLICT,
				() -> service.changeOrgUnit(hrAdmin, tenantKey, unit, null, OrgUnitStatus.INACTIVE));

		members.unassign(member.subject());
		service.changeOrgUnit(hrAdmin, tenantKey, unit, null, OrgUnitStatus.INACTIVE);
		assertThat(jdbc.queryForObject("select status from org_unit where id = ?", String.class, unit)).isEqualTo("INACTIVE");
	}

	@Test
	void aRejectedChangeIsNeverPublished() {
		assertStatus(HttpStatus.FORBIDDEN, () -> service.createNode(opsAdmin, hr, WorkspaceNodeKind.WORK, "남의 방"));

		verify(refresh, never()).publish(any(), any(), any());
	}

	// ------------------------------------------------------------------ 도우미

	@Test
	void rankRulesSupportAllOrganizationsChangesAndIndividualAuditEvents() {
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "Rank project");
		UUID rule = service.addRankGrant(hrAdmin, project, null, Rank.K, WorkspaceRole.VIEWER);
		assertThat(service.listRankGrants(hrAdmin, project)).containsExactly(
				new WorkspaceManagementService.RankGrantView(rule, null, Rank.K, WorkspaceRole.VIEWER, false));
		assertStatus(HttpStatus.CONFLICT,
				() -> service.addRankGrant(hrAdmin, project, null, Rank.K, WorkspaceRole.VIEWER));

		service.changeRankGrantRole(hrAdmin, rule, WorkspaceRole.CONTRIBUTOR);
		service.changeRankGrantRank(hrAdmin, rule, Rank.B);
		assertThat(jdbc.queryForMap("select rank, role from rank_grn where id = ?", rule))
				.containsEntry("rank", "B").containsEntry("role", "CONTRIBUTOR");
		assertThat(jdbc.queryForList("""
				select evt_kind from authz_adt where tnn_id = ? and trg_ref ->> 'rank_grn_id' = ?
				order by created_at, id
				""", String.class, tenantId, rule.toString()))
				.containsExactly("POLICY_ADDED", "POLICY_REPLACED", "POLICY_REPLACED");

		service.removeRankGrant(hrAdmin, rule);
		assertThat(service.listRankGrants(hrAdmin, project)).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from rank_grn where id = ?", Integer.class, rule)).isZero();
	}

	@Test
	void rankRuleDuplicateChangesAreRejectedAndListUsesRankOrder() {
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "Rank order project");
		UUID lower = service.addRankGrant(hrAdmin, project, hrTeam, Rank.K, WorkspaceRole.VIEWER);
		UUID higher = service.addRankGrant(hrAdmin, project, hrTeam, Rank.B, WorkspaceRole.VIEWER);
		assertThat(service.listRankGrants(hrAdmin, project)).extracting(WorkspaceManagementService.RankGrantView::id)
				.containsExactly(higher, lower);
		assertStatus(HttpStatus.CONFLICT, () -> service.changeRankGrantRank(hrAdmin, lower, Rank.B));
		assertThat(jdbc.queryForObject("select rank from rank_grn where id = ?", String.class, lower)).isEqualTo("K");
		assertStatus(HttpStatus.BAD_REQUEST,
				() -> service.addRankGrant(hrAdmin, project, hrTeam, null, WorkspaceRole.VIEWER));
		assertStatus(HttpStatus.CONFLICT,
				() -> service.addRankGrant(hrAdmin, project, UUID.randomUUID(), Rank.K, WorkspaceRole.VIEWER));
	}

	@Test
	void rankRuleCreationRollsBackWhenAuditInsertFails() {
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "Rank audit rollback");
		Actor invalidAuditActor = new Actor(hrAdmin.userId(), hrAdmin.subject(), hrAdmin.roles(), "r".repeat(256));
		int existingAudits = jdbc.queryForObject(
				"select count(*) from authz_adt where wrk_node_id = ? and evt_kind = 'POLICY_ADDED'",
				Integer.class, project);
		clearInvocations(refresh);

		assertThatThrownBy(() -> service.addRankGrant(invalidAuditActor, project, null, Rank.K, WorkspaceRole.VIEWER))
				.satisfies(error -> {
					Throwable cause = error;
					while (cause.getCause() != null) cause = cause.getCause();
					assertThat(cause).isInstanceOf(SQLException.class);
					assertThat(((SQLException) cause).getSQLState()).isEqualTo("22001");
				});
		assertThat(jdbc.queryForObject("select count(*) from rank_grn where wrk_node_id = ?", Integer.class, project))
				.isZero();
		assertThat(jdbc.queryForObject("select count(*) from authz_adt where wrk_node_id = ? and evt_kind = 'POLICY_ADDED'",
				Integer.class, project)).isEqualTo(existingAudits);
		verify(refresh, never()).publish(any(), any(), any());
	}

	@Test
	void leafMovementKeepsTheSameRankRule() {
		UUID leaf = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "Rank leaf");
		UUID target = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "Rank parent");
		UUID rule = service.addRankGrant(hrAdmin, leaf, hrTeam, Rank.K, WorkspaceRole.VIEWER);

		service.reparentLeaf(hrAdmin, leaf, target);
		assertThat(jdbc.queryForObject("select wrk_node_id from rank_grn where id = ?", UUID.class, rule))
				.isEqualTo(leaf);
	}

	@Test
	void rankDerivedManageCanRemoveItsOwnPermissionButCannotManageAgain() {
		UUID project = service.createNode(hrAdmin, hr, WorkspaceNodeKind.WORK, "Rank owned project");
		UUID rule = service.addRankGrant(hrAdmin, project, opsTeam, Rank.S, WorkspaceRole.ADMIN);
		assertThat(service.listRankGrants(opsAdmin, project)).hasSize(1);

		service.removeRankGrant(opsAdmin, rule);
		assertStatus(HttpStatus.FORBIDDEN, () -> service.listRankGrants(opsAdmin, project));
	}

	@Test
	void declaredRankRuleCannotBeChangedUntilItsDeclarationIsRemoved() throws Exception {
		UUID rule = service.addRankGrant(hrAdmin, hr, hrTeam, Rank.K, WorkspaceRole.VIEWER);
		String config = Files.readString(BOOTSTRAP_CONFIG);
		Files.writeString(BOOTSTRAP_CONFIG, config.replace("    grants:\n",
				"    rank_grants:\n      - { org_unit: hr, rank: K, role: VIEWER, node: hr }\n    grants:\n"));
		assertThat(service.listRankGrants(hrAdmin, hr)).contains(
				new WorkspaceManagementService.RankGrantView(rule, hrTeam, Rank.K, WorkspaceRole.VIEWER, true));
		assertStatus(HttpStatus.CONFLICT, () -> service.changeRankGrantRole(hrAdmin, rule, WorkspaceRole.ADMIN));
		assertStatus(HttpStatus.CONFLICT, () -> service.removeRankGrant(hrAdmin, rule));

		Files.writeString(BOOTSTRAP_CONFIG, config);
		service.changeRankGrantRole(hrAdmin, rule, WorkspaceRole.ADMIN);
		assertThat(jdbc.queryForObject("select role from rank_grn where id = ?", String.class, rule)).isEqualTo("ADMIN");
	}

	@Test
	void changingDeclaredRankRuleAddsNewTupleWithoutRemovingOldOne() throws Exception {
		String config = Files.readString(BOOTSTRAP_CONFIG);
		String viewerConfig = config.replace("    grants:\n",
				"    rank_grants:\n      - { org_unit: hr, rank: K, role: VIEWER, node: hr }\n    grants:\n");
		Files.writeString(BOOTSTRAP_CONFIG, viewerConfig);
		bootstrap.runCurrentConfiguration();
		assertThat(service.listRankGrants(hrAdmin, hr)).extracting(WorkspaceManagementService.RankGrantView::role)
				.containsExactly(WorkspaceRole.VIEWER);

		Files.writeString(BOOTSTRAP_CONFIG, viewerConfig.replace("rank: K, role: VIEWER", "rank: K, role: ADMIN"));
		bootstrap.runCurrentConfiguration();

		assertThat(service.listRankGrants(hrAdmin, hr))
				.extracting(WorkspaceManagementService.RankGrantView::role)
				.containsExactlyInAnyOrder(WorkspaceRole.VIEWER, WorkspaceRole.ADMIN);
		assertThat(jdbc.queryForObject("select count(*) from rank_grn where tnn_id = ? and wrk_node_id = ?",
				Integer.class, tenantId, hr)).isEqualTo(2);
	}

	private UUID team(String key) {
		return jdbc.queryForObject("select id from org_unit where tnn_id = ? and org_unit_key = ?", UUID.class, tenantId, key);
	}

	/** app_user와 배정(사원)을 만들고 관리 연산의 행위자로 쓴다. */
	private Actor actor(String name, UUID orgUnit) {
		String subject = name + "-" + tag;
		UUID userId = UUID.randomUUID();
		jdbc.update("insert into app_user (id, keycloak_subj) values (?, ?)", userId, subject);
		members.assign(subject, tenantId, orgUnit, Rank.S);
		return new Actor(userId, subject, List.of("USER"), "req-" + UUID.randomUUID());
	}

	private UUID collabRoom(UUID workspace) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into thr (id, kind, created_user_id, title, tnn_id, wrk_node_id) values (?, 'COLLAB', ?, 't', ?, ?)",
				id, hrAdmin.userId(), tenantId, workspace);
		return id;
	}

	private List<String> grants(UUID node) {
		return jdbc.queryForList("select org_unit_id || ':' || role from wrk_grn where wrk_node_id = ? order by org_unit_id, role",
				String.class, node);
	}

	private List<String> grantCreatedAt(UUID node) {
		return jdbc.queryForList("select id || ':' || created_at from wrk_grn where wrk_node_id = ? order by id", String.class, node);
	}

	private UUID grantId(UUID node, UUID orgUnit) {
		return jdbc.queryForObject("select id from wrk_grn where wrk_node_id = ? and org_unit_id = ?", UUID.class, node, orgUnit);
	}

	private UUID parentOf(UUID node) {
		return jdbc.queryForObject("select prn_id from wrk_node where id = ?", UUID.class, node);
	}

	private String status(UUID node) {
		return jdbc.queryForObject("select status from wrk_node where id = ?", String.class, node);
	}

	/** 사람(USER)이 남긴 감사 이벤트를 시간순으로. bootstrap이 남긴 SYSTEM 행은 뺀다. */
	private List<String> events() {
		return jdbc.queryForList("select evt_kind from authz_adt where tnn_id = ? and act_kind = 'USER' order by created_at, id",
				String.class, tenantId);
	}

	private static void assertStatus(HttpStatus expected, Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode()).isEqualTo(expected));
	}
}
