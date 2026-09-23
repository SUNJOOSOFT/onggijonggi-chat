package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.RankGrant;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.WorkspaceGrant;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import com.onggijonggi.common.authz.WorkspaceRole;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : CasbinServerContainerTest.java
 * Description : infra/docker-compose.yml과 같은 casbin-server 이미지에 규칙을 넣고 판정한다. model.conf·정책 식·JSON 요청
 *               방식이 실제 서버에서 맞물리는지, 서버 재시작으로 규칙을 잃었을 때 다시 넣고 복구하는지 확인한다.
 *               재시작 뒤에도 같은 주소로 닿아야 해서 호스트 포트를 고정한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class CasbinServerContainerTest {

	private static final int HOST_PORT = freePort();

	@Container
	@SuppressWarnings("deprecation")
	static final FixedHostPortGenericContainer<?> CASBIN = new FixedHostPortGenericContainer<>("casbin/casbin-server:v1.19.0")
			.withFixedExposedPort(HOST_PORT, 50051)
			.waitingFor(Wait.forListeningPort());

	private static final UUID TENANT = UUID.randomUUID();
	private static final UUID HR = UUID.randomUUID();
	private static final UUID FIN = UUID.randomUUID();
	private static final UUID HR_NODE = UUID.randomUUID();
	private static final UUID FIN_NODE = UUID.randomUUID();
	private static final UUID EXEC_NODE = UUID.randomUUID();
	private static final UUID HR_LEAD_NODE = UUID.randomUUID();

	private CasbinClient client;
	private CasbinRuleLoader loader;

	@BeforeEach
	void setUp() {
		CasbinProperties properties = new CasbinProperties();
		properties.setAddress("localhost:" + HOST_PORT);
		client = new CasbinClient(properties);
		WorkspaceGrantRepository workspaceGrants = mock(WorkspaceGrantRepository.class);
		when(workspaceGrants.findAll()).thenReturn(List.of(
				new WorkspaceGrant(TENANT, HR, HR_NODE, WorkspaceRole.ADMIN),
				new WorkspaceGrant(TENANT, FIN, FIN_NODE, WorkspaceRole.ADMIN)));
		RankGrantRepository rankGrants = mock(RankGrantRepository.class);
		when(rankGrants.findAll()).thenReturn(List.of(new RankGrant(TENANT, EXEC_NODE, Rank.K),
				new RankGrant(TENANT, HR_LEAD_NODE, HR, Rank.K)));
		RbacProperties rbac = new RbacProperties();
		rbac.setEnforce(true);
		loader = new CasbinRuleLoader(rbac, client, workspaceGrants, rankGrants);
	}

	@Test
	void teamAndRankRulesDecideWhoSeesWhat() {
		loader.ensureLoaded();

		// 과장 이상 → exec. 팀 규칙은 자기 팀 노드만 연다.
		assertThat(sees(HR, Rank.TL)).containsExactly(HR_NODE, EXEC_NODE);
		assertThat(sees(HR, Rank.K)).containsExactly(HR_NODE, EXEC_NODE);
		assertThat(sees(HR, Rank.D)).containsExactly(HR_NODE);
		assertThat(sees(HR, Rank.S)).containsExactly(HR_NODE);
		assertThat(sees(FIN, Rank.B)).containsExactly(FIN_NODE, EXEC_NODE);
	}

	@Test
	void aTeamScopedRankRuleNeedsBothTheTeamAndTheRank() {
		loader.ensureLoaded();

		// 인사팀의 과장 이상만. 재무팀 부장은 서열은 되지만 팀이 달라 거부된다.
		assertThat(client.enforce(attributes(HR, Rank.K), HR_LEAD_NODE.toString(), "view")).isTrue();
		assertThat(client.enforce(attributes(HR, Rank.D), HR_LEAD_NODE.toString(), "view")).isFalse();
		assertThat(client.enforce(attributes(FIN, Rank.B), HR_LEAD_NODE.toString(), "view")).isFalse();
	}

	@Test
	void aStringRankIsAnErrorAndErrorsAreDenials() {
		loader.ensureLoaded();

		assertThat(client.enforce("{\"OrgUnit\":\"" + HR + "\",\"Rank\":\"1\"}", EXEC_NODE.toString(), "view")).isFalse();
		assertThat(client.isLoaded()).as("오류가 적재 상태를 비우지 않는다").isTrue();
	}

	@Test
	void rulesLostByAServerRestartAreReloaded() throws Exception {
		loader.ensureLoaded();
		assertThat(sees(HR, Rank.S)).containsExactly(HR_NODE);

		DockerClientFactory.instance().client().restartContainerCmd(CASBIN.getContainerId()).exec();
		waitUntilListening();

		// 재시작 직후에는 연결이 아직 안 붙어 첫 요청이 UNAVAILABLE일 수 있다(CI처럼 느린 환경). 그동안에도 모두 거부여야 하고,
		// 서버에 닿는 순간 "enforcer not found"를 받아 적재 상태를 비워야 한다.
		for (int attempt = 0; attempt < 50 && client.isLoaded(); attempt++) {
			assertThat(client.enforce(attributes(HR, Rank.S), HR_NODE.toString(), "view")).as("재시작한 서버에는 규칙이 없다").isFalse();
			if (client.isLoaded()) Thread.sleep(200);
		}
		assertThat(client.isLoaded()).isFalse();

		loader.ensureLoaded();
		assertThat(sees(HR, Rank.S)).containsExactly(HR_NODE);
	}

	@Test
	void theFirstRequestAfterAServerOutageDetectsTheLostRules() throws Exception {
		// CI에서 드러난 경우: 서버가 내려가 있는 동안 요청이 실패하면 gRPC 채널이 재연결 대기(backoff)에 들어간다.
		// 서버가 다시 떠도 대기 중이면 요청이 서버에 닿기 전에 UNAVAILABLE로 끝나, 규칙을 잃었다는 것을 알지 못한다.
		loader.ensureLoaded();
		DockerClientFactory.instance().client().stopContainerCmd(CASBIN.getContainerId()).exec();
		assertThat(client.enforce(attributes(HR, Rank.S), HR_NODE.toString(), "view")).as("서버가 없으면 거부").isFalse();
		assertThat(client.isLoaded()).as("연결 실패만으로는 규칙을 잃었다고 보지 않는다").isTrue();
		DockerClientFactory.instance().client().startContainerCmd(CASBIN.getContainerId()).exec();
		waitUntilListening();

		assertThat(client.enforce(attributes(HR, Rank.S), HR_NODE.toString(), "view")).isFalse();
		assertThat(client.isLoaded()).as("다시 뜬 서버에 첫 요청이 닿아 규칙을 잃은 것을 안다").isFalse();

		loader.ensureLoaded();
		assertThat(sees(HR, Rank.S)).containsExactly(HR_NODE);
	}

	@Test
	void anUnreachableServerIsADenial() {
		CasbinProperties nowhere = new CasbinProperties();
		nowhere.setAddress("localhost:" + freePort());
		nowhere.setDeadline(Duration.ofMillis(500));
		CasbinClient unreachable = new CasbinClient(nowhere);

		assertThat(unreachable.enforce(attributes(HR, Rank.TL), HR_NODE.toString(), "view")).isFalse();
	}

	private List<UUID> sees(UUID team, Rank rank) {
		return List.of(HR_NODE, FIN_NODE, EXEC_NODE).stream()
				.filter(node -> client.enforce(attributes(team, rank), node.toString(), "view"))
				.toList();
	}

	private static String attributes(UUID team, Rank rank) {
		return "{\"OrgUnit\":\"" + team + "\",\"Rank\":" + rank.order() + "}";
	}

	private static void waitUntilListening() throws InterruptedException {
		for (int attempt = 0; attempt < 50; attempt++) {
			try (java.net.Socket ignored = new java.net.Socket("localhost", HOST_PORT)) {
				return;
			} catch (IOException notYet) {
				Thread.sleep(200);
			}
		}
		throw new IllegalStateException("casbin-server가 재시작 뒤 열리지 않았다");
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException error) {
			throw new IllegalStateException(error);
		}
	}
}
