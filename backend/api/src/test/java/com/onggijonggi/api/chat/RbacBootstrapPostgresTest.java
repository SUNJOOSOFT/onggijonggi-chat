package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.api.authz.RbacBootstrapConfigurationException;
import com.onggijonggi.api.authz.RbacBootstrapResult;
import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.common.authz.AuthorizationAudit;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : RbacBootstrapPostgresTest.java
 * Description : bootstrap을 실제 PostgreSQL 16 위에서 JPA·trigger·jsonb까지 포함해 끝까지 검증한다(DB-TST-048·060·061·062·063).
 *               테스트마다 고유한 Tenant key를 쓰므로 서로 간섭하지 않는다.
 */
class RbacBootstrapPostgresTest extends PostgresSpringTestBase {

	@Autowired
	private RbacBootstrapService bootstrap;
	@Autowired
	private TenantRepository tenants;
	@Autowired
	private OrgUnitRepository orgUnits;
	@Autowired
	private WorkspaceNodeRepository nodes;
	@Autowired
	private WorkspaceGrantRepository grants;
	@Autowired
	private AuthorizationAuditRepository audits;
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private DataSource dataSource;

	private String key;

	@BeforeEach
	void uniqueTenant() {
		key = "acme-" + UUID.randomUUID().toString().substring(0, 8);
	}

	// ------------------------------------------------------------------ 생성 (DB-TST-060·061)

