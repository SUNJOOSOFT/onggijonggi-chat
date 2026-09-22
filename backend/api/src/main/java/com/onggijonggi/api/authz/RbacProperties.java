package com.onggijonggi.api.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Class Name : RbacProperties.java
 * Description : v0.3 권한 강제 스위치(app.rbac.enforce)를 정의한다. 기본 false는 절체 전 상태이며 지금과 똑같이 동작한다.
 *               이 PR은 값을 정의만 하고 읽어서 동작을 바꾸는 코드는 만들지 않는다 — 권한 강제 코드는 다른 RBAC 이슈가
 *               이 스위치 뒤에 둔다. 절체 PR이 이 스위치를 삭제한다. bootstrap·/api/platform/**·절체 사전 검증은
 *               이 값과 무관하게 동작한다.
 */
@Component
@ConfigurationProperties(prefix = "app.rbac")
public class RbacProperties {

	private boolean enforce;

	public boolean isEnforce() {
		return enforce;
	}

	public void setEnforce(boolean enforce) {
		this.enforce = enforce;
	}
}
