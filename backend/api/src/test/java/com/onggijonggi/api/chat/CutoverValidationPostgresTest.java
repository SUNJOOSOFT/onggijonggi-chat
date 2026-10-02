package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.api.authz.CutoverValidationResult;
import com.onggijonggi.api.authz.CutoverValidationService;
import com.onggijonggi.common.authz.Rank;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Class Name : CutoverValidationPostgresTest.java
 * Description : PostgreSQL의 절체 전 스키마에서 단일 Tenant·최종 Workspace VIEW를 읽기 전용으로 검증한다.
 */
class CutoverValidationPostgresTest extends PostgresSpringTestBase {

	@Autowired private CutoverValidationService validation;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private FakeMemberAttributesConfig.FakeMemberAttributes members;

	@BeforeEach
	void isolateTenant() {
		jdbc.update("delete from thr");
		jdbc.update("update tnn set status = 'INACTIVE', inactive_at = now() where status = 'ACTIVE'");
	}

	@Test
	void noActiveTenantBlocksOperationalCutover() {
		CutoverValidationResult result = validation.validate(List.of());
		assertThat(result.ready()).isFalse();
		assertThat(result.failures()).extracting(CutoverValidationResult.Failure::code)
				.contains("ACTIVE_TENANT_COUNT");
	}

	@Test
	void missingCommonReportsOnlyTheRootCause() {
		UUID tenant = UUID.randomUUID();
		String key = "t" + tenant.toString().substring(0, 8);
		jdbc.update("insert into tnn (id, tnn_key, name) values (?, ?, ?)", tenant, key, key);
		UUID owner = user("owner-" + tenant);
		UUID direct = thread("DIRECT", owner, null);
		member(direct, owner);

		CutoverValidationResult result = validation.validate(List.of());
		assertThat(result.ready()).isFalse();
		assertThat(result.tenantId()).isEqualTo(tenant);
		assertThat(codes(result)).containsExactly("ACTIVE_COMMON_REQUIRED");
	}

	@Test
	void duplicateCommonIsRejectedByTheDatabase() {
		Fixture fixture = fixture();
		UUID root = jdbc.queryForObject("select prn_id from wrk_node where id = ?", UUID.class, fixture.common());
		UUID duplicate = UUID.randomUUID();
		assertThatThrownBy(() -> jdbc.update("insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'common', 'COMMON', 'Duplicate', array[?, ?]::uuid[])",
				duplicate, fixture.tenant(), root, root, duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void blankEnabledSubjectBlocksCutover() {
		Fixture fixture = fixture();
		CutoverValidationResult result = validation.validateWithKeycloak(List.of(" "));
		assertThat(result.ready()).isFalse();
		assertThat(result.tenantId()).isEqualTo(fixture.tenant());
		assertThat(codes(result)).containsExactly("INVALID_ENABLED_SUBJECT");
	}

	@Test
	void directAndCollabUseTheirFinalWorkspaces() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID direct = thread("DIRECT", owner, null);
		member(direct, owner);
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);
		grant(fixture.tenant(), fixture.team(), fixture.common());
		grant(fixture.tenant(), fixture.team(), fixture.teamWorkspace());

		CutoverValidationResult result = validation.validate(List.of(fixture.subject()));
		assertThat(result.ready()).isTrue();
		assertThat(result.tenantId()).isEqualTo(fixture.tenant());
		assertThat(result.failures()).isEmpty();
		assertThat(jdbc.queryForObject("select tnn_id from thr where id = ?", UUID.class, direct)).isNull();
	}

	@Test
	void commonViewDoesNotGrantViewOfCollabWorkspace() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);
		grant(fixture.tenant(), fixture.team(), fixture.common());

