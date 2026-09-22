package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Class Name : CasbinRuleLoader.java
 * Description : 03·CORE DB 규칙(wrk_grn·rank_grn)을 casbin-server에 넣는다. 규칙은 조직 구조 설정(workspace-setup.yml)에서만
 *               오고 그 파일은 기동 때만 읽히므로, 넣는 때는 기동과 서버 재시작 뒤 둘뿐이다(reload 엔드포인트는 없다).
 *               기동 때는 bootstrap(ApplicationReadyEvent)이 끝난 뒤인 ReadinessState.ACCEPTING_TRAFFIC에서 넣는다 —
 *               그 전에 넣으면 bootstrap이 만들 규칙을 놓친다. 사람 배정은 넣지 않는다(판정 때 DB에서 읽는다).
 *               권한 판정(app.rbac.enforce)이 꺼져 있으면 아무것도 하지 않는다.
 */
@Component
public class CasbinRuleLoader {

	private static final Logger log = LoggerFactory.getLogger(CasbinRuleLoader.class);

	private final RbacProperties rbacProperties;
	private final CasbinClient client;
	private final WorkspaceGrantRepository workspaceGrants;
	private final RankGrantRepository rankGrants;
	private final String modelText;

	public CasbinRuleLoader(RbacProperties rbacProperties, CasbinClient client, WorkspaceGrantRepository workspaceGrants,
			RankGrantRepository rankGrants) {
		this.rbacProperties = rbacProperties;
		this.client = client;
		this.workspaceGrants = workspaceGrants;
		this.rankGrants = rankGrants;
		this.modelText = readModel();
	}

	@EventListener
	public void onReadiness(AvailabilityChangeEvent<ReadinessState> event) {
		if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC && rbacProperties.isEnforce()) {
			loadQuietly();
		}
	}

	/** 판정 직전에 부른다. 이미 적재돼 있으면 아무것도 하지 않는다. 블로킹이다(DB·gRPC). */
	public void ensureLoaded() {
		if (!client.isLoaded()) loadQuietly();
	}

	/** 동시에 여러 판정이 적재를 요청해도 한 번만 넣는다. 실패는 로그만 남긴다 — 적재되지 않은 동안 판정은 모두 거부다. */
	private synchronized void loadQuietly() {
		if (client.isLoaded()) return;
		try {
			client.load(modelText, CasbinPolicy.rules(workspaceGrants.findAll(), rankGrants.findAll()));
		} catch (RuntimeException error) {
			log.warn("Casbin 규칙 적재 실패 — 적재될 때까지 판정은 거부한다: {}", error.getMessage());
		}
	}

	private static String readModel() {
		try (InputStream in = CasbinRuleLoader.class.getClassLoader().getResourceAsStream(CasbinPolicy.MODEL_RESOURCE)) {
			if (in == null) throw new IllegalStateException(CasbinPolicy.MODEL_RESOURCE + "가 없다");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
	}
}
