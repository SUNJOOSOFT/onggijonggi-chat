package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.AuthorizationAudit;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.AuthorizationAuditTargetKind;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import com.onggijonggi.common.authz.WorkspaceRole;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : RbacBootstrapService.java
 * Description : 배포 환경에 마운트한 선언(YAML)으로 Tenant·조직 단위·Workspace 노드·부여를 만든다(0001 6.0).
 *               <ul>
 *               <li>설정 검증은 DB를 건드리기 전에 설정 전체에 대해 끝난다. 하나라도 어긋나면 아무것도 실행하지 않는다.</li>
 *               <li>Tenant마다 자기 트랜잭션에서 처리하고, 처음 시작할 때 `tnn` 행을 `SELECT … FOR UPDATE`로 잠근다
 *                   (새 Tenant는 잠글 행이 없어 `tnn_key` unique가 동시 생성을 막는다).</li>
 *               <li>선언됐는데 없는 리소스만 만든다. 기존 선언 대상의 **권한 구조**(Tenant·org-unit 상태, 노드 kind·부모·상태)가
 *                   다르면 자동으로 덮어쓰지 않고 drift로 판정해 그 Tenant만 fail-closed 표시를 하고
 *                   `TENANT_DRIFT_DETECTED`를 남긴다. 표시명 차이는 경고만 한다. 부여와 직급 규칙(rank_grn)은 drift가 아니다.</li>
 *               <li>reconcile은 설정에 `reconcile.enabled`와 배포 ID가 모두 있고 그 배포 ID가 이 Tenant에 아직 적용되지 않았을
 *                   때만 선언 차이를 적용한다. 노드 규칙(leaf만 reparent 등)에 막히는 변경은 적용하지 않고 drift로 남긴다.</li>
 *               <li>모든 변경은 SYSTEM 행위자로 `authz_adt`에 남기고, 한 실행의 행은 같은 `req_id`로 묶는다.
 *                   `dpl_id`는 reconcile이 켜진 실행의 행에만 채운다.</li>
 *               </ul>
 *               권한 강제(fail-closed 판정을 요청 인가에 거는 것)는 다른 이슈 범위다. 이 서비스는 drift 상태를 조회하는
 *               {@link #isTenantFailClosed(String)}까지만 제공한다.
 */
@Service
public class RbacBootstrapService {

	private static final Logger log = LoggerFactory.getLogger(RbacBootstrapService.class);
	/** ROOT 아래 최대 10단이므로 path 길이는 11 이하다. */
	private static final int MAX_PATH_LENGTH = RbacBootstrapValidator.MAX_DEPTH_UNDER_ROOT + 1;

	private final RbacBootstrapConfigReader configReader;
	private final RbacBootstrapValidator validator;
	private final TenantRepository tenantRepository;
	private final OrgUnitRepository orgUnitRepository;
	private final WorkspaceNodeRepository workspaceNodeRepository;
	private final WorkspaceGrantRepository workspaceGrantRepository;
	private final RankGrantRepository rankGrantRepository;
	private final AuthorizationAuditRepository authorizationAuditRepository;
	private final ThrRepository threadRepository;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactions;
	private final Set<String> failClosedTenants = ConcurrentHashMap.newKeySet();

	public RbacBootstrapService(RbacBootstrapConfigReader configReader, RbacBootstrapValidator validator,
			TenantRepository tenantRepository, OrgUnitRepository orgUnitRepository,
			WorkspaceNodeRepository workspaceNodeRepository, WorkspaceGrantRepository workspaceGrantRepository,
			RankGrantRepository rankGrantRepository, AuthorizationAuditRepository authorizationAuditRepository, ThrRepository threadRepository,
			ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
		this.configReader = configReader;
		this.validator = validator;
		this.tenantRepository = tenantRepository;
		this.orgUnitRepository = orgUnitRepository;
		this.workspaceNodeRepository = workspaceNodeRepository;
		this.workspaceGrantRepository = workspaceGrantRepository;
		this.rankGrantRepository = rankGrantRepository;
		this.authorizationAuditRepository = authorizationAuditRepository;
		this.threadRepository = threadRepository;
		this.objectMapper = objectMapper;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** Flyway가 끝난 뒤 앱이 뜰 때 한 번 실행한다. 잘못된 설정이 서비스 기동을 막아서는 안 되므로 실패는 로그만 남긴다. */
	@EventListener(ApplicationReadyEvent.class)
	public void bootstrapAtStartup() {
		try {
			RbacBootstrapResult result = runCurrentConfiguration();
			if (!result.processedTenants().isEmpty()) {
				log.info("RBAC bootstrap 완료: 처리 {}, 신규 {}, drift {}, 실패 {}", result.processedTenants(),
						result.createdTenants(), result.driftTenants(), result.failures());
			}
		} catch (RbacBootstrapConfigurationException exception) {
			log.error("RBAC bootstrap 설정이 올바르지 않아 아무것도 실행하지 않았다: {}", exception.getProblems());
		} catch (RuntimeException exception) {
			log.error("RBAC bootstrap이 완료되지 못했다", exception);
		}
	}

	/** 현재 마운트된 설정을 한 번 적용한다. 설정이 없으면 아무것도 하지 않는다. PLATFORM_ADMIN 재시도도 이 메서드를 쓴다. */
	public synchronized RbacBootstrapResult runCurrentConfiguration() {
		Optional<RbacBootstrapConfigReader.LoadedBootstrapSpec> loaded = configReader.loadWithFingerprint();
		if (loaded.isEmpty()) return RbacBootstrapResult.empty();
		RbacBootstrapSpec spec = loaded.get().spec();
		List<String> problems = validator.validate(spec);
		if (!problems.isEmpty()) throw new RbacBootstrapConfigurationException(problems);

		Run run = new Run(UUID.randomUUID().toString(), loaded.get().fingerprint(), spec.reconcile());
		List<String> processed = new ArrayList<>();
		List<String> created = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> drifts = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		if (spec.reconcile().enabled() && !spec.reconcile().applies()) {
			warnings.add("reconcile.enabled가 켜졌지만 dpl_id가 없어 차이를 적용하지 않고 drift 판정만 한다");
		}
		for (RbacBootstrapSpec.TenantSpec tenant : spec.tenants()) {
			try {
				TenantOutcome outcome = applyTenant(tenant, run);
				processed.add(tenant.key());
				if (outcome.created()) created.add(tenant.key());
				warnings.addAll(outcome.warnings());
				if (outcome.drifted()) {
					drifts.add(tenant.key());
					failClosedTenants.add(tenant.key());
				} else {
					failClosedTenants.remove(tenant.key());
				}
			} catch (RuntimeException exception) {
				// 한 Tenant의 실패가 다른 Tenant의 처리를 막지 않는다. 확인되지 않은 Tenant는 fail-closed로 둔다.
				failClosedTenants.add(tenant.key());
				// 응답에는 예외 종류만 싣는다. Hibernate 예외 메시지는 SQL·제약 상세를 담을 수 있어 로그에만 남긴다.
				failures.add(tenant.key() + ": " + exception.getClass().getSimpleName());
				log.error("RBAC bootstrap: tenant {} 처리 실패", tenant.key(), exception);
			}
		}
		warnings.forEach(warning -> log.warn("RBAC bootstrap 경고: {}", warning));
		return new RbacBootstrapResult(List.copyOf(processed), List.copyOf(created), List.copyOf(warnings),
				List.copyOf(drifts), List.copyOf(failures));
	}

	/**
	 * bootstrap이 drift로 판정한 Tenant인지 조회한다. 권한 강제(요청 인가에 거는 것)는 다른 이슈에서 도입한다.
	 * <b>주의(그 이슈의 계약)</b>: 이 표시는 메모리에만 있다. 앱이 막 떴고 bootstrap이 끝나기 전에는 모든 Tenant가
	 * 열린 것으로 보이고, 설정이 없거나 깨진 채 재시작하면 이전 drift 표시가 사라진다. 강제 코드는 이 값만 믿지 말고
	 * 판정 결과를 DB에서 읽는 경로(예: 최근 `TENANT_DRIFT_DETECTED` 감사 행)를 함께 두어야 한다.
	 */
	public boolean isTenantFailClosed(String tenantKey) {
		return failClosedTenants.contains(tenantKey);
	}

	private TenantOutcome applyTenant(RbacBootstrapSpec.TenantSpec spec, Run run) {
		// 시작할 때 없던 Tenant를 만들다 unique 충돌이 났고 지금은 있다면, 다른 인스턴스가 동시에 만든 것이다.
		// 그 경우에만 한 번 다시 시도한다(다른 이유의 제약 위반을 동시 생성으로 오인해 되풀이하지 않는다).
		boolean absentAtStart = tenantRepository.findByKey(spec.key()).isEmpty();
		for (int attempt = 1;; attempt++) {
			try {
				return transactions.execute(status -> processTenant(spec, run));
			} catch (DataIntegrityViolationException conflict) {
				if (attempt >= 2 || !absentAtStart || tenantRepository.findByKey(spec.key()).isEmpty()) throw conflict;
				log.info("RBAC bootstrap: tenant {} 동시 생성 충돌, 다시 시도한다", spec.key());
			}
		}
	}

	private TenantOutcome processTenant(RbacBootstrapSpec.TenantSpec spec, Run run) {
		Optional<Tenant> existing = tenantRepository.findByKeyForUpdate(spec.key());
		return existing.isEmpty() ? createTenant(spec, run) : processExistingTenant(existing.get(), spec, run);
	}

	// ---------------------------------------------------------------- 새 Tenant

	private TenantOutcome createTenant(RbacBootstrapSpec.TenantSpec spec, Run run) {
		Tenant tenant = tenantRepository.saveAndFlush(new Tenant(spec.key(), spec.name(), tenantStatus(spec.status())));
		audit(run, tenant, AuthorizationAuditEventKind.TENANT_CREATED, AuthorizationAuditTargetKind.TENANT,
				tenantRef(tenant), null, null, tenantSnapshot(tenant));

		Model model = new Model();
		// ROOT·COMMON은 Tenant 생성과 같은 트랜잭션에서 자동으로 만든다(선언하지 않는다).
		WorkspaceNode root = createNode(run, tenant, model, WorkspaceNode.root(tenant.getId(),
				RbacBootstrapValidator.ROOT_NAME));
		createNode(run, tenant, model, WorkspaceNode.common(tenant.getId(), root.getId(), root.getPath(),
				RbacBootstrapValidator.COMMON_NAME));
		for (RbacBootstrapSpec.OrgUnitSpec unit : spec.orgUnits()) createOrgUnit(run, tenant, model, unit);
		createMissingNodes(run, tenant, spec, model, new ArrayList<>());
		createMissingGrants(run, tenant, spec, model);
		createMissingRankGrants(run, tenant, spec, model);
		return new TenantOutcome(true, false, List.of());
	}

	// ---------------------------------------------------------------- 기존 Tenant

	private TenantOutcome processExistingTenant(Tenant tenant, RbacBootstrapSpec.TenantSpec spec, Run run) {
		// 같은 배포 ID가 이 Tenant에 이미 적용됐는지는 이번 실행이 행을 쓰기 전에 판정한다.
		boolean reconcileNow = run.reconcile().applies()
				&& !authorizationAuditRepository.existsByTenantIdAndDeploymentId(tenant.getId(), run.reconcile().deploymentId());
		Model model = load(tenant);
		for (RbacBootstrapSpec.OrgUnitSpec unit : spec.orgUnits()) {
			if (!model.unitsByKey.containsKey(unit.key())) createOrgUnit(run, tenant, model, unit);
		}
		// 노드는 두 번에 나눠 만든다. reconcile이 비활성 부모를 재활성화하거나 새로 만든 부모 아래로 옮긴 뒤에야
		// 만들 수 있는 노드가 있어서, 첫 번째에서 막힌 것은 버리고 reconcile 뒤에 다시 시도해 남는 것만 blocked로 본다.
		createMissingNodes(run, tenant, spec, model, new ArrayList<>());
		if (reconcileNow) applyReconcile(run, tenant, spec, model);
		List<DriftItem> blocked = new ArrayList<>();
		createMissingNodes(run, tenant, spec, model, blocked);
		createMissingGrants(run, tenant, spec, model);
		createMissingRankGrants(run, tenant, spec, model);

		List<DriftItem> drift = new ArrayList<>(drift(tenant, spec, model));
		drift.addAll(blocked);
		List<String> warnings = nameWarnings(tenant, spec, model);
		if (!drift.isEmpty()) {
			recordDrift(run, tenant, drift);
			warnings.add(spec.key() + ": drift=" + drift.stream().map(DriftItem::summary).toList());
		}
		return new TenantOutcome(false, !drift.isEmpty(), warnings);
	}

	private Model load(Tenant tenant) {
		Model model = new Model();
		for (OrgUnit unit : orgUnitRepository.findByTenantId(tenant.getId())) model.unitsByKey.put(unit.getKey(), unit);
		for (WorkspaceNode node : workspaceNodeRepository.findByTenantId(tenant.getId())) model.putNode(node);
		for (WorkspaceGrant grant : workspaceGrantRepository.findByTenantId(tenant.getId())) {
			model.grantedNodeIds.add(grant.getWorkspaceNodeId());
		}
		// 직급 규칙이 걸린 노드도 팀 부여가 걸린 노드처럼 reconcile이 옮기지 않는다.
		for (RankGrant grant : rankGrantRepository.findByTenantId(tenant.getId())) {
			model.grantedNodeIds.add(grant.getWorkspaceNodeId());
		}
		return model;
	}

	// ---------------------------------------------------------------- 누락 리소스 생성

	private OrgUnit createOrgUnit(Run run, Tenant tenant, Model model, RbacBootstrapSpec.OrgUnitSpec spec) {
		OrgUnit unit = orgUnitRepository.saveAndFlush(new OrgUnit(tenant.getId(), spec.key(), spec.name(),
				orgUnitStatus(spec.status())));
		model.unitsByKey.put(unit.getKey(), unit);
		audit(run, tenant, AuthorizationAuditEventKind.ORG_UNIT_CREATED, AuthorizationAuditTargetKind.ORG_UNIT,
				orgUnitRef(unit), null, null, orgUnitSnapshot(unit));
		return unit;
	}

	private WorkspaceNode createNode(Run run, Tenant tenant, Model model, WorkspaceNode node) {
		WorkspaceNode saved = workspaceNodeRepository.saveAndFlush(node);
		model.putNode(saved);
		audit(run, tenant, AuthorizationAuditEventKind.NODE_CREATED, AuthorizationAuditTargetKind.WORKSPACE,
				nodeRef(saved), saved.getId(), null, nodeSnapshot(saved));
		return saved;
	}

	/**
	 * 선언된 노드 중 없는 것을 부모부터 만든다. 선언된 상태(ACTIVE·INACTIVE)로 바로 만든다. 만들 수 없는 것은 blocked에 남겨
	 * drift로 보고한다: 부모가 아직 없거나, 부모가 비활성이라 그 아래에는 만들 수 없거나(생성의 부모는 ACTIVE여야 한다),
	 * 같은 부모 아래 활성 형제와 이름이 겹치는 경우(설정 밖에서 ADMIN이 만든 노드와 겹칠 수 있다 — 제약 위반으로 Tenant
	 * 전체를 롤백하지 않는다).
	 */
	private void createMissingNodes(Run run, Tenant tenant, RbacBootstrapSpec.TenantSpec spec, Model model,
			List<DriftItem> blocked) {
		List<RbacBootstrapSpec.NodeSpec> pending = spec.nodes().stream()
				.filter(node -> !model.nodesByKey.containsKey(node.key())).toList();
		while (!pending.isEmpty()) {
			List<RbacBootstrapSpec.NodeSpec> next = new ArrayList<>();
			for (RbacBootstrapSpec.NodeSpec node : pending) {
				WorkspaceNode parent = model.nodesByKey.get(node.parent());
				if (parent == null) {
					next.add(node);
					continue;
				}
				if (parent.getStatus() != WorkspaceNodeStatus.ACTIVE) {
					blocked.add(new DriftItem("node", node.key(), "missing", "부모 " + node.parent() + " ACTIVE에서 생성", "부모가 INACTIVE"));
					continue;
				}
				WorkspaceNodeStatus status = workspaceStatus(node.status());
				if (status == WorkspaceNodeStatus.ACTIVE && model.hasActiveSiblingNamed(parent.getId(), node.name(), null)) {
					blocked.add(new DriftItem("node", node.key(), "missing", "이름 " + node.name() + " 으로 생성", "같은 부모 아래 활성 형제와 이름이 겹침"));
					continue;
				}
				createNode(run, tenant, model, WorkspaceNode.child(tenant.getId(), parent.getId(), parent.getPath(),
						node.key(), workspaceKind(node.kind()), node.name(), status));
			}
			if (next.size() == pending.size()) {
				// 부모가 만들어지지 못한 노드다. 부모의 blocked 항목이 이미 원인을 담고 있다.
				next.forEach(node -> blocked.add(new DriftItem("node", node.key(), "missing", "부모 " + node.parent() + " 생성 후 생성", "부모가 없음")));
				break;
			}
			pending = next;
		}
	}

	private void createMissingGrants(Run run, Tenant tenant, RbacBootstrapSpec.TenantSpec spec, Model model) {
		Set<String> existing = new HashSet<>();
		for (WorkspaceGrant grant : workspaceGrantRepository.findByTenantId(tenant.getId())) {
			existing.add(grantKey(grant.getOrgUnitId(), grant.getRole(), grant.getWorkspaceNodeId()));
		}
		for (RbacBootstrapSpec.GrantSpec declared : spec.grants()) {
			OrgUnit unit = model.unitsByKey.get(declared.orgUnit());
			WorkspaceNode node = model.nodesByKey.get(declared.node());
			// 대상이 DB에서 비활성이면 부여를 만들 수 없다. 그 차이는 drift(상태)로 이미 드러난다.
			if (unit == null || node == null || unit.getStatus() != OrgUnitStatus.ACTIVE
					|| node.getStatus() != WorkspaceNodeStatus.ACTIVE) continue;
			WorkspaceRole role = workspaceRole(declared.role());
			if (!existing.add(grantKey(unit.getId(), role, node.getId()))) continue;
			WorkspaceGrant grant = workspaceGrantRepository.saveAndFlush(
					new WorkspaceGrant(tenant.getId(), unit.getId(), node.getId(), role));
			audit(run, tenant, AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					policyRef(grant, unit, node), node.getId(), null, grantSnapshot(grant));
		}
	}

	/** 팀 부여와 같은 규칙이다: 빠진 직급 규칙은 만들고, 설정에서 뺀 규칙은 그대로 둔다(drift가 아니다). */
	private void createMissingRankGrants(Run run, Tenant tenant, RbacBootstrapSpec.TenantSpec spec, Model model) {
		Set<String> existing = new HashSet<>();
		for (RankGrant grant : rankGrantRepository.findByTenantId(tenant.getId())) {
			existing.add(rankGrantKey(grant.getOrgUnitId(), grant.getRank(), grant.getWorkspaceNodeId()));
		}
		for (RbacBootstrapSpec.RankGrantSpec declared : spec.rankGrants()) {
			OrgUnit unit = declared.orgUnit() == null ? null : model.unitsByKey.get(declared.orgUnit());
			WorkspaceNode node = model.nodesByKey.get(declared.node());
			// 대상이 DB에서 비활성이면 규칙을 만들 수 없다. 그 차이는 drift(상태)로 이미 드러난다.
			if (node == null || node.getStatus() != WorkspaceNodeStatus.ACTIVE) continue;
			if (declared.orgUnit() != null && (unit == null || unit.getStatus() != OrgUnitStatus.ACTIVE)) continue;
			Rank rank = Rank.valueOf(declared.rank());
			UUID unitId = unit == null ? null : unit.getId();
			if (!existing.add(rankGrantKey(unitId, rank, node.getId()))) continue;
			RankGrant grant = rankGrantRepository.saveAndFlush(new RankGrant(tenant.getId(), node.getId(), unitId, rank));
			audit(run, tenant, AuthorizationAuditEventKind.POLICY_ADDED, AuthorizationAuditTargetKind.POLICY,
					rankPolicyRef(grant, unit), node.getId(), null, rankGrantSnapshot(grant));
		}
	}

	// ---------------------------------------------------------------- drift

	private List<DriftItem> drift(Tenant tenant, RbacBootstrapSpec.TenantSpec spec, Model model) {
		List<DriftItem> drift = new ArrayList<>();
		if (tenant.getStatus() != tenantStatus(spec.status())) {
			drift.add(new DriftItem("tenant", spec.key(), "status", spec.status(), tenant.getStatus().name()));
		}
		for (RbacBootstrapSpec.OrgUnitSpec declared : spec.orgUnits()) {
			OrgUnit actual = model.unitsByKey.get(declared.key());
			if (actual != null && actual.getStatus() != orgUnitStatus(declared.status())) {
				drift.add(new DriftItem("org_unit", declared.key(), "status", declared.status(), actual.getStatus().name()));
			}
		}
		for (RbacBootstrapSpec.NodeSpec declared : spec.nodes()) {
			WorkspaceNode actual = model.nodesByKey.get(declared.key());
			if (actual == null) continue; // 만들지 못한 노드는 blocked 항목이 따로 담는다.
			if (actual.getKind() != workspaceKind(declared.kind())) {
				drift.add(new DriftItem("node", declared.key(), "kind", declared.kind(), actual.getKind().name()));
			}
			WorkspaceNode declaredParent = model.nodesByKey.get(declared.parent());
			UUID declaredParentId = declaredParent == null ? null : declaredParent.getId();
			if (!java.util.Objects.equals(declaredParentId, actual.getParentId())) {
				WorkspaceNode actualParent = actual.getParentId() == null ? null : model.nodesById.get(actual.getParentId());
				drift.add(new DriftItem("node", declared.key(), "parent", declared.parent(),
						actualParent == null ? "(none)" : actualParent.getKey()));
			}
			if (actual.getStatus() != workspaceStatus(declared.status())) {
				drift.add(new DriftItem("node", declared.key(), "status", declared.status(), actual.getStatus().name()));
			}
		}
		return drift;
	}

	private List<String> nameWarnings(Tenant tenant, RbacBootstrapSpec.TenantSpec spec, Model model) {
		List<String> warnings = new ArrayList<>();
		if (!tenant.getName().equals(spec.name())) warnings.add(spec.key() + ": tenant 표시명이 다르다");
		for (RbacBootstrapSpec.OrgUnitSpec declared : spec.orgUnits()) {
			OrgUnit actual = model.unitsByKey.get(declared.key());
			if (actual != null && !actual.getName().equals(declared.name())) {
				warnings.add(spec.key() + ": org_unit " + declared.key() + " 표시명이 다르다");
			}
		}
		for (RbacBootstrapSpec.NodeSpec declared : spec.nodes()) {
			WorkspaceNode actual = model.nodesByKey.get(declared.key());
			if (actual != null && !actual.getName().equals(declared.name())) {
				warnings.add(spec.key() + ": node " + declared.key() + " 표시명이 다르다");
			}
		}
		return warnings;
	}

	/** 같은 설정으로 시작할 때마다 같은 drift 행이 쌓이지 않도록, 이 Tenant의 직전 drift 행이 같은 내용이면 다시 적지 않는다. */
	private void recordDrift(Run run, Tenant tenant, List<DriftItem> drift) {
		JsonNode after = objectMapper.valueToTree(driftPayload(drift));
		Optional<AuthorizationAudit> latest = authorizationAuditRepository
				.findFirstByTenantIdAndEventKindOrderByCreatedAtDescIdDesc(tenant.getId(),
						AuthorizationAuditEventKind.TENANT_DRIFT_DETECTED);
		if (latest.isPresent()
				&& run.fingerprint().equals(latest.get().getConfigurationFingerprint())
				&& after.equals(objectMapper.readTree(latest.get().getAfterJson()))) {
			return;
		}
		audit(run, tenant, AuthorizationAuditEventKind.TENANT_DRIFT_DETECTED, AuthorizationAuditTargetKind.TENANT,
				tenantRef(tenant), null, null, driftPayload(drift));
	}

	private Map<String, Object> driftPayload(List<DriftItem> drift) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("drift", drift.stream().map(DriftItem::asMap).toList());
		return payload;
	}

	// ---------------------------------------------------------------- reconcile

	/**
	 * 승인된 배포 reconcile: 선언과 다른 권한 구조·표시명을 선언대로 맞춘다. 노드 규칙(0001 6.4)에 막히는 변경은 적용하지 않고
	 * 건너뛴다 — 건너뛴 차이는 이후 drift 계산에 그대로 남아 그 Tenant가 fail-closed로 남는다.
	 */
	private void applyReconcile(Run run, Tenant tenant, RbacBootstrapSpec.TenantSpec spec, Model model) {
		if (!tenant.getName().equals(spec.name())) {
			Map<String, Object> before = tenantSnapshot(tenant);
			tenant.rename(spec.name());
			tenantRepository.saveAndFlush(tenant);
			audit(run, tenant, AuthorizationAuditEventKind.TENANT_RENAMED, AuthorizationAuditTargetKind.TENANT,
					tenantRef(tenant), null, before, tenantSnapshot(tenant));
		}
		TenantStatus declaredTenantStatus = tenantStatus(spec.status());
		if (tenant.getStatus() != declaredTenantStatus) {
			Map<String, Object> before = tenantSnapshot(tenant);
			tenant.reconcileStatus(declaredTenantStatus);
			tenantRepository.saveAndFlush(tenant);
			audit(run, tenant, declaredTenantStatus == TenantStatus.INACTIVE
					? AuthorizationAuditEventKind.TENANT_DEACTIVATED : AuthorizationAuditEventKind.TENANT_REACTIVATED,
					AuthorizationAuditTargetKind.TENANT, tenantRef(tenant), null, before, tenantSnapshot(tenant));
		}
		for (RbacBootstrapSpec.OrgUnitSpec declared : spec.orgUnits()) reconcileOrgUnit(run, tenant, model, declared);
		reconcileNodes(run, tenant, spec, model);
	}

	private void reconcileOrgUnit(Run run, Tenant tenant, Model model, RbacBootstrapSpec.OrgUnitSpec declared) {
		OrgUnit unit = model.unitsByKey.get(declared.key());
		if (unit == null) return;
		if (!unit.getName().equals(declared.name())) {
			Map<String, Object> before = orgUnitSnapshot(unit);
			unit.rename(declared.name());
			orgUnitRepository.saveAndFlush(unit);
			audit(run, tenant, AuthorizationAuditEventKind.ORG_UNIT_RENAMED, AuthorizationAuditTargetKind.ORG_UNIT,
					orgUnitRef(unit), null, before, orgUnitSnapshot(unit));
		}
		OrgUnitStatus status = orgUnitStatus(declared.status());
		if (unit.getStatus() != status) {
			Map<String, Object> before = orgUnitSnapshot(unit);
			unit.reconcileStatus(status);
			orgUnitRepository.saveAndFlush(unit);
			audit(run, tenant, status == OrgUnitStatus.INACTIVE ? AuthorizationAuditEventKind.ORG_UNIT_DEACTIVATED
					: AuthorizationAuditEventKind.ORG_UNIT_REACTIVATED, AuthorizationAuditTargetKind.ORG_UNIT,
					orgUnitRef(unit), null, before, orgUnitSnapshot(unit));
		}
	}

	/**
	 * 노드 reconcile. 한 번의 실행으로 수렴하도록 순서를 정한다: 표시명 → 부모 이동 → 재활성화 → 비활성화.
	 * 이름을 먼저 맞춰야 재활성화의 형제 이름 충돌 검사가 새 이름 기준이 되고, 부모 이동이 재활성화보다 앞서야
	 * 옛 부모가 꺼져 있어도 새 부모 아래에서 되살릴 수 있다. 각 단계는 선언 트리 기준으로 부모부터 처리하고
	 * (비활성화만 자식부터), 규칙에 막히는 변경은 건너뛰어 drift로 남긴다.
	 */
	private void reconcileNodes(Run run, Tenant tenant, RbacBootstrapSpec.TenantSpec spec, Model model) {
		Map<String, RbacBootstrapSpec.NodeSpec> declaredByKey = new HashMap<>();
		spec.nodes().forEach(node -> declaredByKey.put(node.key(), node));
		List<RbacBootstrapSpec.NodeSpec> topDown = new ArrayList<>(spec.nodes());
		topDown.sort(Comparator.comparingInt(node -> declaredDepth(node, declaredByKey)));

		// 1) 표시명은 활성 형제와 이름이 겹치지 않을 때만 맞춘다(비활성 노드는 형제 unique 대상이 아니라 바로 맞춘다).
		for (RbacBootstrapSpec.NodeSpec node : topDown) {
			WorkspaceNode actual = model.nodesByKey.get(node.key());
			if (actual == null || actual.getName().equals(node.name())) continue;
			if (actual.getStatus() == WorkspaceNodeStatus.ACTIVE
					&& model.hasActiveSiblingNamed(actual.getParentId(), node.name(), actual.getId())) continue;
			Map<String, Object> before = nodeSnapshot(actual);
			actual.rename(node.name());
			workspaceNodeRepository.saveAndFlush(actual);
			audit(run, tenant, AuthorizationAuditEventKind.NODE_RENAMED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(actual), actual.getId(), before, nodeSnapshot(actual));
		}

		// 2) reparent는 자식·부여·Thread가 없는 leaf만, 활성 부모 아래로, 깊이 상한을 지킬 때만 한다.
		for (RbacBootstrapSpec.NodeSpec node : topDown) {
			WorkspaceNode actual = model.nodesByKey.get(node.key());
			WorkspaceNode newParent = model.nodesByKey.get(node.parent());
			if (actual == null || newParent == null || newParent.getId().equals(actual.getParentId())
					|| actual.getKind() != workspaceKind(node.kind())) continue;
			if (!canReparent(actual, newParent, model)) continue;
			Map<String, Object> before = nodeSnapshot(actual);
			actual.moveTo(newParent.getId(), newParent.getPath());
			workspaceNodeRepository.saveAndFlush(actual);
			audit(run, tenant, AuthorizationAuditEventKind.NODE_REPARENTED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(actual), actual.getId(), before, nodeSnapshot(actual));
		}

		// 3) 재활성화는 부모부터. ACTIVE 부모 아래의 대상 노드만 되살리고 하위 노드는 자동으로 살리지 않는다.
		for (RbacBootstrapSpec.NodeSpec node : topDown) {
			WorkspaceNode actual = model.nodesByKey.get(node.key());
			if (actual == null || !node.status().equals("ACTIVE") || actual.getStatus() != WorkspaceNodeStatus.INACTIVE) continue;
			WorkspaceNode parent = actual.getParentId() == null ? null : model.nodesById.get(actual.getParentId());
			if (parent == null || parent.getStatus() != WorkspaceNodeStatus.ACTIVE
					|| model.hasActiveSiblingNamed(actual.getParentId(), actual.getName(), actual.getId())) continue;
			Map<String, Object> before = nodeSnapshot(actual);
			actual.reconcileStatus(WorkspaceNodeStatus.ACTIVE);
			workspaceNodeRepository.saveAndFlush(actual);
			audit(run, tenant, AuthorizationAuditEventKind.NODE_REACTIVATED, AuthorizationAuditTargetKind.WORKSPACE,
					nodeRef(actual), actual.getId(), before, nodeSnapshot(actual));
		}

		// 4) 비활성화는 자식부터. 활성 하위 노드(선언 여부와 무관)나 Thread가 남아 있는 노드는 끄지 않는다 —
		//    설정 밖에서 만든 노드를 자동으로 바꾸지 않고, 개편은 Thread를 먼저 옮긴 뒤 비활성화하기 때문이다.
		for (int index = topDown.size() - 1; index >= 0; index--) {
			RbacBootstrapSpec.NodeSpec node = topDown.get(index);
			WorkspaceNode actual = model.nodesByKey.get(node.key());
			if (actual == null || !node.status().equals("INACTIVE") || actual.getStatus() != WorkspaceNodeStatus.ACTIVE) continue;
			if (model.activeSubtree(actual).size() > 1 || threadRepository.existsByWorkspaceNodeId(actual.getId())) continue;
			deactivateNode(run, tenant, actual);
		}
	}

	/**
	 * 선언 트리 기준 깊이(root 직속이 1). 설정 검증이 순환을 먼저 거부하지만, 검증을 건너뛴 경로로 불리더라도
	 * 무한 루프가 되지 않도록 지나온 key를 기억한다.
	 */
	private int declaredDepth(RbacBootstrapSpec.NodeSpec node, Map<String, RbacBootstrapSpec.NodeSpec> declaredByKey) {
		int depth = 1;
		Set<String> seen = new HashSet<>();
		seen.add(node.key());
		RbacBootstrapSpec.NodeSpec current = node;
		while (!current.parent().equals("root")) {
			current = declaredByKey.get(current.parent());
			if (current == null || !seen.add(current.key())) break;
			depth++;
		}
		return depth;
	}

	private boolean canReparent(WorkspaceNode node, WorkspaceNode newParent, Model model) {
		return newParent.getStatus() == WorkspaceNodeStatus.ACTIVE
				&& newParent.getKind() != WorkspaceNodeKind.COMMON
				&& newParent.getPath().length + 1 <= MAX_PATH_LENGTH
				&& !model.hasChildren(node)
				&& !model.grantedNodeIds.contains(node.getId())
				&& !threadRepository.existsByWorkspaceNodeId(node.getId())
				&& (node.getStatus() != WorkspaceNodeStatus.ACTIVE
						|| !model.hasActiveSiblingNamed(newParent.getId(), node.getName(), node.getId()));
	}

	private void deactivateNode(Run run, Tenant tenant, WorkspaceNode node) {
		Map<String, Object> before = nodeSnapshot(node);
		node.reconcileStatus(WorkspaceNodeStatus.INACTIVE);
		workspaceNodeRepository.saveAndFlush(node);
		audit(run, tenant, AuthorizationAuditEventKind.NODE_DEACTIVATED, AuthorizationAuditTargetKind.WORKSPACE,
				nodeRef(node), node.getId(), before, nodeSnapshot(node));
	}

	// ---------------------------------------------------------------- 감사 기록

	/** SYSTEM 행위자 기록. `dpl_id`는 reconcile이 켜진 실행에서만 채운다(꺼진 실행이 남기면 이후 같은 배포 ID의 reconcile이 막힌다). */
	private void audit(Run run, Tenant tenant, AuthorizationAuditEventKind event, AuthorizationAuditTargetKind target,
			Map<String, Object> targetRef, UUID workspaceNodeId, Map<String, Object> before, Map<String, Object> after) {
		authorizationAuditRepository.save(new AuthorizationAudit(tenant.getId(), event, target, json(targetRef),
				workspaceNodeId, before == null ? null : json(before), after == null ? null : json(after),
				run.requestId(), run.deploymentIdOrNull(), run.fingerprint()));
	}

	private String json(Object value) {
		return objectMapper.writeValueAsString(value);
	}

	private Map<String, Object> tenantRef(Tenant tenant) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("tnn_key", tenant.getKey());
		return ref;
	}

	private Map<String, Object> orgUnitRef(OrgUnit unit) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("org_unit_key", unit.getKey());
		return ref;
	}

	private Map<String, Object> nodeRef(WorkspaceNode node) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("wrk_node_id", node.getId());
		ref.put("node_key", node.getKey());
		return ref;
	}

	private Map<String, Object> policyRef(WorkspaceGrant grant, OrgUnit unit, WorkspaceNode node) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("wrk_grn_id", grant.getId());
		ref.put("org_unit_key", unit.getKey());
		ref.put("role", grant.getRole().name());
		ref.put("wrk_node_id", node.getId());
		return ref;
	}

	private Map<String, Object> rankPolicyRef(RankGrant grant, OrgUnit unit) {
		Map<String, Object> ref = new LinkedHashMap<>();
		ref.put("rank_grn_id", grant.getId());
		ref.put("org_unit_key", unit == null ? null : unit.getKey());
		ref.put("rank", grant.getRank().name());
		ref.put("wrk_node_id", grant.getWorkspaceNodeId());
		return ref;
	}

	private Map<String, Object> tenantSnapshot(Tenant tenant) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", tenant.getId());
		snapshot.put("tnn_key", tenant.getKey());
		snapshot.put("name", tenant.getName());
		snapshot.put("status", tenant.getStatus().name());
		snapshot.put("inactive_at", tenant.getInactiveAt());
		return snapshot;
	}

	private Map<String, Object> orgUnitSnapshot(OrgUnit unit) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", unit.getId());
		snapshot.put("org_unit_key", unit.getKey());
		snapshot.put("name", unit.getName());
		snapshot.put("status", unit.getStatus().name());
		snapshot.put("inactive_at", unit.getInactiveAt());
		return snapshot;
	}

	private Map<String, Object> nodeSnapshot(WorkspaceNode node) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", node.getId());
		snapshot.put("node_key", node.getKey());
		snapshot.put("kind", node.getKind().name());
		snapshot.put("prn_id", node.getParentId());
		snapshot.put("name", node.getName());
		snapshot.put("status", node.getStatus().name());
		snapshot.put("path", java.util.Arrays.stream(node.getPath()).map(UUID::toString).toList());
		snapshot.put("inactive_at", node.getInactiveAt());
		return snapshot;
	}

	private Map<String, Object> grantSnapshot(WorkspaceGrant grant) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", grant.getId());
		snapshot.put("tnn_id", grant.getTenantId());
		snapshot.put("org_unit_id", grant.getOrgUnitId());
		snapshot.put("role", grant.getRole().name());
		snapshot.put("wrk_node_id", grant.getWorkspaceNodeId());
		return snapshot;
	}

	private Map<String, Object> rankGrantSnapshot(RankGrant grant) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", grant.getId());
		snapshot.put("tnn_id", grant.getTenantId());
		snapshot.put("org_unit_id", grant.getOrgUnitId());
		snapshot.put("rank", grant.getRank().name());
		snapshot.put("wrk_node_id", grant.getWorkspaceNodeId());
		return snapshot;
	}

	// ---------------------------------------------------------------- 변환

	private String grantKey(UUID orgUnitId, WorkspaceRole role, UUID nodeId) {
		return orgUnitId + "|" + role + "|" + nodeId;
	}

	private String rankGrantKey(UUID orgUnitId, Rank rank, UUID nodeId) {
		return orgUnitId + "|" + rank + "|" + nodeId;
	}

	private TenantStatus tenantStatus(String value) { return TenantStatus.valueOf(value); }
	private OrgUnitStatus orgUnitStatus(String value) { return OrgUnitStatus.valueOf(value); }
	private WorkspaceNodeKind workspaceKind(String value) { return WorkspaceNodeKind.valueOf(value); }
	private WorkspaceNodeStatus workspaceStatus(String value) { return WorkspaceNodeStatus.valueOf(value); }
	private WorkspaceRole workspaceRole(String value) { return WorkspaceRole.valueOf(value); }

	// ---------------------------------------------------------------- 내부 값

	/** 한 번의 bootstrap 실행. 모든 감사 행이 같은 requestId를 갖는다. */
	private record Run(String requestId, String fingerprint, RbacBootstrapSpec.Reconcile reconcile) {

		String deploymentIdOrNull() {
			return reconcile.applies() ? reconcile.deploymentId() : null;
		}
	}

	private record TenantOutcome(boolean created, boolean drifted, List<String> warnings) {
	}

	private record DriftItem(String resource, String key, String field, String declared, String actual) {

		String summary() {
			return resource + ":" + key + "." + field;
		}

		Map<String, Object> asMap() {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("resource", resource);
			value.put("key", key);
			value.put("field", field);
			value.put("declared", declared);
			value.put("actual", actual);
			return value;
		}
	}

	/** 한 Tenant의 현재 DB 상태를 메모리에 올린 것. reconcile이 규칙 검사를 DB 왕복 없이 하게 한다. */
	private static final class Model {
		private final Map<String, OrgUnit> unitsByKey = new HashMap<>();
		private final Map<String, WorkspaceNode> nodesByKey = new HashMap<>();
		private final Map<UUID, WorkspaceNode> nodesById = new HashMap<>();
		/** 부여가 걸린 노드(부여가 있는 노드는 reparent할 수 없다). reconcile 전에 한 번 읽는다. */
		private final Set<UUID> grantedNodeIds = new HashSet<>();

		void putNode(WorkspaceNode node) {
			nodesByKey.put(node.getKey(), node);
			nodesById.put(node.getId(), node);
		}

		boolean hasChildren(WorkspaceNode node) {
			return nodesById.values().stream().anyMatch(other -> node.getId().equals(other.getParentId()));
		}

		/** 같은 부모 아래 이름이 겹치는 활성 형제가 있는지(대소문자 무시, 종류 무관). excludeId는 검사에서 뺀다. */
		boolean hasActiveSiblingNamed(UUID parentId, String name, UUID excludeId) {
			String normalized = name.toLowerCase(Locale.ROOT);
			return nodesById.values().stream().anyMatch(other -> !other.getId().equals(excludeId)
					&& other.getStatus() == WorkspaceNodeStatus.ACTIVE
					&& java.util.Objects.equals(other.getParentId(), parentId)
					&& other.getName().toLowerCase(Locale.ROOT).equals(normalized));
		}

		/** node와 그 활성 하위 노드(path에 node가 들어 있는 노드). */
		List<WorkspaceNode> activeSubtree(WorkspaceNode node) {
			return nodesById.values().stream()
					.filter(other -> other.getStatus() == WorkspaceNodeStatus.ACTIVE)
					.filter(other -> java.util.Arrays.asList(other.getPath()).contains(node.getId()))
					.toList();
		}
	}
}
