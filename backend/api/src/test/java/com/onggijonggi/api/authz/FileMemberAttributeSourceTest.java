package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient.KeycloakUser;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Class Name : FileMemberAttributeSourceTest.java
 * Description : 속성 파일(username,team,rank)을 검증하고 아이디를 Keycloak subject로 매핑하는지 확인한다. 틀린 줄은 모두 모아
 *               전체를 거부하고, Keycloak에 없거나 꺼진 계정의 줄만 건너뛴다. 아이디는 정확히 같은 username에만 맞춘다.
 */
class FileMemberAttributeSourceTest {

	private final UUID tenant = UUID.randomUUID();
	private final OrgUnit hr = new OrgUnit(tenant, "hr", "인사팀", OrgUnitStatus.ACTIVE);
	private final OrgUnit fin = new OrgUnit(tenant, "fin", "재무팀", OrgUnitStatus.ACTIVE);
	private final OrgUnit idle = new OrgUnit(tenant, "idle", "휴면팀", OrgUnitStatus.INACTIVE);
	private final OrgUnit sharedA = new OrgUnit(tenant, "shared", "공용A", OrgUnitStatus.ACTIVE);
	private final OrgUnit sharedB = new OrgUnit(UUID.randomUUID(), "shared", "공용B", OrgUnitStatus.ACTIVE);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);

	@TempDir
	Path dir;
	private Path file;

	@BeforeEach
	void setUp() {
		file = dir.resolve("members.csv");
		when(orgUnits.findAll()).thenReturn(List.of(hr, fin, idle, sharedA, sharedB));
		when(keycloak.users()).thenReturn(Mono.just(List.of(
				new KeycloakUser("sub-demo1", "demo1", true),
				new KeycloakUser("sub-demo10", "demo10", true),
				new KeycloakUser("sub-off", "off", false))));
	}

	@Test
	void eachUsernameIsMappedToTheExactKeycloakAccount() throws IOException {
		write("username,team,rank", "Demo1,hr,tl", "demo10,fin,S");

		MemberAttributeSource.Load load = source().load();

		// 소문자로 맞추고, demo1이 demo10에 걸리지 않는다.
		assertThat(load.members()).containsExactly(
				new MemberAttribute("sub-demo1", tenant, hr.getId(), Rank.TL),
				new MemberAttribute("sub-demo10", tenant, fin.getId(), Rank.S));
		assertThat(load.skipped()).isEmpty();
		assertThat(load.fingerprint()).hasSize(12);
	}

	@Test
	void accountsMissingFromKeycloakOrDisabledAreSkippedNotFatal() throws IOException {
		write("username,team,rank", "demo1,hr,K", "ghost,hr,K", "off,fin,S");

		MemberAttributeSource.Load load = source().load();

		assertThat(load.members()).extracting(MemberAttribute::subject).containsExactly("sub-demo1");
		assertThat(load.skipped()).containsExactly("ghost", "off");
	}

	@Test
	void everyBadLineIsReportedAndNothingIsLoaded() throws IOException {
		write("username,team,rank",
				"demo1,hr,K",
				"demo1,fin,S",
				",hr,K",
				"a,nowhere,K",
				"b,idle,K",
				"c,shared,K",
				"d,hr,X",
				"e,hr");

		assertThatThrownBy(() -> source().load())
				.isInstanceOfSatisfying(MemberAttributeSourceException.class, error -> assertThat(error.getProblems())
						.containsExactly(
								"3번째 줄: 같은 아이디가 2번째 줄에도 있다: demo1",
								"4번째 줄: 아이디가 비어 있다",
								"5번째 줄: 없는 팀이다: nowhere",
								"6번째 줄: 비활성 팀이다: idle",
								"7번째 줄: 같은 팀 코드가 여러 고객사에 있어 고를 수 없다: shared",
								"8번째 줄: 없는 직급이다(TL·B·C·K·D·S): X",
								"9번째 줄: 칸이 3개(username,team,rank)여야 한다"));
		verifyNoInteractions(keycloak);
	}

	@Test
	void theHeaderMustBeUsernameTeamRank() throws IOException {
		write("email,team,rank", "demo1@example.com,hr,K");

		assertThatThrownBy(() -> source().load()).isInstanceOf(MemberAttributeSourceException.class)
				.hasMessageContaining("username,team,rank");
	}

	@Test
	void aKeycloakFailureFailsTheWholeLoad() throws IOException {
		write("username,team,rank", "demo1,hr,K");
		when(keycloak.users()).thenReturn(Mono.error(new IllegalStateException("keycloak down")));

		// 장애를 "없는 계정"으로 보면 멀쩡한 사람을 건너뛰어 권한을 잃게 한다.
		assertThatThrownBy(() -> source().load()).hasMessageContaining("keycloak down");
	}

	@Test
	void aMissingFileOrNoPathMeansNobodyHasAttributes() {
		assertThat(source().load().members()).isEmpty();
		assertThat(new FileMemberAttributeSource("", orgUnits, keycloak).load().members()).isEmpty();
		verifyNoInteractions(keycloak);
	}

	private FileMemberAttributeSource source() {
		return new FileMemberAttributeSource(file.toString(), orgUnits, keycloak);
	}

	private void write(String... lines) throws IOException {
		Files.writeString(file, String.join("\n", lines) + "\n");
	}
}
