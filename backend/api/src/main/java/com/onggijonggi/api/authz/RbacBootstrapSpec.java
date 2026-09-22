package com.onggijonggi.api.authz;

import java.util.List;

/**
 * Class Name : RbacBootstrapSpec.java
 * Description : 배포 환경에 마운트한 bootstrap YAML을 해석한 선언이다. ROOT·COMMON은 Tenant 생성 때 자동으로 만들어지므로
 *               선언하지 않는다. 노드의 parent는 부모 node_key이고 root면 최상위 노드다.
 *               rank_grants는 직급 서열 규칙(rank_grn)이다. org_unit을 비우면 모든 팀에 적용된다.
 */
public record RbacBootstrapSpec(Reconcile reconcile, List<TenantSpec> tenants) {

	/** reconcile은 enabled와 배포 ID가 모두 있을 때만 선언 차이를 적용한다. */
	public record Reconcile(boolean enabled, String deploymentId) {

		public boolean applies() {
			return enabled && deploymentId != null && !deploymentId.isBlank();
		}
	}

	public record TenantSpec(String key, String name, String status, List<OrgUnitSpec> orgUnits,
			List<NodeSpec> nodes, List<GrantSpec> grants, List<RankGrantSpec> rankGrants) {

		/** 직급 규칙이 없는 선언. */
		public TenantSpec(String key, String name, String status, List<OrgUnitSpec> orgUnits,
				List<NodeSpec> nodes, List<GrantSpec> grants) {
			this(key, name, status, orgUnits, nodes, grants, List.of());
		}
	}

	public record OrgUnitSpec(String key, String name, String status) {
	}

	public record NodeSpec(String key, String kind, String parent, String name, String status) {
	}

	public record GrantSpec(String orgUnit, String role, String node) {
	}

	/** orgUnit은 null일 수 있다(모든 팀). rank는 Rank 코드(TL·B·C·K·D·S)이고 "이 직급 이상"을 뜻한다. */
	public record RankGrantSpec(String orgUnit, String rank, String node) {
	}
}
