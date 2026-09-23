package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.Rank;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Class Name : RbacBootstrapValidator.java
 * Description : bootstrap 설정을 DB를 건드리기 전에 검증한다. 하나라도 어긋나면 bootstrap 전체가 실행되지 않는다.
 *               검증 규칙은 0001 6.0 「설정 검증」이다: key 형식·중복, 선언 노드의 부모(root 또는 다른 선언 노드,
 *               순환·깊이), 모든 ACTIVE org-unit의 COMMON VIEWER 부여, 최상위 노드마다 ADMIN 부여,
 *               부여가 같은 Tenant에 선언된 ACTIVE 대상을 가리킬 것.
 *               직급 규칙(rank_grants)도 같은 대상 규칙을 따른다. 최상위 ADMIN 부여의 예외는 하나다 — 팀 부여가 하나도
 *               없고 그 노드나 하위 노드에 직급 규칙이 있는 최상위 노드("전사"처럼 직급 규칙만 쓰는 공간)는 ADMIN 부여
 *               없이 둘 수 있다. 팀 부여가 있는 최상위 노드나 규칙이 아예 없는(빠뜨린) 최상위 노드는 지금처럼 거부한다.
 */
@Component
public class RbacBootstrapValidator {

	/** ROOT 아래 최대 10단(path 길이 11) — Casbin 기본 역할 관리자의 상속 탐색 깊이다. */
	static final int MAX_DEPTH_UNDER_ROOT = 10;

	/** 설정 크기 상한. 마운트된 파일은 배포 통제 입력이지만 실수로 커진 선언이 기동 때 자원을 소진하지 않게 한다. */
	static final int MAX_TENANTS = 100;
	static final int MAX_ORG_UNITS_PER_TENANT = 500;
	static final int MAX_NODES_PER_TENANT = 2000;
	static final int MAX_GRANTS_PER_TENANT = 10000;

	/** Tenant 생성 때 자동으로 만드는 ROOT·COMMON의 표시명. 같은 부모 아래 이름 충돌 검사에 쓴다. */
	static final String ROOT_NAME = "Root";
	static final String COMMON_NAME = "Common";

	private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
	/** wrk_node.name CHECK(wrk_node_name_trimmed)와 같은 규칙: 비어 있지 않고 앞뒤 공백이 없다. */
	private static final Pattern NODE_NAME = Pattern.compile("\\S([\\s\\S]*\\S)?");

	/** 표시명은 목록·감사 로그에 그대로 나가므로 가운데에 개행·제어문자가 섞이는 것도 막는다(DB CHECK와 같은 규칙). */
	private static boolean isDisplayName(String value) {
		return NODE_NAME.matcher(value).matches() && value.codePoints().noneMatch(Character::isISOControl);
	}
	private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
	private static final Set<String> NODE_KINDS = Set.of("ORG", "WORK");
	private static final Set<String> ROLES = Set.of("VIEWER", "CONTRIBUTOR", "ADMIN");
	private static final Set<String> RANKS = Arrays.stream(Rank.values())
			.map(Enum::name).collect(Collectors.toSet());

	public List<String> validate(RbacBootstrapSpec spec) {
		List<String> problems = new ArrayList<>();
		if (spec.tenants().isEmpty()) problems.add("tenants가 비어 있다");
		if (spec.tenants().size() > MAX_TENANTS) problems.add("tenants가 " + MAX_TENANTS + "개를 넘는다");
		Set<String> tenantKeys = new HashSet<>();
		for (RbacBootstrapSpec.TenantSpec tenant : spec.tenants()) {
			String scope = "tenant " + tenant.key();
			if (!KEY.matcher(tenant.key()).matches()) problems.add(scope + ": tnn_key 형식이 올바르지 않다");
			if (!tenantKeys.add(tenant.key())) problems.add(scope + ": tnn_key가 중복이다");
			if (tenant.name().length() > 255) problems.add(scope + ": name이 255자를 넘는다");
			status(problems, scope, tenant.status());
			validateTenant(problems, scope, tenant);
		}
		return problems;
	}