	@Test
	void newTenantIsCreatedWithRootCommonDeclaredResourcesAndOneAuditRowPerChange() throws Exception {
		RbacBootstrapResult result = run(new Cfg(key));

		assertThat(result.createdTenants()).containsExactly(key);
		assertThat(result.driftTenants()).isEmpty();
		assertThat(result.failures()).isEmpty();
		assertThat(bootstrap.isTenantFailClosed(key)).isFalse();

		Tenant tenant = tenants.findByKey(key).orElseThrow();
		assertThat(tenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
		WorkspaceNode root = node(tenant, "root");
		WorkspaceNode common = node(tenant, "common");
		assertThat(root.getKind()).isEqualTo(WorkspaceNodeKind.ROOT);
		assertThat(common.getKind()).isEqualTo(WorkspaceNodeKind.COMMON);
		assertThat(common.getParentId()).isEqualTo(root.getId());
		WorkspaceNode hq = node(tenant, "sales-hq");
		WorkspaceNode domestic = node(tenant, "domestic");
		assertThat(hq.getParentId()).isEqualTo(root.getId());
		assertThat(domestic.getParentId()).isEqualTo(hq.getId());
		assertThat(domestic.getPath()).containsExactly(root.getId(), hq.getId(), domestic.getId());
		assertThat(orgUnits.findByTenantId(tenant.getId())).hasSize(2);
		assertThat(grants.findByTenantId(tenant.getId())).hasSize(5);

		// 감사: 변경마다 한 행, 한 실행의 행은 같은 req_id, SYSTEM 행위자, reconcile이 꺼져 있어 dpl_id는 없다.
		List<AuthorizationAudit> rows = audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId());
		assertThat(countOf(rows, AuthorizationAuditEventKind.TENANT_CREATED)).isEqualTo(1);
		assertThat(countOf(rows, AuthorizationAuditEventKind.ORG_UNIT_CREATED)).isEqualTo(2);
		assertThat(countOf(rows, AuthorizationAuditEventKind.NODE_CREATED)).isEqualTo(5); // ROOT·COMMON + 선언 3개
		assertThat(countOf(rows, AuthorizationAuditEventKind.POLICY_ADDED)).isEqualTo(5);
		assertThat(rows).hasSize(13);
		assertThat(rows).extracting(AuthorizationAudit::getRequestId).doesNotContainNull().containsOnly(rows.get(0).getRequestId());
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.getActorKind().name()).isEqualTo("SYSTEM");
			assertThat(row.getActorUserId()).isNull();
			assertThat(row.getActorRoleJson()).isEqualTo("[]");
			assertThat(row.getDeploymentId()).isNull();
			assertThat(row.getConfigurationFingerprint()).matches("[0-9a-f]{64}");
		});

		// trg_ref·wrk_node_id·snapshot 형식은 대상 종류별로 고정돼 있다.
		AuthorizationAudit tenantRow = first(rows, AuthorizationAuditEventKind.TENANT_CREATED);
		assertThat(json(tenantRow.getTargetRef())).isEqualTo(objectMapper.readTree("{\"tnn_key\":\"" + key + "\"}"));
		assertThat(tenantRow.getWorkspaceNodeId()).isNull();
		assertThat(tenantRow.getBeforeJson()).isNull();
		assertThat(json(tenantRow.getAfterJson()).get("tnn_key").asString()).isEqualTo(key);
		AuthorizationAudit unitRow = first(rows, AuthorizationAuditEventKind.ORG_UNIT_CREATED);
		assertThat(json(unitRow.getTargetRef()).propertyNames()).containsExactly("org_unit_key");
		assertThat(unitRow.getWorkspaceNodeId()).isNull();
		AuthorizationAudit nodeRow = rows.stream().filter(row -> row.getEventKind() == AuthorizationAuditEventKind.NODE_CREATED
				&& json(row.getTargetRef()).get("node_key").asString().equals("domestic")).findFirst().orElseThrow();
		assertThat(nodeRow.getWorkspaceNodeId()).isEqualTo(domestic.getId());
		assertThat(json(nodeRow.getTargetRef()).get("wrk_node_id").asString()).isEqualTo(domestic.getId().toString());
		AuthorizationAudit policyRow = first(rows, AuthorizationAuditEventKind.POLICY_ADDED);
		assertThat(json(policyRow.getTargetRef()).propertyNames())
				.containsExactlyInAnyOrder("wrk_grn_id", "org_unit_key", "role", "wrk_node_id");
		assertThat(policyRow.getWorkspaceNodeId()).isNotNull();
	}

	@Test
	void runningTheSameConfigurationAgainChangesNothing() throws Exception {
		run(new Cfg(key));
		Tenant tenant = tenants.findByKey(key).orElseThrow();
		long before = audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId()).size();

		RbacBootstrapResult second = run(new Cfg(key));

		assertThat(second.createdTenants()).isEmpty();
		assertThat(second.driftTenants()).isEmpty();
		assertThat(second.processedTenants()).containsExactly(key);
		assertThat(audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId())).hasSize((int) before);
	}

	@Test
	void anInvalidConfigurationRunsNothingForAnyTenant() throws Exception {
		String otherKey = key + "-b";
		// 두 번째 Tenant에 dev의 COMMON VIEWER 부여가 빠져 있다. 첫 Tenant도 만들어지면 안 된다.
		String yaml = new Cfg(key).yaml() + new Cfg(otherKey).omitDevCommonViewer().tenantYaml();

		assertThatThrownBy(() -> runRaw(yaml)).isInstanceOf(RbacBootstrapConfigurationException.class)
				.satisfies(error -> assertThat(((RbacBootstrapConfigurationException) error).getProblems())
						.anyMatch(problem -> problem.contains("org_unit dev") && problem.contains("COMMON VIEWER")));

		assertThat(tenants.findByKey(key)).isEmpty();
		assertThat(tenants.findByKey(otherKey)).isEmpty();
	}

	// ------------------------------------------------------------------ drift (DB-TST-063)

	@Test
	void structuralDifferenceIsDriftFailsTheTenantClosedAndIsRecordedOncePerConfiguration() throws Exception {
		run(new Cfg(key).legacy("ACTIVE", true));
		Tenant tenant = tenants.findByKey(key).orElseThrow();

		Cfg drifted = new Cfg(key).legacy("INACTIVE", false);
		RbacBootstrapResult first = run(drifted);
		RbacBootstrapResult second = run(drifted);

		assertThat(first.driftTenants()).containsExactly(key);
		assertThat(second.driftTenants()).containsExactly(key);
		assertThat(bootstrap.isTenantFailClosed(key)).isTrue();
		// 자동으로 덮어쓰지 않는다.
		assertThat(node(tenant, "legacy").getStatus()).isEqualTo(WorkspaceNodeStatus.ACTIVE);
		// 같은 설정으로 다시 시작해도 같은 drift 행이 쌓이지 않는다.
		List<AuthorizationAudit> driftRows = audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId()).stream()
				.filter(row -> row.getEventKind() == AuthorizationAuditEventKind.TENANT_DRIFT_DETECTED).toList();
		assertThat(driftRows).hasSize(1);
		JsonNode item = json(driftRows.get(0).getAfterJson()).get("drift").get(0);
		assertThat(item.get("resource").asString()).isEqualTo("node");
		assertThat(item.get("key").asString()).isEqualTo("legacy");
		assertThat(item.get("field").asString()).isEqualTo("status");
		assertThat(item.get("declared").asString()).isEqualTo("INACTIVE");
		assertThat(item.get("actual").asString()).isEqualTo("ACTIVE");
		assertThat(driftRows.get(0).getWorkspaceNodeId()).isNull();

		// 선언을 DB와 다시 맞추면 fail-closed가 풀린다.
		RbacBootstrapResult healed = run(new Cfg(key).legacy("ACTIVE", true));
		assertThat(healed.driftTenants()).isEmpty();
		assertThat(bootstrap.isTenantFailClosed(key)).isFalse();
	}

	@Test
	void displayNameDifferencesAreOnlyWarnings() throws Exception {
		run(new Cfg(key));
		Tenant tenant = tenants.findByKey(key).orElseThrow();

		RbacBootstrapResult result = run(new Cfg(key).tenantName("ACME Korea").salesUnitName("Sales Unit 2"));

		assertThat(result.driftTenants()).isEmpty();
		assertThat(bootstrap.isTenantFailClosed(key)).isFalse();
		assertThat(result.warnings()).anyMatch(warning -> warning.contains(key) && warning.contains("tenant 표시명"));
		assertThat(result.warnings()).anyMatch(warning -> warning.contains("org_unit sales"));
		assertThat(tenants.findByKey(key).orElseThrow().getName()).isEqualTo("ACME");
		assertThat(audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId()))
				.noneMatch(row -> row.getEventKind() == AuthorizationAuditEventKind.TENANT_DRIFT_DETECTED);
	}

	@Test
	void grantsRemovedFromTheConfigurationAreKeptAndMissingOnesAreCreated() throws Exception {
		run(new Cfg(key));
		Tenant tenant = tenants.findByKey(key).orElseThrow();
		assertThat(grants.findByTenantId(tenant.getId())).hasSize(5);

		// 설정에서 CONTRIBUTOR 부여를 빼도 DB의 부여는 남는다(부여는 drift가 아니다).
		RbacBootstrapResult result = run(new Cfg(key).withoutContributorGrant());

		assertThat(result.driftTenants()).isEmpty();
		assertThat(grants.findByTenantId(tenant.getId())).hasSize(5);
	}

	// ------------------------------------------------------------------ reconcile (DB-TST-048·060)

	@Test
	void reconcileAppliesTheDeclarationOncePerDeploymentId() throws Exception {
		run(new Cfg(key).legacy("ACTIVE", true));
		Tenant tenant = tenants.findByKey(key).orElseThrow();

		// D2: 표시명·org-unit 이름·노드 상태를 선언대로 맞춘다.
		Cfg d2 = new Cfg(key).reconcile("D2").tenantName("ACME Korea").salesUnitName("Sales Unit 2").legacy("INACTIVE", false);
		RbacBootstrapResult applied = run(d2);

		assertThat(applied.driftTenants()).isEmpty();
		assertThat(bootstrap.isTenantFailClosed(key)).isFalse();
		assertThat(tenants.findByKey(key).orElseThrow().getName()).isEqualTo("ACME Korea");
		assertThat(orgUnits.findByTenantIdAndKey(tenant.getId(), "sales").orElseThrow().getName()).isEqualTo("Sales Unit 2");
		assertThat(node(tenant, "legacy").getStatus()).isEqualTo(WorkspaceNodeStatus.INACTIVE);
		List<AuthorizationAudit> d2Rows = rowsWithDeployment(tenant, "D2");
		assertThat(d2Rows).extracting(AuthorizationAudit::getEventKind).containsExactlyInAnyOrder(
				AuthorizationAuditEventKind.TENANT_RENAMED, AuthorizationAuditEventKind.ORG_UNIT_RENAMED,
				AuthorizationAuditEventKind.NODE_DEACTIVATED);
		assertThat(d2Rows).extracting(AuthorizationAudit::getRequestId).containsOnly(d2Rows.get(0).getRequestId());
		AuthorizationAudit deactivated = first(d2Rows, AuthorizationAuditEventKind.NODE_DEACTIVATED);
		assertThat(json(deactivated.getBeforeJson()).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(json(deactivated.getAfterJson()).get("status").asString()).isEqualTo("INACTIVE");

		// 같은 배포 ID를 다시 실행해도 아무것도 쓰지 않는다.
		int rows = audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId()).size();
		run(d2);
		assertThat(audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId())).hasSize(rows);

		// 같은 D2로 선언을 되돌리면 다시 적용하지 않고 drift로 남는다.
		RbacBootstrapResult sameId = run(new Cfg(key).reconcile("D2").tenantName("ACME Korea").salesUnitName("Sales Unit 2")
				.legacy("ACTIVE", true));
		assertThat(sameId.driftTenants()).containsExactly(key);
		assertThat(bootstrap.isTenantFailClosed(key)).isTrue();
		assertThat(node(tenant, "legacy").getStatus()).isEqualTo(WorkspaceNodeStatus.INACTIVE);

		// 새 배포 ID D3이면 적용한다(재활성화).
		RbacBootstrapResult reactivated = run(new Cfg(key).reconcile("D3").tenantName("ACME Korea").salesUnitName("Sales Unit 2")
				.legacy("ACTIVE", true));
		assertThat(reactivated.driftTenants()).isEmpty();
		assertThat(bootstrap.isTenantFailClosed(key)).isFalse();
		assertThat(node(tenant, "legacy").getStatus()).isEqualTo(WorkspaceNodeStatus.ACTIVE);
		assertThat(rowsWithDeployment(tenant, "D3")).extracting(AuthorizationAudit::getEventKind)
				.containsExactly(AuthorizationAuditEventKind.NODE_REACTIVATED);
	}

	@Test
	void reconcileCanDeactivateAndReactivateATenant() throws Exception {
		run(new Cfg(key));
		Tenant tenant = tenants.findByKey(key).orElseThrow();

		run(new Cfg(key).reconcile("T1").tenantStatus("INACTIVE"));
		assertThat(tenants.findByKey(key).orElseThrow().getStatus()).isEqualTo(TenantStatus.INACTIVE);
		assertThat(rowsWithDeployment(tenant, "T1")).extracting(AuthorizationAudit::getEventKind)
				.containsExactly(AuthorizationAuditEventKind.TENANT_DEACTIVATED);
		// 비활성화해도 하위 데이터와 부여는 보존한다.
		assertThat(nodes.findByTenantId(tenant.getId())).hasSize(5);
		assertThat(grants.findByTenantId(tenant.getId())).hasSize(5);

		run(new Cfg(key).reconcile("T2"));
		assertThat(tenants.findByKey(key).orElseThrow().getStatus()).isEqualTo(TenantStatus.ACTIVE);
		assertThat(rowsWithDeployment(tenant, "T2")).extracting(AuthorizationAudit::getEventKind)
				.containsExactly(AuthorizationAuditEventKind.TENANT_REACTIVATED);
	}

	@Test
	void aChangeTheNodeRulesForbidStaysDriftAndDoesNotStopOtherTenants() throws Exception {
		run(new Cfg(key));
		Tenant tenant = tenants.findByKey(key).orElseThrow();
		String otherKey = key + "-c";

		// domestic에는 CONTRIBUTOR 부여가 있어 reparent할 수 없다. 이 Tenant는 drift로 남고 다른 Tenant는 계속 처리된다.
		String yaml = new Cfg(key).reconcile("R1").domesticParent("dev-hq").yaml() + new Cfg(otherKey).tenantYaml();
		RbacBootstrapResult result = runRaw(yaml);

		assertThat(result.driftTenants()).containsExactly(key);
		assertThat(result.createdTenants()).containsExactly(otherKey);
		assertThat(result.failures()).isEmpty();
		assertThat(bootstrap.isTenantFailClosed(key)).isTrue();
		assertThat(bootstrap.isTenantFailClosed(otherKey)).isFalse();
		assertThat(node(tenant, "domestic").getParentId()).isEqualTo(node(tenant, "sales-hq").getId());
		assertThat(tenants.findByKey(otherKey)).isPresent();
	}

	@Test
	void parentChangeWithoutReconcileIsDrift() throws Exception {
		// DB-TST-063: 선언한 부모가 DB와 다르면(reconcile 없음) drift다. 덮어쓰지 않는다.
		run(new Cfg(key));
		Tenant tenant = tenants.findByKey(key).orElseThrow();

		RbacBootstrapResult result = run(new Cfg(key).domesticParent("dev-hq"));

		assertThat(result.driftTenants()).containsExactly(key);
		assertThat(bootstrap.isTenantFailClosed(key)).isTrue();
		assertThat(node(tenant, "domestic").getParentId()).isEqualTo(node(tenant, "sales-hq").getId());
		JsonNode item = audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId()).stream()
				.filter(row -> row.getEventKind() == AuthorizationAuditEventKind.TENANT_DRIFT_DETECTED)
				.map(row -> json(row.getAfterJson()).get("drift").get(0)).findFirst().orElseThrow();
		assertThat(item.get("key").asString()).isEqualTo("domestic");
		assertThat(item.get("field").asString()).isEqualTo("parent");
		assertThat(item.get("declared").asString()).isEqualTo("dev-hq");
		assertThat(item.get("actual").asString()).isEqualTo("sales-hq");
	}

	@Test
	void aNodeDeclaredInactiveIsCreatedInactiveEvenWhenAnActiveSiblingHasTheSameName() throws Exception {
		// 폐기한 노드와 후속 노드의 이름이 같은 흔한 선언이다. ACTIVE로 만든 뒤 끄면 형제 이름 unique가 걸리므로 처음부터
		// INACTIVE로 만들어야 한다.
		RbacBootstrapResult result = run(new Cfg(key).legacy("INACTIVE", false).legacyName("Sales HQ"));

		assertThat(result.failures()).isEmpty();
		assertThat(result.driftTenants()).isEmpty();
		Tenant tenant = tenants.findByKey(key).orElseThrow();
		assertThat(node(tenant, "legacy").getStatus()).isEqualTo(WorkspaceNodeStatus.INACTIVE);
		assertThat(node(tenant, "sales-hq").getStatus()).isEqualTo(WorkspaceNodeStatus.ACTIVE);
	}

	@Test
	void aMissingNodeThatCollidesWithAnUndeclaredSiblingIsDriftNotAFailure() throws Exception {
		run(new Cfg(key));
		Tenant tenant = tenants.findByKey(key).orElseThrow();
		WorkspaceNode root = node(tenant, "root");
		// 설정 밖에서 ADMIN이 만든 노드라고 본다. 선언하려는 노드와 같은 이름이다.
		nodes.saveAndFlush(WorkspaceNode.child(tenant.getId(), root.getId(), root.getPath(), "adhoc",
				WorkspaceNodeKind.ORG, "Legacy", WorkspaceNodeStatus.ACTIVE));

		RbacBootstrapResult result = run(new Cfg(key).legacy("ACTIVE", true));

		assertThat(result.failures()).isEmpty();
		assertThat(result.driftTenants()).containsExactly(key);
		assertThat(nodes.findByTenantIdAndKey(tenant.getId(), "legacy")).isEmpty();
		assertThat(bootstrap.isTenantFailClosed(key)).isTrue();
	}

	@Test
	void reconcileDoesNotDeactivateANodeThatStillHasActiveDescendantsOrThreads() throws Exception {
		run(new Cfg(key).legacy("ACTIVE", true));
		Tenant tenant = tenants.findByKey(key).orElseThrow();
		WorkspaceNode legacy = node(tenant, "legacy");
		// 설정 밖에서 만든 활성 하위 노드가 있다: 자동으로 끄지 않는다.
		WorkspaceNode child = nodes.saveAndFlush(WorkspaceNode.child(tenant.getId(), legacy.getId(), legacy.getPath(),
				"adhoc-child", WorkspaceNodeKind.WORK, "Adhoc Child", WorkspaceNodeStatus.ACTIVE));

		RbacBootstrapResult blockedByChild = run(new Cfg(key).reconcile("B1").legacy("INACTIVE", false));

		assertThat(blockedByChild.driftTenants()).containsExactly(key);
		assertThat(node(tenant, "legacy").getStatus()).isEqualTo(WorkspaceNodeStatus.ACTIVE);
		assertThat(nodes.findById(child.getId()).orElseThrow().getStatus()).isEqualTo(WorkspaceNodeStatus.ACTIVE);

		// 하위 노드가 없어도 Thread가 붙어 있으면 끄지 않는다(개편은 Thread를 먼저 옮긴 뒤 비활성화한다).
		String other = key + "-t";
		run(new Cfg(other).legacy("ACTIVE", true));
		Tenant otherTenant = tenants.findByKey(other).orElseThrow();
		UUID owner = UUID.randomUUID();
		jdbcInsertThreadOn(node(otherTenant, "legacy").getId(), owner);

		RbacBootstrapResult blockedByThread = run(new Cfg(other).reconcile("B2").legacy("INACTIVE", false));

		assertThat(blockedByThread.driftTenants()).containsExactly(other);
		assertThat(node(otherTenant, "legacy").getStatus()).isEqualTo(WorkspaceNodeStatus.ACTIVE);
	}

	@Test
	void renameAndReactivationConvergeInASingleReconcile() throws Exception {
		run(new Cfg(key).legacy("ACTIVE", true));
		Tenant tenant = tenants.findByKey(key).orElseThrow();
		run(new Cfg(key).reconcile("C1").legacy("INACTIVE", false));
		assertThat(node(tenant, "legacy").getStatus()).isEqualTo(WorkspaceNodeStatus.INACTIVE);

		// 옛 이름 "Legacy"를 쓰는 활성 노드가 새로 생기고, legacy는 새 이름으로 되살아나야 한다.
		String yaml = new Cfg(key).reconcile("C2").legacy("ACTIVE", true).legacyName("Legacy 2").takenName("Legacy").yaml();
		RbacBootstrapResult result = runRaw(yaml);

		assertThat(result.failures()).isEmpty();
		assertThat(result.driftTenants()).isEmpty();
		WorkspaceNode legacy = node(tenant, "legacy");
		assertThat(legacy.getName()).isEqualTo("Legacy 2");
		assertThat(legacy.getStatus()).isEqualTo(WorkspaceNodeStatus.ACTIVE);
		assertThat(node(tenant, "taken").getName()).isEqualTo("Legacy");
	}

	// ------------------------------------------------------------------ Tenant 잠금 (DB-TST-062)

	@Test
	void theTenantLockBlocksAnotherWriterUntilTheTransactionEnds() throws Exception {
		run(new Cfg(key));
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		java.util.concurrent.atomic.AtomicReference<String> whileHeld = new java.util.concurrent.atomic.AtomicReference<>();

		transaction.executeWithoutResult(status -> {
			tenants.findByKeyForUpdate(key).orElseThrow();
			// 다른 세션은 같은 행을 잠그려 하면 기다리다가 lock_timeout으로 실패해야 한다.
			whileHeld.set(CompletableFuture.supplyAsync(this::tryLockOnAnotherSession).orTimeout(10, TimeUnit.SECONDS).join());
		});

		assertThat(whileHeld.get()).isEqualTo("55P03");
		assertThat(tryLockOnAnotherSession()).isEqualTo("acquired");
	}

	// ------------------------------------------------------------------ 헬퍼

	private String tryLockOnAnotherSession() {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement timeout = connection.prepareStatement("set local lock_timeout = '400ms'");
					PreparedStatement lock = connection.prepareStatement("select 1 from tnn where tnn_key = ? for update")) {
				timeout.execute();
				lock.setString(1, key);
				lock.executeQuery();
				connection.rollback();
				return "acquired";
			}
		} catch (SQLException exception) {
			return exception.getSQLState();
		}
	}

	private RbacBootstrapResult run(Cfg cfg) throws IOException {
		return runRaw(cfg.yaml());
	}

	private RbacBootstrapResult runRaw(String yaml) throws IOException {
		Files.writeString(BOOTSTRAP_CONFIG, yaml);
		return bootstrap.runCurrentConfiguration();
	}

	private WorkspaceNode node(Tenant tenant, String nodeKey) {
		return nodes.findByTenantIdAndKey(tenant.getId(), nodeKey).orElseThrow();
	}

	private List<AuthorizationAudit> rowsWithDeployment(Tenant tenant, String deploymentId) {
		return audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId()).stream()
				.filter(row -> deploymentId.equals(row.getDeploymentId())).collect(Collectors.toList());
	}

	private long countOf(List<AuthorizationAudit> rows, AuthorizationAuditEventKind kind) {
		return rows.stream().filter(row -> row.getEventKind() == kind).count();
	}

	private AuthorizationAudit first(List<AuthorizationAudit> rows, AuthorizationAuditEventKind kind) {
		return rows.stream().filter(row -> row.getEventKind() == kind).findFirst().orElseThrow();
	}

	private JsonNode json(String value) {
		return objectMapper.readTree(value);
	}

	/** 노드에 Thread를 하나 붙인다(직접 SQL — 이 테스트는 bootstrap이 Thread를 확인하는지만 본다). */
	private void jdbcInsertThreadOn(UUID nodeId, UUID user) {
		try (Connection connection = dataSource.getConnection()) {
			try (PreparedStatement insertUser = connection.prepareStatement("insert into app_user (id, keycloak_subj) values (?, ?)");
					PreparedStatement insertThread = connection.prepareStatement(
							"insert into thr (id, kind, created_user_id, title, wrk_node_id) values (?, 'COLLAB', ?, 't', ?)")) {
				insertUser.setObject(1, user);
				insertUser.setString(2, "thread-owner-" + user);
				insertUser.executeUpdate();
				insertThread.setObject(1, UUID.randomUUID());
				insertThread.setObject(2, user);
				insertThread.setObject(3, nodeId);
				insertThread.executeUpdate();
			}
		} catch (SQLException exception) {
			throw new IllegalStateException(exception);
		}
	}

	/** bootstrap 설정 YAML을 만든다. 기본값은 유효한 설정이고, 테스트는 필요한 부분만 바꾼다. */
	private static final class Cfg {
		private final String key;
		private String reconcile;
		private String tenantName = "ACME";
		private String tenantStatus = "ACTIVE";
		private String salesUnitName = "Sales Unit";
		private String domesticParent = "sales-hq";
		private String legacyStatus;
		private String legacyName = "Legacy";
		private String takenName;
		private boolean legacyGrant;
		private boolean devCommonViewer = true;
		private boolean contributorGrant = true;

		Cfg(String key) {
			this.key = key;
		}

		Cfg reconcile(String deploymentId) { this.reconcile = deploymentId; return this; }
		Cfg tenantName(String value) { this.tenantName = value; return this; }
		Cfg tenantStatus(String value) { this.tenantStatus = value; return this; }
		Cfg salesUnitName(String value) { this.salesUnitName = value; return this; }
		Cfg domesticParent(String value) { this.domesticParent = value; return this; }
		Cfg legacy(String status, boolean withAdminGrant) { this.legacyStatus = status; this.legacyGrant = withAdminGrant; return this; }
		Cfg legacyName(String value) { this.legacyName = value; return this; }
		Cfg takenName(String value) { this.takenName = value; return this; }
		Cfg omitDevCommonViewer() { this.devCommonViewer = false; return this; }
		Cfg withoutContributorGrant() { this.contributorGrant = false; return this; }

		String yaml() {
			String header = reconcile == null ? "reconcile:\n  enabled: false\n"
					: "reconcile:\n  enabled: true\n  dpl_id: \"" + reconcile + "\"\n";
			return header + "tenants:\n" + tenantYaml();
		}

		/** tenants 목록의 항목 하나(여러 Tenant를 이어 붙일 때 쓴다). */
		String tenantYaml() {
			StringBuilder yaml = new StringBuilder();
			yaml.append("  - tnn_key: ").append(key).append('\n');
			yaml.append("    name: ").append(tenantName).append('\n');
			yaml.append("    status: ").append(tenantStatus).append('\n');
			yaml.append("    org_units:\n");
			yaml.append("      - { key: sales, name: \"").append(salesUnitName).append("\", status: ACTIVE }\n");
			yaml.append("      - { key: dev, name: Dev Unit, status: ACTIVE }\n");
			yaml.append("    nodes:\n");
			yaml.append("      - { node_key: sales-hq, kind: ORG, parent: root, name: Sales HQ, status: ACTIVE }\n");
			yaml.append("      - { node_key: domestic, kind: WORK, parent: ").append(domesticParent)
					.append(", name: Domestic, status: ACTIVE }\n");
			yaml.append("      - { node_key: dev-hq, kind: ORG, parent: root, name: Dev HQ, status: ACTIVE }\n");
			if (legacyStatus != null) {
				yaml.append("      - { node_key: legacy, kind: ORG, parent: root, name: \"").append(legacyName).append("\", status: ")
						.append(legacyStatus).append(" }\n");
			}
			if (takenName != null) {
				yaml.append("      - { node_key: taken, kind: ORG, parent: root, name: \"").append(takenName)
						.append("\", status: ACTIVE }\n");
			}
			yaml.append("    grants:\n");
			yaml.append("      - { org_unit: sales, role: VIEWER, node: common }\n");
			if (devCommonViewer) yaml.append("      - { org_unit: dev, role: VIEWER, node: common }\n");
			yaml.append("      - { org_unit: sales, role: ADMIN, node: sales-hq }\n");
			yaml.append("      - { org_unit: dev, role: ADMIN, node: dev-hq }\n");
			if (contributorGrant) yaml.append("      - { org_unit: sales, role: CONTRIBUTOR, node: domestic }\n");
			if (legacyGrant) yaml.append("      - { org_unit: dev, role: ADMIN, node: legacy }\n");
			if (takenName != null) yaml.append("      - { org_unit: dev, role: ADMIN, node: taken }\n");
			return yaml.toString();
		}
	}


}
