package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Class Name : RbacBootstrapValidatorTest.java
 * Description : bootstrap 설정 검증 규칙을 DB 없이 검증한다. 규칙마다 위반 하나만 만든 설정을 넣어 그 규칙의 메시지가
 *               나오는지 확인한다(다른 규칙 때문에 우연히 통과하는 단정을 피한다).
 */
class RbacBootstrapValidatorTest {

	private final RbacBootstrapValidator validator = new RbacBootstrapValidator();

	private static RbacBootstrapSpec.OrgUnitSpec unit(String key) {
		return new RbacBootstrapSpec.OrgUnitSpec(key, "Unit " + key, "ACTIVE");
	}

	private static RbacBootstrapSpec.NodeSpec node(String key, String parent, String name) {
		return new RbacBootstrapSpec.NodeSpec(key, "ORG", parent, name, "ACTIVE");
	}

	private static RbacBootstrapSpec.GrantSpec grant(String unit, String role, String node) {
		return new RbacBootstrapSpec.GrantSpec(unit, role, node);
	}

	/** 통과하는 기준 설정: org-unit 하나, 최상위 노드 하나, COMMON VIEWER와 최상위 ADMIN 부여. */
	private static RbacBootstrapSpec.TenantSpec baseline() {
		return new RbacBootstrapSpec.TenantSpec("acme", "ACME", "ACTIVE", List.of(unit("sales")),
				List.of(node("sales-hq", "root", "Sales HQ")),
				List.of(grant("sales", "VIEWER", "common"), grant("sales", "ADMIN", "sales-hq")));
	}

	private static RbacBootstrapSpec spec(RbacBootstrapSpec.TenantSpec... tenants) {
		return new RbacBootstrapSpec(new RbacBootstrapSpec.Reconcile(false, null), List.of(tenants));
	}

	private static RbacBootstrapSpec.TenantSpec with(UnaryOperator<TenantParts> change) {
		RbacBootstrapSpec.TenantSpec base = baseline();
		TenantParts parts = change.apply(new TenantParts(base.key(), base.name(), base.status(),
				new ArrayList<>(base.orgUnits()), new ArrayList<>(base.nodes()), new ArrayList<>(base.grants())));
		return new RbacBootstrapSpec.TenantSpec(parts.key, parts.name, parts.status, parts.units, parts.nodes, parts.grants);
	}

	private static final class TenantParts {
		String key;
		String name;
		String status;
		final List<RbacBootstrapSpec.OrgUnitSpec> units;
		final List<RbacBootstrapSpec.NodeSpec> nodes;
		final List<RbacBootstrapSpec.GrantSpec> grants;

		TenantParts(String key, String name, String status, List<RbacBootstrapSpec.OrgUnitSpec> units,
				List<RbacBootstrapSpec.NodeSpec> nodes, List<RbacBootstrapSpec.GrantSpec> grants) {
			this.key = key;
			this.name = name;
			this.status = status;
			this.units = units;
			this.nodes = nodes;
			this.grants = grants;
		}
	}

	private List<String> problemsOf(RbacBootstrapSpec.TenantSpec tenant) {
		return validator.validate(spec(tenant));
	}

	private static void assertOne(List<String> problems, String fragment) {
		assertThat(problems).as("문제 목록: %s", problems).hasSize(1).first().asString().contains(fragment);
	}

	@Test
	void aWellFormedConfigurationHasNoProblems() {
		assertThat(problemsOf(baseline())).isEmpty();
	}

	@Test
	void anEmptyTenantListIsRejected() {
		assertThat(validator.validate(spec())).containsExactly("tenants가 비어 있다");
	}

	@Test
	void tenantKeysMustBeWellFormedAndUnique() {
		assertOne(problemsOf(with(parts -> {
			parts.key = "Acme_Corp";
			return parts;
		})), "tnn_key 형식이 올바르지 않다");
		assertOne(validator.validate(spec(baseline(), baseline())), "tnn_key가 중복이다");
	}

	@Test
	void tooManyTenantsAreRejected() {
		List<RbacBootstrapSpec.TenantSpec> tenants = new ArrayList<>();
		for (int index = 0; index <= RbacBootstrapValidator.MAX_TENANTS; index++) {
			tenants.add(new RbacBootstrapSpec.TenantSpec("t" + index, "T", "ACTIVE", List.of(), List.of(), List.of()));
		}
		assertThat(validator.validate(new RbacBootstrapSpec(new RbacBootstrapSpec.Reconcile(false, null), tenants)))
				.contains("tenants가 " + RbacBootstrapValidator.MAX_TENANTS + "개를 넘는다");
	}

