package com.onggijonggi.api.authz;

import java.util.List;

/**
 * Class Name : RbacBootstrapResult.java
 * Description : bootstrap 한 번(기동 또는 PLATFORM_ADMIN 재시도)의 결과다. drift Tenant는 fail-closed 상태이고,
 *               경고(표시명 차이 등)와 실패한 Tenant의 사유를 함께 돌려준다.
 */
public record RbacBootstrapResult(List<String> processedTenants, List<String> createdTenants,
		List<String> warnings, List<String> driftTenants, List<String> failures) {

	public static RbacBootstrapResult empty() {
		return new RbacBootstrapResult(List.of(), List.of(), List.of(), List.of(), List.of());
	}
}
