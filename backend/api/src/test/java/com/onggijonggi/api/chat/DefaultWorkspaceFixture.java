package com.onggijonggi.api.chat;

import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Class Name : DefaultWorkspaceFixture.java
 * Description : 권한 기능을 켜지 않은 기본 배포처럼, 새 대화를 놓을 유일한 ACTIVE Tenant와 그 ROOT·COMMON을 H2 테스트 DB에 심는다.
 *               모든 Thread는 Tenant·워크스페이스에 놓여야 하므로(절체 뒤 NOT NULL) 판정이 꺼진 상태에서 대화를 만드는 테스트가
 *               쓴다. 이미 Tenant가 있으면 아무것도 하지 않는다. Tenant를 직접 만드는 테스트에는 import하지 않는다.
 */
@TestConfiguration
class DefaultWorkspaceFixture {

	@Bean
	ApplicationRunner seedDefaultWorkspace(TenantRepository tenants, WorkspaceNodeRepository nodes) {
		return args -> {
			if (tenants.count() > 0) return;
			Tenant tenant = tenants.saveAndFlush(new Tenant("default", "기본", TenantStatus.ACTIVE));
			WorkspaceNode root = nodes.saveAndFlush(WorkspaceNode.root(tenant.getId(), "기본"));
			nodes.saveAndFlush(WorkspaceNode.common(tenant.getId(), root.getId(), root.getPath(), "공용"));
		};
	}
}