	private void validateTenant(List<String> problems, String scope, RbacBootstrapSpec.TenantSpec tenant) {
		if (tenant.orgUnits().size() > MAX_ORG_UNITS_PER_TENANT || tenant.nodes().size() > MAX_NODES_PER_TENANT
				|| tenant.grants().size() + tenant.rankGrants().size() > MAX_GRANTS_PER_TENANT) {
			problems.add(scope + ": 선언이 상한(org_units " + MAX_ORG_UNITS_PER_TENANT + ", nodes " + MAX_NODES_PER_TENANT
					+ ", grants " + MAX_GRANTS_PER_TENANT + ")을 넘는다");
			return;
		}
		Map<String, RbacBootstrapSpec.OrgUnitSpec> units = new HashMap<>();
		for (RbacBootstrapSpec.OrgUnitSpec unit : tenant.orgUnits()) {
			String unitScope = scope + " org_unit " + unit.key();
			if (!KEY.matcher(unit.key()).matches()) problems.add(unitScope + ": key 형식이 올바르지 않다");
			if (units.put(unit.key(), unit) != null) problems.add(unitScope + ": key가 중복이다");
			if (unit.name().length() > 255) problems.add(unitScope + ": name이 255자를 넘는다");
			status(problems, unitScope, unit.status());
		}

		Map<String, RbacBootstrapSpec.NodeSpec> nodes = new HashMap<>();
		for (RbacBootstrapSpec.NodeSpec node : tenant.nodes()) {
			String nodeScope = scope + " node " + node.key();
			if (!KEY.matcher(node.key()).matches()) problems.add(nodeScope + ": node_key 형식이 올바르지 않다");
			if (node.key().equals("root") || node.key().equals("common")) {
				problems.add(nodeScope + ": root·common은 Tenant 생성 때 자동으로 만들어져 선언할 수 없다");
			}
			if (nodes.put(node.key(), node) != null) problems.add(nodeScope + ": node_key가 중복이다");
			if (!NODE_KINDS.contains(node.kind())) problems.add(nodeScope + ": kind는 ORG 또는 WORK여야 한다");
			if (node.name().length() > 255) problems.add(nodeScope + ": name이 255자를 넘는다");
			if (!isDisplayName(node.name())) problems.add(nodeScope + ": name이 비었거나 앞뒤 공백·제어문자가 있다");
			status(problems, nodeScope, node.status());
		}
		validateNodeTree(problems, scope, nodes);
		validateGrants(problems, scope, tenant, units, nodes);
	}

	private void validateNodeTree(List<String> problems, String scope, Map<String, RbacBootstrapSpec.NodeSpec> nodes) {
		for (RbacBootstrapSpec.NodeSpec node : nodes.values()) {
			String nodeScope = scope + " node " + node.key();
			String parent = node.parent();
			if (parent.equals("common")) {
				problems.add(nodeScope + ": common 아래에는 노드를 둘 수 없다(COMMON은 leaf)");
				continue;
			}
			if (!parent.equals("root") && !nodes.containsKey(parent)) {
				problems.add(nodeScope + ": parent " + parent + "가 root도 선언된 노드도 아니다");
				continue;
			}
			int depth = depth(node, nodes);
			if (depth < 0) {
				problems.add(nodeScope + ": parent가 순환한다");
			} else if (depth > MAX_DEPTH_UNDER_ROOT) {
				problems.add(nodeScope + ": ROOT 아래 깊이가 " + MAX_DEPTH_UNDER_ROOT + "단을 넘는다");
			}
			RbacBootstrapSpec.NodeSpec parentNode = nodes.get(parent);
			if (parentNode != null && parentNode.status().equals("INACTIVE") && node.status().equals("ACTIVE")) {
				problems.add(nodeScope + ": ACTIVE 노드의 부모 " + parent + "가 INACTIVE다(ACTIVE 노드의 조상은 모두 ACTIVE여야 한다)");
			}
		}
		// 같은 부모 아래 활성 형제의 이름은 종류와 무관하게, 대소문자를 무시하고 유일해야 한다.
		Map<String, Set<String>> siblingNames = new HashMap<>();
		siblingNames.computeIfAbsent("root", key -> new HashSet<>()).add(COMMON_NAME.toLowerCase(Locale.ROOT));
		for (RbacBootstrapSpec.NodeSpec node : nodes.values()) {
			if (!node.status().equals("ACTIVE")) continue;
			if (!siblingNames.computeIfAbsent(node.parent(), key -> new HashSet<>())
					.add(node.name().toLowerCase(Locale.ROOT))) {
				problems.add(scope + " node " + node.key() + ": 같은 부모 " + node.parent() + " 아래 활성 형제와 이름이 겹친다");
			}
		}
	}

