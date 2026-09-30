package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceRole;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Class Name : CasbinPolicyTest.java
 * Description : wrk_grn·rank_grn이 Casbin ABAC 정책 행으로 바뀌는 모양과 model.conf가 그 행을 받는 모양을 검증한다.
 *               실제 판정은 casbin-server가 한다 — 여기서는 식 문자열과 속성 이름이 서로 맞는지만 본다.
 */
class CasbinPolicyTest {

	private static final UUID TENANT = UUID.randomUUID();
	private static final UUID HR = UUID.randomUUID();
	private static final UUID HR_NODE = UUID.randomUUID();
	private static final UUID EXEC_NODE = UUID.randomUUID();

	@Test
	void teamGrantBecomesAnOrgUnitEqualityRule() {
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(
				List.of(new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.VIEWER)), List.of());

		assertThat(rules).containsExactly(new CasbinPolicy.Rule("r.sub.OrgUnit == '" + HR + "'", HR_NODE.toString(), "view"));
	}

	@Test
	void rankGrantBecomesAnOrderComparisonSoThatHigherRanksAlsoPass() {
		// 과장(K) 이상 = 서열 4 이하. 팀장(1)~과장(4)이 통과하고 대리(5)·사원(6)은 통과하지 않는다.
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(List.of(), List.of(new RankGrant(TENANT, EXEC_NODE, Rank.K)));

		assertThat(rules).containsExactly(new CasbinPolicy.Rule("r.sub.Rank <= 4", EXEC_NODE.toString(), "view"));
	}

	@Test
	void aRankGrantWithATeamRequiresBothTheTeamAndTheRank() {
		// "인사팀의 과장 이상". 팀이 없으면 다른 팀의 과장 이상도 통과한다.
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(List.of(), List.of(new RankGrant(TENANT, EXEC_NODE, HR, Rank.K)));

		assertThat(rules).containsExactly(
				new CasbinPolicy.Rule("r.sub.OrgUnit == '" + HR + "' && r.sub.Rank <= 4", EXEC_NODE.toString(), "view"));
	}

	@Test
	void rankGrantExpandsItsRoleIntoTheSameActionsAsADirectGrant() {
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(List.of(), List.of(
				new RankGrant(TENANT, EXEC_NODE, HR, Rank.K, WorkspaceRole.ADMIN)));

		assertThat(rules).containsExactlyInAnyOrder(
				new CasbinPolicy.Rule("r.sub.OrgUnit == '" + HR + "' && r.sub.Rank <= 4", EXEC_NODE.toString(), "view"),
				new CasbinPolicy.Rule("r.sub.OrgUnit == '" + HR + "' && r.sub.Rank <= 4", EXEC_NODE.toString(), "thread_create"),
				new CasbinPolicy.Rule("r.sub.OrgUnit == '" + HR + "' && r.sub.Rank <= 4", EXEC_NODE.toString(), "manage"));
	}

	/** 같은 조건에 역할이 다른 행이 함께 있으면 허용을 합친다(#299) — 겹치는 액션은 한 행으로 합친다. */
	@Test
	void rankGrantsWithTheSameConditionButDifferentRolesAreUnioned() {
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(List.of(), List.of(
				new RankGrant(TENANT, EXEC_NODE, null, Rank.C, WorkspaceRole.VIEWER),
				new RankGrant(TENANT, EXEC_NODE, null, Rank.C, WorkspaceRole.CONTRIBUTOR)));

		assertThat(rules).containsExactlyInAnyOrder(
				new CasbinPolicy.Rule("r.sub.Rank <= 3", EXEC_NODE.toString(), "view"),
				new CasbinPolicy.Rule("r.sub.Rank <= 3", EXEC_NODE.toString(), "thread_create"));
	}

	@Test
	void viewerGrantOnlyProducesTheViewAction() {
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(
				List.of(new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.VIEWER)), List.of());

		assertThat(rules).extracting(CasbinPolicy.Rule::action).containsExactly("view");
	}

	@Test
	void contributorGrantAddsThreadCreateOnTopOfView() {
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(
				List.of(new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.CONTRIBUTOR)), List.of());

		assertThat(rules).extracting(CasbinPolicy.Rule::action).containsExactlyInAnyOrder("view", "thread_create");
	}

	@Test
	void adminGrantAddsManageOnTopOfViewAndThreadCreate() {
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(
				List.of(new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.ADMIN)), List.of());

		assertThat(rules).extracting(CasbinPolicy.Rule::action).containsExactlyInAnyOrder("view", "thread_create", "manage");
	}

	@Test
	void severalRolesOfTheSameTeamAndNodeCollapseWhereTheirActionsOverlap() {
		// #264 bootstrap은 같은 (팀, 노드)에 VIEWER와 ADMIN을 함께 둘 수 있다 — ADMIN의 액션 집합이 VIEWER를 이미
		// 포함하므로 VIEW 행은 하나로 합쳐지고, ADMIN이 추가로 갖는 thread_create·manage만 더해진다.
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(List.of(
				new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.VIEWER),
				new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.ADMIN)), List.of());

		assertThat(rules).extracting(CasbinPolicy.Rule::action).containsExactlyInAnyOrder("view", "thread_create", "manage");
	}

	@Test
	void rankOrderRunsFromTeamLeaderDownToStaff() {
		assertThat(List.of(Rank.values()).stream().map(Rank::order).toList()).containsExactly(1, 2, 3, 4, 5, 6);
	}

	@Test
	void modelEvaluatesThePolicyRuleAgainstTheRequestAttributes() throws IOException {
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(CasbinPolicy.MODEL_RESOURCE)) {
			assertThat(in).isNotNull();
			String model = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(model).contains("r = sub, obj, act", "p = sub_rule, obj, act",
					"m = eval(p.sub_rule) && r.obj == p.obj && r.act == p.act");
		}
	}
}