		CutoverValidationResult result = validation.validate(List.of(fixture.subject()));
		assertThat(result.ready()).isFalse();
		assertThat(result.failures()).extracting(CutoverValidationResult.Failure::code)
				.contains("THREAD_ACTOR_VIEW_MISSING");
	}

	@Test
	void locallyInactiveButKeycloakEnabledOwnerIsStillChecked() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		jdbc.update("update app_user set status = 'INACTIVE', inactive_at = now() where id = ?", owner);
		UUID direct = thread("DIRECT", owner, null);
		member(direct, owner);

		CutoverValidationResult result = validation.validate(List.of(fixture.subject()));
		assertThat(result.failures()).extracting(CutoverValidationResult.Failure::code)
				.contains("THREAD_ACTOR_VIEW_MISSING");
	}

	@Test
	void rankRuleWithoutTeamPassesAtTheRankBoundaryAndFailsAboveIt() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);

		rankGrant(fixture.tenant(), fixture.teamWorkspace(), "K", null, "VIEWER");
		assertThat(codes(validation.validate(List.of(fixture.subject())))).contains("THREAD_ACTOR_VIEW_MISSING");

		rankGrant(fixture.tenant(), fixture.teamWorkspace(), "S", null, "VIEWER");
		assertThat(validation.validate(List.of(fixture.subject())).ready()).isTrue();
	}

	@Test
	void rankRuleForAnotherTeamDoesNotCount() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);
		UUID other = UUID.randomUUID();
		jdbc.update("insert into org_unit (id, tnn_id, org_unit_key, name) values (?, ?, 'other', 'Other')", other, fixture.tenant());

		rankGrant(fixture.tenant(), fixture.teamWorkspace(), "S", other, "VIEWER");
		assertThat(codes(validation.validate(List.of(fixture.subject())))).contains("THREAD_ACTOR_VIEW_MISSING");
	}

	@Test
	void contributorAndAdminGrantsIncludeView() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);

		grant(fixture.tenant(), fixture.team(), fixture.common());
		grant(fixture.tenant(), fixture.team(), fixture.teamWorkspace(), "CONTRIBUTOR");
		assertThat(validation.validate(List.of(fixture.subject())).ready()).isTrue();

		jdbc.update("delete from wrk_grn where wrk_node_id = ?", fixture.teamWorkspace());
		grant(fixture.tenant(), fixture.team(), fixture.teamWorkspace(), "ADMIN");
		assertThat(validation.validate(List.of(fixture.subject())).ready()).isTrue();
	}

	@Test
	void directThreadPlacedOutsideCommonIsReported() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID direct = thread("DIRECT", owner, fixture.teamWorkspace());
		member(direct, owner);
		grant(fixture.tenant(), fixture.team(), fixture.teamWorkspace());

		assertThat(codes(validation.validate(List.of(fixture.subject())))).contains("DIRECT_OUTSIDE_COMMON");
	}

	@Test
	void enabledSubjectWithoutAssignmentIsReported() {
		Fixture fixture = fixture();
		members.unassign(fixture.subject());

		CutoverValidationResult result = validation.validate(List.of(fixture.subject()));
		assertThat(result.failures()).contains(new CutoverValidationResult.Failure("INVALID_ACTIVE_SUBJECT_ASSIGNMENT", null, fixture.subject()));
	}

	@Test
	void duplicatedFailuresAreReportedOnce() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);
		jdbc.update("insert into thr_idm_key (id, thr_id, user_id, idm_key, title) values (?, ?, ?, 'k', 't')", UUID.randomUUID(), collab, owner);

		long sameFailure = validation.validate(List.of(fixture.subject())).failures().stream()
				.filter(failure -> failure.code().equals("THREAD_ACTOR_VIEW_MISSING")).count();
		assertThat(sameFailure).isEqualTo(1);
	}

	@Test
	void twoActiveTenantsStopTheValidation() {
		fixture();
		fixture();

		CutoverValidationResult result = validation.validate(List.of());
		assertThat(result.ready()).isFalse();
		assertThat(result.tenantId()).isNull();
		assertThat(codes(result)).containsExactly("ACTIVE_TENANT_COUNT");
	}

	@Test
	void collabInAnInactiveWorkspaceIsReported() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);
		jdbc.update("update wrk_node set status = 'INACTIVE', inactive_at = now() where id = ?", fixture.teamWorkspace());

		assertThat(codes(validation.validate(List.of()))).contains("INVALID_THREAD_WORKSPACE");
	}

	@Test
	void directThreadWithoutItsOwnerMembershipIsReported() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID direct = thread("DIRECT", owner, null);
		grant(fixture.tenant(), fixture.team(), fixture.common());

		CutoverValidationResult result = validation.validate(List.of(fixture.subject()));
		assertThat(result.failures()).contains(new CutoverValidationResult.Failure("DIRECT_OWNER_MEMBERSHIP_MISSING", direct, null));
	}

	@Test
	void locallyInactiveButKeycloakEnabledOwnerPassesWhenTheGrantExists() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		jdbc.update("update app_user set status = 'INACTIVE', inactive_at = now() where id = ?", owner);
		UUID direct = thread("DIRECT", owner, null);
		member(direct, owner);
		grant(fixture.tenant(), fixture.team(), fixture.common());

		// 위 실패 테스트의 양성 대조: 권한을 주면 같은 계정이 통과한다.
		assertThat(validation.validate(List.of(fixture.subject())).ready()).isTrue();
	}

	@Test
	void sameHistoryFailsOnlyWhileTheAccountIsEnabledInKeycloak() {
		Fixture fixture = fixture();
		UUID owner = user(fixture.subject());
		UUID collab = thread("COLLAB", null, fixture.teamWorkspace());
		member(collab, owner);

		assertThat(validation.validate(List.of()).ready()).isTrue();
		assertThat(validation.validate(List.of(fixture.subject())).ready()).isFalse();
	}

	@Test
	void emptyKeycloakListWithLocalUsersIsNotReady() {
		Fixture fixture = fixture();
		user(fixture.subject());

		assertThat(codes(validation.validateWithKeycloak(List.of()))).contains("ENABLED_SUBJECTS_EMPTY");
		assertThat(codes(validation.validateWithKeycloak(List.of(fixture.subject())))).doesNotContain("ENABLED_SUBJECTS_EMPTY");
	}

	private static List<String> codes(CutoverValidationResult result) {
		return result.failures().stream().map(CutoverValidationResult.Failure::code).toList();
	}

	private void rankGrant(UUID tenant, UUID workspace, String rank, UUID orgUnit, String role) {
		jdbc.update("insert into rank_grn (id, tnn_id, wrk_node_id, rank, org_unit_id, role) values (?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), tenant, workspace, rank, orgUnit, role);
	}

	private void grant(UUID tenant, UUID team, UUID workspace, String role) {
		jdbc.update("insert into wrk_grn (id, tnn_id, org_unit_id, wrk_node_id, role) values (?, ?, ?, ?, ?)", UUID.randomUUID(), tenant, team, workspace, role);
	}

	private Fixture fixture() {
		String key = "t" + UUID.randomUUID().toString().substring(0, 8);
		UUID tenant = UUID.randomUUID();
		UUID root = UUID.randomUUID();
		UUID common = UUID.randomUUID();
		UUID workspace = UUID.randomUUID();
		UUID team = UUID.randomUUID();
		String subject = "cutover-" + key;
		jdbc.update("insert into tnn (id, tnn_key, name) values (?, ?, ?)", tenant, key, key);
		jdbc.update("insert into wrk_node (id, tnn_id, node_key, kind, name, path) values (?, ?, 'root', 'ROOT', 'Root', array[?]::uuid[])", root, tenant, root);
		jdbc.update("insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'common', 'COMMON', 'Common', array[?, ?]::uuid[])", common, tenant, root, root, common);
		jdbc.update("insert into wrk_node (id, tnn_id, prn_id, node_key, kind, name, path) values (?, ?, ?, 'team', 'WORK', 'Team', array[?, ?]::uuid[])", workspace, tenant, root, root, workspace);
		jdbc.update("insert into org_unit (id, tnn_id, org_unit_key, name) values (?, ?, 'team', 'Team')", team, tenant);
		members.assign(subject, tenant, team, Rank.S);
		return new Fixture(tenant, common, workspace, team, subject);
	}

	private UUID user(String subject) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into app_user (id, keycloak_subj) values (?, ?)", id, subject);
		return id;
	}

	private UUID thread(String kind, UUID directOwner, UUID workspace) {
		UUID id = UUID.randomUUID();
		UUID creator = directOwner == null ? user("creator-" + id) : directOwner;
		jdbc.update("insert into thr (id, kind, created_user_id, drc_own_user_id, title, wrk_node_id) values (?, ?, ?, ?, 't', ?)", id, kind, creator, directOwner, workspace);
		return id;
	}

	private void member(UUID thread, UUID user) {
		jdbc.update("insert into thr_mbr (id, thr_id, user_id, role, created_by_user_id) values (?, ?, ?, 'OWNER', ?)", UUID.randomUUID(), thread, user, user);
	}

	private void grant(UUID tenant, UUID team, UUID workspace) {
		grant(tenant, team, workspace, "VIEWER");
	}

	private record Fixture(UUID tenant, UUID common, UUID teamWorkspace, UUID team, String subject) {
	}
}
