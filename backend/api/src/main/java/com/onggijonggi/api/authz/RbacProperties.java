package com.onggijonggi.api.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Class Name : RbacProperties.java
 * Description : v0.3 권한 강제 스위치(app.rbac.enforce). 기본값은 false지만, 운영 DB에 불변 절체 완료 표지가
 *               기록된 뒤에는 CutoverMarkerGuard가 false 기동을 거부한다. bootstrap·/api/platform/**·절체 사전
 *               검증은 스위치와 무관하게 동작한다.
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