	@Test
	void statusesMustBeActiveOrInactive() {
		assertOne(problemsOf(with(parts -> {
			parts.status = "DELETED";
			return parts;
		})), "status는 ACTIVE 또는 INACTIVE여야 한다");
	}

	@Test
	void declaredOrgUnitKeysMustBeWellFormedAndUnique() {
		assertThat(problemsOf(with(parts -> {
			parts.units.add(new RbacBootstrapSpec.OrgUnitSpec("Bad Key", "x", "ACTIVE"));
			return parts;
		}))).anyMatch(problem -> problem.contains("org_unit Bad Key") && problem.contains("key 형식이 올바르지 않다"));
		assertThat(problemsOf(with(parts -> {
			parts.units.add(unit("sales"));
			return parts;
		}))).anyMatch(problem -> problem.contains("org_unit sales") && problem.contains("key가 중복이다"));
	}

	@Test
	void rootAndCommonCannotBeDeclaredBecauseTheyAreCreatedAutomatically() {
		assertThat(problemsOf(with(parts -> {
			parts.nodes.add(node("common", "root", "Common Two"));
			return parts;
		}))).anyMatch(problem -> problem.contains("root·common은 Tenant 생성 때 자동으로 만들어져 선언할 수 없다"));
	}

	@Test
	void nodeKindMustBeOrgOrWork() {
		assertOne(problemsOf(with(parts -> {
			parts.nodes.set(0, new RbacBootstrapSpec.NodeSpec("sales-hq", "ROOT", "root", "Sales HQ", "ACTIVE"));
			return parts;
		})), "kind는 ORG 또는 WORK여야 한다");
	}

	@Test
	void aNodeParentMustBeRootOrAnotherDeclaredNode() {
		assertThat(problemsOf(with(parts -> {
			parts.nodes.add(node("orphan", "nowhere", "Orphan"));
			return parts;
		}))).anyMatch(problem -> problem.contains("node orphan") && problem.contains("root도 선언된 노드도 아니다"));
	}

	@Test
	void nothingCanBeDeclaredUnderCommon() {
		assertThat(problemsOf(with(parts -> {
			parts.nodes.add(node("under-common", "common", "Under Common"));
			return parts;
		}))).anyMatch(problem -> problem.contains("common 아래에는 노드를 둘 수 없다"));
	}

	@Test
	void parentCyclesAreReportedWithoutLoopingForever() {
		assertThat(problemsOf(with(parts -> {
			parts.nodes.clear();
			parts.nodes.add(node("a", "b", "A"));
			parts.nodes.add(node("b", "a", "B"));
			return parts;
		}))).anyMatch(problem -> problem.contains("parent가 순환한다"));
	}

	@Test
	void depthUnderRootIsCappedAtTen() {
		List<RbacBootstrapSpec.NodeSpec> chain = new ArrayList<>();
		String parent = "root";
		for (int level = 1; level <= 11; level++) {
			chain.add(node("n" + level, parent, "Level " + level));
			parent = "n" + level;
		}
		assertThat(problemsOf(with(parts -> {
			parts.nodes.clear();
			parts.nodes.addAll(chain);
			parts.grants.removeIf(grant -> grant.node().equals("sales-hq"));
			parts.grants.add(grant("sales", "ADMIN", "n1"));
			return parts;
		}))).containsExactly("tenant acme node n11: ROOT 아래 깊이가 10단을 넘는다");
	}

	@Test
	void tenLevelsAreAllowed() {
		List<RbacBootstrapSpec.NodeSpec> chain = new ArrayList<>();
		String parent = "root";
		for (int level = 1; level <= 10; level++) {
			chain.add(node("n" + level, parent, "Level " + level));
			parent = "n" + level;
		}
		assertThat(problemsOf(with(parts -> {
			parts.nodes.clear();
			parts.nodes.addAll(chain);
			parts.grants.removeIf(grant -> grant.node().equals("sales-hq"));
			parts.grants.add(grant("sales", "ADMIN", "n1"));
			return parts;
		}))).isEmpty();
	}

