package com.onggijonggi.api.authz;

import java.util.List;

/**
 * Class Name : RbacBootstrapSpec.java
 * Description : 배포 환경에 마운트한 bootstrap YAML을 해석한 선언이다. ROOT·COMMON은 Tenant 생성 때 자동으로 만들어지므로
 *               선언하지 않는다. 노드의 parent는 부모 node_key이고 root면 최상위 노드다.
 */
public record RbacBootstrapSpec(Reconcile reconcile, List<TenantSpec> tenants) {

	/** reconcile은 enabled와 배포 ID가 모두 있을 때만 선언 차이를 적용한다. */
	public record Reconcile(boolean enabled, String deploymentId) {

		public boolean applies() {
			return enabled && deploymentId != null && !deploymentId.isBlank();
		}
	}

	public record TenantSpec(String key, String name, String status, List<OrgUnitSpec> orgUnits,
			List<NodeSpec> nodes, List<GrantSpec> grants) {
	}

	public record OrgUnitSpec(String key, String name, String status) {
	}

	public record NodeSpec(String key, String kind, String parent, String name, String status) {
	}

	public record GrantSpec(String orgUnit, String role, String node) {
	}
}