	/** root 직속이 1이다. 순환이면 -1을 돌려준다. 재귀 대신 반복문을 써서 깊은 선언이 스택을 넘기지 않게 한다. */
	private int depth(RbacBootstrapSpec.NodeSpec node, Map<String, RbacBootstrapSpec.NodeSpec> nodes) {
		int depth = 1;
		Set<String> seen = new HashSet<>();
		seen.add(node.key());
		RbacBootstrapSpec.NodeSpec current = node;
		while (!current.parent().equals("root")) {
			RbacBootstrapSpec.NodeSpec parent = nodes.get(current.parent());
			if (parent == null) return depth; // 없는 부모는 호출부가 따로 보고한다
			if (!seen.add(parent.key())) return -1;
			current = parent;
			depth++;
		}
		return depth;
	}

	private void validateGrants(List<String> problems, String scope, RbacBootstrapSpec.TenantSpec tenant,
			Map<String, RbacBootstrapSpec.OrgUnitSpec> units, Map<String, RbacBootstrapSpec.NodeSpec> nodes) {
		Set<String> seen = new HashSet<>();
		Set<String> commonViewer = new HashSet<>();
		Set<String> adminNodes = new HashSet<>();
		for (RbacBootstrapSpec.GrantSpec grant : tenant.grants()) {
			String grantScope = scope + " grant " + grant.orgUnit() + "/" + grant.role() + "/" + grant.node();
			if (!ROLES.contains(grant.role())) problems.add(grantScope + ": role은 VIEWER·CONTRIBUTOR·ADMIN 중 하나여야 한다");
			RbacBootstrapSpec.OrgUnitSpec unit = units.get(grant.orgUnit());
			if (unit == null) {
				problems.add(grantScope + ": org_unit이 같은 Tenant에 선언돼 있지 않다");
			} else if (!unit.status().equals("ACTIVE")) {
				problems.add(grantScope + ": INACTIVE org_unit에는 부여할 수 없다");
			}
			if (grant.node().equals("root")) {
				problems.add(grantScope + ": ROOT에는 부여할 수 없다(구조 전용)");
			} else if (!grant.node().equals("common")) {
				RbacBootstrapSpec.NodeSpec node = nodes.get(grant.node());
				if (node == null) {
					problems.add(grantScope + ": node가 같은 Tenant에 선언돼 있지 않다");
				} else if (!node.status().equals("ACTIVE")) {
					problems.add(grantScope + ": INACTIVE node에는 부여할 수 없다");
				}
			}
			if (!seen.add(grant.orgUnit() + "|" + grant.role() + "|" + grant.node())) {
				problems.add(grantScope + ": 같은 부여가 중복이다");
			}
			if (unit != null && grant.role().equals("VIEWER") && grant.node().equals("common")) commonViewer.add(grant.orgUnit());
			if (unit != null && unit.status().equals("ACTIVE") && grant.role().equals("ADMIN")) adminNodes.add(grant.node());
		}
		// 모든 ACTIVE org-unit은 COMMON VIEWER 부여를 가져야 한다(DIRECT가 COMMON VIEW를 요구한다).
		for (RbacBootstrapSpec.OrgUnitSpec unit : units.values()) {
			if (unit.status().equals("ACTIVE") && !commonViewer.contains(unit.key())) {
				problems.add(scope + " org_unit " + unit.key() + ": COMMON VIEWER 부여가 선언돼 있지 않다");
			}
		}
		Set<String> rankOnlyTopNodes = validateRankGrants(problems, scope, tenant, units, nodes);
		Set<String> teamGrantedNodes = new HashSet<>();
		for (RbacBootstrapSpec.GrantSpec grant : tenant.grants()) teamGrantedNodes.add(grant.node());
		// 최상위(ROOT 직속) 노드마다 ADMIN 부여가 하나 이상 있어야 한다(ROOT에는 부여할 수 없어 관리자가 없다).
		// 예외: 팀 부여가 없고 직급 규칙만 쓰는 최상위 노드. 팀 부여가 있으면 판정이 role을 보지 않아 그 팀 전원이
		// 노드를 보게 되므로, "과장 이상만" 같은 공간을 최상위에 두려면 ADMIN 부여 없이 둘 수 있어야 한다.
		for (RbacBootstrapSpec.NodeSpec node : nodes.values()) {
			if (node.parent().equals("root") && node.status().equals("ACTIVE") && !adminNodes.contains(node.key())
					&& !(rankOnlyTopNodes.contains(node.key()) && !teamGrantedNodes.contains(node.key()))) {
				problems.add(scope + " node " + node.key() + ": 최상위 노드에 ADMIN 부여가 선언돼 있지 않다");
			}
		}
	}