	@Test
	void anActiveNodeCannotHaveAnInactiveParent() {
		assertThat(problemsOf(with(parts -> {
			parts.nodes.set(0, new RbacBootstrapSpec.NodeSpec("sales-hq", "ORG", "root", "Sales HQ", "INACTIVE"));
			parts.nodes.add(node("team", "sales-hq", "Team"));
			return parts;
		}))).anyMatch(problem -> problem.contains("node team") && problem.contains("ACTIVE 노드의 부모 sales-hq가 INACTIVE다"));
	}

	@Test
	void activeSiblingNamesMustBeUniqueIgnoringCaseAndKind() {
		assertThat(problemsOf(with(parts -> {
			parts.nodes.add(new RbacBootstrapSpec.NodeSpec("sales-two", "WORK", "root", "SALES HQ", "ACTIVE"));
			parts.grants.add(grant("sales", "ADMIN", "sales-two"));
			return parts;
		}))).anyMatch(problem -> problem.contains("활성 형제와 이름이 겹친다"));
	}

	@Test
	void aTopLevelNodeCannotReuseTheAutomaticCommonName() {
		assertThat(problemsOf(with(parts -> {
			parts.nodes.add(node("shared", "root", "common"));
			parts.grants.add(grant("sales", "ADMIN", "shared"));
			return parts;
		}))).anyMatch(problem -> problem.contains("node shared") && problem.contains("활성 형제와 이름이 겹친다"));
	}

	@Test
	void inactiveSiblingsMayShareANameWithAnActiveOne() {
		assertThat(problemsOf(with(parts -> {
			parts.nodes.add(new RbacBootstrapSpec.NodeSpec("old", "ORG", "root", "Sales HQ", "INACTIVE"));
			return parts;
		}))).isEmpty();
	}

	@Test
	void nodeNamesMustBeNonBlankAndTrimmed() {
		// 앞뒤 공백뿐 아니라 가운데 개행·제어문자도 막는다(DB CHECK와 같은 규칙).
		for (String bad : new String[] { " Padded", "Padded ", "\tTabbed", "Line\n", "   ", "", "a\nb", "a\u0001b" }) {
			assertThat(problemsOf(with(parts -> {
				parts.nodes.set(0, node("sales-hq", "root", bad));
				return parts;
			}))).as("이름 '%s'", bad.replace("\n", "\\n").replace("\t", "\\t"))
					.anyMatch(problem -> problem.contains("비었거나 앞뒤 공백·제어문자가 있다"));
		}
		assertThat(problemsOf(with(parts -> {
			parts.nodes.set(0, node("sales-hq", "root", "영업 본부 (서울)"));
			return parts;
		}))).isEmpty();
	}

	@Test
	void namesLongerThan255CharactersAreRejected() {
		String tooLong = "x".repeat(256);
		assertOne(problemsOf(with(parts -> {
			parts.name = tooLong;
			return parts;
		})), "name이 255자를 넘는다");
		assertOne(problemsOf(with(parts -> {
			parts.nodes.set(0, node("sales-hq", "root", tooLong));
			return parts;
		})), "name이 255자를 넘는다");
	}

	@Test
	void grantsMustPointAtDeclaredActiveTargets() {
		assertThat(problemsOf(with(parts -> {
			parts.grants.add(grant("ghost", "VIEWER", "sales-hq"));
			return parts;
		}))).anyMatch(problem -> problem.contains("org_unit이 같은 Tenant에 선언돼 있지 않다"));
		assertThat(problemsOf(with(parts -> {
			parts.grants.add(grant("sales", "VIEWER", "ghost-node"));
			return parts;
		}))).anyMatch(problem -> problem.contains("node가 같은 Tenant에 선언돼 있지 않다"));
		assertThat(problemsOf(with(parts -> {
			parts.grants.add(grant("sales", "OWNER", "sales-hq"));
			return parts;
		}))).anyMatch(problem -> problem.contains("role은 VIEWER·CONTRIBUTOR·ADMIN 중 하나여야 한다"));
	}

