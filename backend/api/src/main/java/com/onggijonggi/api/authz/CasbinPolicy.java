package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceRole;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Class Name : CasbinPolicy.java
 * Description : 03·CORE DB의 규칙(wrk_grn 팀 규칙, rank_grn 직급 서열 규칙 — 팀이 붙으면 팀과 서열을 함께 본다)을
 *               Casbin ABAC 정책 행으로 바꾼다.
 *               모델은 resources/casbin/model.conf다. 판정 요청의 속성 이름(ORG_UNIT·RANK)도 여기서 정해
 *               정책 식과 요청이 같은 이름을 쓰게 한다. wrk_grn의 role은 {@link #ROLE_ACTIONS}로 액션 집합에
 *               펼쳐지고, 같은 (팀, 노드, 액션) 조합은 한 행으로 합친다. rank_grn은 role 개념이 없어 항상
 *               VIEW 하나다("그 서열 이상이면 볼 수 있다"). 대상은 id로 적는다 — key는 Tenant 안에서만 유일하다.
 */
public final class CasbinPolicy {

	public static final String MODEL_RESOURCE = "casbin/model.conf";
	/** 판정 요청 r.sub JSON의 팀 속성. 값은 org_unit.id 문자열이다. */
	public static final String ORG_UNIT = "OrgUnit";
	/** 판정 요청 r.sub JSON의 서열 속성. 값은 Rank.order() 숫자다(문자열이면 casbin-server가 비교에서 오류를 낸다). */
	public static final String RANK = "Rank";
	/** 볼 수 있나. */
	public static final String VIEW = "view";
	/** 그 workspace에 Thread를 만들 수 있나. */
	public static final String THREAD_CREATE = "thread_create";
	/** 그 workspace의 노드·부여를 바꿀 수 있나(관리). */
	public static final String MANAGE = "manage";

	/** wrk_grn.role → 그 role이 갖는 액션 집합. 상위 role은 하위 role의 액션을 전부 포함한다. */
	private static final Map<WorkspaceRole, Set<String>> ROLE_ACTIONS = new EnumMap<>(WorkspaceRole.class);
	static {
		ROLE_ACTIONS.put(WorkspaceRole.VIEWER, Set.of(VIEW));
		ROLE_ACTIONS.put(WorkspaceRole.CONTRIBUTOR, Set.of(VIEW, THREAD_CREATE));
		ROLE_ACTIONS.put(WorkspaceRole.ADMIN, Set.of(VIEW, THREAD_CREATE, MANAGE));
	}

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
			String subjectRule = orgUnitRule(grant.getOrgUnitId());
			String object = grant.getWorkspaceNodeId().toString();
			for (String action : ROLE_ACTIONS.get(grant.getRole())) {
				rules.add(new Rule(subjectRule, object, action));
			}
		}
		for (RankGrant grant : rankGrants) {
			String rankRule = "r.sub." + RANK + " <= " + grant.getRank().order();
			// 팀이 있으면 "그 팀 사람이면서 서열 조건" — 없으면 다른 팀의 같은 직급도 통과한다.
			String rule = grant.getOrgUnitId() == null ? rankRule : orgUnitRule(grant.getOrgUnitId()) + " && " + rankRule;
			rules.add(new Rule(rule, grant.getWorkspaceNodeId().toString(), VIEW));
		}
		return new ArrayList<>(rules);
	}

	private static String orgUnitRule(UUID orgUnitId) {
		return "r.sub." + ORG_UNIT + " == '" + orgUnitId + "'";
	}
}
