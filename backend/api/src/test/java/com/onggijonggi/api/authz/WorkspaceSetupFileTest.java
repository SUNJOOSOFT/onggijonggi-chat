package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.authz.RbacBootstrapSpec.TenantSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : WorkspaceSetupFileTest.java
 * Description : 저장소의 infra/config/workspace-setup.yml이 bootstrap 검증을 통과하는지 확인한다. casbin 프로필을 켜면
 *               bff가 기동 때 이 파일을 읽는데, 규칙을 하나라도 어기면 bootstrap 전체가 실행되지 않는다.
 *               Gradle은 테스트를 backend/api에서 돌리므로 저장소 루트는 두 단계 위다.
 */
class WorkspaceSetupFileTest {

	private static final Path FILE = Path.of("..", "..", "infra", "config", "workspace-setup.yml");

	@Test
	void theRepositorySetupFilePassesBootstrapValidation() {
		assertThat(Files.isRegularFile(FILE)).as("%s", FILE.toAbsolutePath()).isTrue();

		RbacBootstrapSpec spec = new RbacBootstrapConfigReader(FILE.toString(), new JsonMapper())
				.loadWithFingerprint().orElseThrow().spec();

		assertThat(new RbacBootstrapValidator().validate(spec)).isEmpty();
		TenantSpec tenant = spec.tenants().get(0);
		assertThat(tenant.orgUnits()).extracting(RbacBootstrapSpec.OrgUnitSpec::key).containsExactly("hr", "legal", "fin");
		assertThat(tenant.rankGrants()).extracting(RbacBootstrapSpec.RankGrantSpec::node)
				.containsExactly("hr-lead", "legal-lead", "fin-lead", "leaders", "notice");
	}
}
