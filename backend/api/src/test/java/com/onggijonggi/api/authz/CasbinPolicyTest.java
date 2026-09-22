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
	void severalRolesOfTheSameTeamAndNodeCollapseIntoOneRule() {
		// role은 지금 판정에 쓰지 않는다. #264 bootstrap은 같은 (팀, 노드)에 VIEWER와 ADMIN을 함께 둘 수 있다.
		List<CasbinPolicy.Rule> rules = CasbinPolicy.rules(List.of(
				new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.VIEWER),
				new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.ADMIN)), List.of());

		assertThat(rules).hasSize(1);
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