	/** 직급 규칙을 검증하고, 직급 규칙이 걸린 노드(자신 또는 하위)를 가진 최상위 노드 key를 돌려준다. */
	private Set<String> validateRankGrants(List<String> problems, String scope, RbacBootstrapSpec.TenantSpec tenant,
			Map<String, RbacBootstrapSpec.OrgUnitSpec> units, Map<String, RbacBootstrapSpec.NodeSpec> nodes) {
		Set<String> seen = new HashSet<>();
		Set<String> topNodes = new HashSet<>();
		for (RbacBootstrapSpec.RankGrantSpec grant : tenant.rankGrants()) {
			String grantScope = scope + " rank_grant " + (grant.orgUnit() == null ? "*" : grant.orgUnit()) + "/" + grant.rank()
					+ "/" + grant.node();
			if (!RANKS.contains(grant.rank())) problems.add(grantScope + ": rank는 TL·B·C·K·D·S 중 하나여야 한다");
			if (grant.orgUnit() != null) {
				RbacBootstrapSpec.OrgUnitSpec unit = units.get(grant.orgUnit());
				if (unit == null) {
					problems.add(grantScope + ": org_unit이 같은 Tenant에 선언돼 있지 않다");
				} else if (!unit.status().equals("ACTIVE")) {
					problems.add(grantScope + ": INACTIVE org_unit에는 규칙을 둘 수 없다");
				}
			}
			// 직급 규칙은 선언한 ORG·WORK 노드에만 둔다. COMMON은 누구나 보므로 규칙이 뜻이 없다.
			RbacBootstrapSpec.NodeSpec node = nodes.get(grant.node());
			if (node == null) {
				problems.add(grantScope + ": node가 같은 Tenant에 선언된 노드가 아니다(root·common에는 둘 수 없다)");
			} else if (!node.status().equals("ACTIVE")) {
				problems.add(grantScope + ": INACTIVE node에는 규칙을 둘 수 없다");
			} else {
				String top = topAncestor(node, nodes);
				if (top != null) topNodes.add(top);
			}
			if (!seen.add(grant.orgUnit() + "|" + grant.rank() + "|" + grant.node())) {
				problems.add(grantScope + ": 같은 직급 규칙이 중복이다");
			}
		}
		return topNodes;
	}

	/** 노드가 속한 최상위(ROOT 직속) 노드 key. 부모가 없거나 순환이면 null(그 문제는 트리 검증이 보고한다). */
	private String topAncestor(RbacBootstrapSpec.NodeSpec node, Map<String, RbacBootstrapSpec.NodeSpec> nodes) {
		Set<String> seen = new HashSet<>();
		RbacBootstrapSpec.NodeSpec current = node;
		while (!current.parent().equals("root")) {
			current = nodes.get(current.parent());
			if (current == null || !seen.add(current.key())) return null;
		}
		return current.key();
	}

	private void status(List<String> problems, String scope, String value) {
		if (!STATUSES.contains(value)) problems.add(scope + ": status는 ACTIVE 또는 INACTIVE여야 한다");
	}
}
