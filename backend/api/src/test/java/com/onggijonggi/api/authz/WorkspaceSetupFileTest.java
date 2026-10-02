package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.authz.RbacBootstrapSpec.TenantSpec;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : WorkspaceSetupFileTest.java
 * Description : 저장소의 infra/config/workspace-setup.yml이 bootstrap 검증을 통과하는지 확인한다. casbin 프로필을 켜면
 *               bff가 기동 때 이 파일을 읽는데, 규칙을 하나라도 어기면 bootstrap 전체가 실행되지 않는다. 사람 속성 파일
 *               infra/config/members/members.csv도 이 조직 구조로 검증한다 — 틀리면 적재되지 않아 판정이 모두 거부된다.
 *               Gradle은 테스트를 backend/api에서 돌리므로 저장소 루트는 두 단계 위다.
 */
class WorkspaceSetupFileTest {

	private static final Path FILE = Path.of("..", "..", "infra", "config", "workspace-setup.yml");
	private static final Path MEMBERS = Path.of("..", "..", "infra", "config", "members", "members.csv");

	@Test
	void theRepositorySetupFilePassesBootstrapValidation() {
		assertThat(Files.isRegularFile(FILE)).as("%s", FILE.toAbsolutePath()).isTrue();

		RbacBootstrapSpec spec = new RbacBootstrapConfigReader(FILE.toString(), new JsonMapper())
				.loadWithFingerprint().orElseThrow().spec();

		assertThat(new RbacBootstrapValidator().validate(spec)).isEmpty();
		TenantSpec tenant = spec.tenants().get(0);
		assertThat(tenant.orgUnits()).extracting(RbacBootstrapSpec.OrgUnitSpec::key).containsExactly("hr", "legal", "fin", "free");
		assertThat(tenant.rankGrants()).extracting(RbacBootstrapSpec.RankGrantSpec::node)
				.containsExactly("hr-lead", "legal-lead", "fin-lead", "leaders", "notice");
	}

	@Test
	void theRepositoryMembersFileMatchesTheSetupFile() {
		RbacBootstrapSpec spec = new RbacBootstrapConfigReader(FILE.toString(), new JsonMapper())
				.loadWithFingerprint().orElseThrow().spec();
		UUID tenant = UUID.randomUUID();
		OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
		when(orgUnits.findAll()).thenReturn(spec.tenants().get(0).orgUnits().stream()
				.map(unit -> new OrgUnit(tenant, unit.key(), unit.name(), OrgUnitStatus.valueOf(unit.status()))).toList());
		KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
		when(keycloak.users()).thenReturn(Mono.just(List.of()));

		// 형식·팀·직급이 틀리면 MemberAttributeSourceException이다. 계정이 없는 Keycloak이라 모든 줄이 건너뛰어진다.
		MemberAttributeSource.Load load = new FileMemberAttributeSource(MEMBERS.toString(), orgUnits, keycloak).load();

		assertThat(load.skipped()).containsExactly("demo1", "demo2", "demo3", "demo4", "demo5", "demo6", "appuser");
	}
}