	@Test
	void grantsCannotTargetRootOrInactiveResources() {
		assertThat(problemsOf(with(parts -> {
			parts.grants.add(grant("sales", "VIEWER", "root"));
			return parts;
		}))).anyMatch(problem -> problem.contains("ROOT에는 부여할 수 없다"));
		assertThat(problemsOf(with(parts -> {
			parts.nodes.add(new RbacBootstrapSpec.NodeSpec("closed", "ORG", "root", "Closed", "INACTIVE"));
			parts.grants.add(grant("sales", "VIEWER", "closed"));
			return parts;
		}))).anyMatch(problem -> problem.contains("INACTIVE node에는 부여할 수 없다"));
		assertThat(problemsOf(with(parts -> {
			parts.units.add(new RbacBootstrapSpec.OrgUnitSpec("retired", "Retired", "INACTIVE"));
			parts.grants.add(grant("retired", "VIEWER", "common"));
			return parts;
		}))).anyMatch(problem -> problem.contains("INACTIVE org_unit에는 부여할 수 없다"));
	}

	@Test
	void duplicateGrantsAreRejected() {
		assertThat(problemsOf(with(parts -> {
			parts.grants.add(grant("sales", "ADMIN", "sales-hq"));
			return parts;
		}))).anyMatch(problem -> problem.contains("같은 부여가 중복이다"));
	}

	@Test
	void everyActiveOrgUnitNeedsACommonViewerGrant() {
		assertOne(problemsOf(with(parts -> {
			parts.grants.removeIf(grant -> grant.node().equals("common"));
			return parts;
		})), "COMMON VIEWER 부여가 선언돼 있지 않다");
		// CONTRIBUTOR만으로는 충족되지 않는다.
		assertOne(problemsOf(with(parts -> {
			parts.grants.removeIf(grant -> grant.node().equals("common"));
			parts.grants.add(grant("sales", "CONTRIBUTOR", "common"));
			return parts;
		})), "COMMON VIEWER 부여가 선언돼 있지 않다");
	}

	@Test
	void anInactiveOrgUnitDoesNotNeedACommonViewerGrant() {
		assertThat(problemsOf(with(parts -> {
			parts.units.add(new RbacBootstrapSpec.OrgUnitSpec("retired", "Retired", "INACTIVE"));
			return parts;
		}))).isEmpty();
	}

	@Test
	void everyActiveTopLevelNodeNeedsAnAdminGrant() {
		assertOne(problemsOf(with(parts -> {
			parts.grants.removeIf(grant -> grant.role().equals("ADMIN"));
			return parts;
		})), "최상위 노드에 ADMIN 부여가 선언돼 있지 않다");
		// 하위 노드는 상위 ADMIN 상속을 받으므로 ADMIN 부여를 요구하지 않는다.
		assertThat(problemsOf(with(parts -> {
			parts.nodes.add(node("team", "sales-hq", "Team"));
			return parts;
		}))).isEmpty();
	}

	@Test
	void anAdminGrantFromAnInactiveOrgUnitDoesNotCount() {
		assertThat(problemsOf(with(parts -> {
			parts.units.add(new RbacBootstrapSpec.OrgUnitSpec("retired", "Retired", "INACTIVE"));
			parts.grants.removeIf(grant -> grant.role().equals("ADMIN"));
			parts.grants.add(grant("retired", "ADMIN", "sales-hq"));
			return parts;
		}))).anyMatch(problem -> problem.contains("최상위 노드에 ADMIN 부여가 선언돼 있지 않다"));
	}

	@Test
	void declarationsBeyondTheLimitsAreRejectedWithoutFurtherChecks() {
		List<RbacBootstrapSpec.NodeSpec> nodes = new ArrayList<>();
		for (int index = 0; index <= RbacBootstrapValidator.MAX_NODES_PER_TENANT; index++) {
			nodes.add(node("n" + index, "root", "Node " + index));
		}
		List<String> problems = problemsOf(new RbacBootstrapSpec.TenantSpec("acme", "ACME", "ACTIVE", List.of(unit("sales")),
				nodes, List.of()));
		assertOne(problems, "선언이 상한");
	}

	@Test
	void problemsFromDifferentTenantsAreAllReported() {
		RbacBootstrapSpec.TenantSpec broken = new RbacBootstrapSpec.TenantSpec("globex", "Globex", "ACTIVE",
				List.of(unit("ops")), List.of(), List.of());
		List<String> problems = validator.validate(spec(baseline(), broken));
		assertThat(problems).hasSize(1).first().asString().contains("tenant globex").contains("COMMON VIEWER");
	}
}
