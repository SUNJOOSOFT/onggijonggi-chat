package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.WorkspaceGrant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Class Name : CasbinPolicy.java
 * Description : 03·CORE DB의 규칙(wrk_grn 팀 규칙, rank_grn 직급 서열 규칙)을 Casbin ABAC 정책 행으로 바꾼다.
 *               모델은 resources/casbin/model.conf다. 판정 요청의 속성 이름(ORG_UNIT·RANK)도 여기서 정해
 *               정책 식과 요청이 같은 이름을 쓰게 한다. wrk_grn의 role은 지금 판정에 쓰지 않아 같은 (팀, 노드)의
 *               여러 role은 한 행으로 합친다. 대상은 id로 적는다 — key는 Tenant 안에서만 유일하다.
 */
public final class CasbinPolicy {

	public static final String MODEL_RESOURCE = "casbin/model.conf";
	/** 판정 요청 r.sub JSON의 팀 속성. 값은 org_unit.id 문자열이다. */
	public static final String ORG_UNIT = "OrgUnit";
	/** 판정 요청 r.sub JSON의 서열 속성. 값은 Rank.order() 숫자다(문자열이면 casbin-server가 비교에서 오류를 낸다). */
	public static final String RANK = "Rank";
	/** 지금 판정하는 동작은 "볼 수 있나" 하나다. 볼 수 있으면 그 workspace에 방도 만들 수 있다. */
	public static final String VIEW = "view";

	private CasbinPolicy() {
	}

	/** Casbin p 정책 한 행. 순서대로 sub_rule, obj(wrk_node.id), act다. */
	public record Rule(String subjectRule, String object, String action) {

		public List<String> params() {
			return List.of(subjectRule, object, action);
		}
	}

	public static List<Rule> rules(List<WorkspaceGrant> workspaceGrants, List<RankGrant> rankGrants) {
		Set<Rule> rules = new LinkedHashSet<>();
		for (WorkspaceGrant grant : workspaceGrants) {
			rules.add(new Rule("r.sub." + ORG_UNIT + " == '" + grant.getOrgUnitId() + "'", grant.getWorkspaceNodeId().toString(), VIEW));
		}
		for (RankGrant grant : rankGrants) {
			rules.add(new Rule("r.sub." + RANK + " <= " + grant.getRank().order(), grant.getWorkspaceNodeId().toString(), VIEW));
		}
		return new ArrayList<>(rules);
	}
}
