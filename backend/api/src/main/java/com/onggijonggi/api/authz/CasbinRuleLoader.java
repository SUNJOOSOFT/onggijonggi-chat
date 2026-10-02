package com.onggijonggi.api.authz;

import com.onggijonggi.api.chat.CollabAuthorizationRevoker;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Class Name : CasbinRuleLoader.java
 * Description : 03·CORE DB 규칙(wrk_grn·rank_grn)과 사람 속성(MemberAttributeSource)을 casbin-server에 넣는다.
 *               넣을 때마다 속성 파일을 다시 읽는다 — 파일을 고치고 casbin을 재시작하면 다음 판정 때 다시 넣으며 반영된다.
 *               기동 때는 bootstrap(ApplicationReadyEvent)이 끝난 뒤인 ReadinessState.ACCEPTING_TRAFFIC에서 넣는다 —
 *               그 전에 넣으면 bootstrap이 만들 규칙을 놓친다.
 *               casbin 프로필(app.casbin.address)이 켜져 있으면 판정 스위치(app.rbac.enforce)와 무관하게 넣는다 — 스위치를
 *               꺼 둔 절체 전에도 직접 관리 판정과 절체 검증이 사람 속성을 읽는다. 프로필이 꺼져 있으면 아무것도 하지 않는다.
 *               다시 넣을 때마다 직전에 넣은 사람 속성과 비교해, 속성이 바뀌었거나 빠진 사람의 협업방 구독과 협업방 AI 턴을
 *               거둔다(CollabAuthorizationRevoker). casbin이 재시작하면 서버의 이전 값이 사라지므로 비교 기준은 여기서 들고
 *               있다 — 판정에는 쓰지 않는다(판정은 늘 Casbin에서 읽는다). 기동 뒤 첫 적재는 비교하지 않는다.
 *               적재가 실패하면(속성 파일이 틀림, Keycloak 장애 등) 새 enforcer로 바꾸지 않는다. 그래서 결과는 경로마다 다르다:
 *               - 기동·casbin 재시작 뒤(ensureLoaded): 남은 enforcer가 없어 판정은 모두 거부, 사람 속성 조회도 실패한다.
 *               - 규칙 변경 뒤(reload, RbacPolicyRefresh): 이전 enforcer와 이전 속성이 남고, RbacPolicyRefresh가 그 Tenant를
 *                 막아 판정·관리 쓰기를 거부한다.
 */
@Component
public class CasbinRuleLoader {

	private static final Logger log = LoggerFactory.getLogger(CasbinRuleLoader.class);
	/** 적재에 실패하면 이 시간 안에는 판정이 다시 적재를 시도하지 않는다 — 실패마다 파일과 Keycloak 전체 목록을 읽기 때문이다. */
	static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(5);

	private final CasbinProperties casbinProperties;
	private final CasbinClient client;
	private final WorkspaceGrantRepository workspaceGrants;
	private final RankGrantRepository rankGrants;
	private final MemberAttributeSource source;
	private final ObjectProvider<CollabAuthorizationRevoker> revoker;
	private final Clock clock;
	private final String modelText;
	/** 직전에 넣은 사람 속성(subject → 속성). null이면 아직 한 번도 넣지 못했다. */
	private Map<String, MemberAttribute> lastLoaded;
	private Instant lastFailure;

	@Autowired
	public CasbinRuleLoader(CasbinProperties casbinProperties, CasbinClient client, WorkspaceGrantRepository workspaceGrants,
			RankGrantRepository rankGrants, MemberAttributeSource source, ObjectProvider<CollabAuthorizationRevoker> revoker) {
		this(casbinProperties, client, workspaceGrants, rankGrants, source, revoker, Clock.systemUTC());
	}

	CasbinRuleLoader(CasbinProperties casbinProperties, CasbinClient client, WorkspaceGrantRepository workspaceGrants,
			RankGrantRepository rankGrants, MemberAttributeSource source, ObjectProvider<CollabAuthorizationRevoker> revoker,
			Clock clock) {
		this.casbinProperties = casbinProperties;
		this.client = client;
		this.workspaceGrants = workspaceGrants;
		this.rankGrants = rankGrants;
		this.source = source;
		this.revoker = revoker;
		this.clock = clock;
		this.modelText = readModel();
	}

	@EventListener
	public void onReadiness(AvailabilityChangeEvent<ReadinessState> event) {
		if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC && isActive()) {
			loadQuietly();
		}
	}

	/** casbin 프로필이 켜져 있나(casbin-server 주소가 있나). 꺼져 있으면 넣을 곳이 없다. */
	public boolean isActive() {
		return !casbinProperties.getAddress().isBlank();
	}

	/** 판정·조회 직전에 부른다. 이미 적재돼 있으면 아무것도 하지 않는다. 블로킹이다(파일·Keycloak·DB·gRPC). */
	public void ensureLoaded() {
		if (isActive() && !client.isLoaded()) loadQuietly();
	}

	/** 관리 변경이 커밋된 뒤 전체를 다시 넣는다. 실패는 호출자(RbacPolicyRefresh)에게 던진다 — 이전 적재가 그대로 남고 호출자가 Tenant를 막는다. */
	public synchronized void reload() {
		load();
	}

	/** 동시에 여러 판정이 적재를 요청해도 한 번만 넣는다. 실패는 로그만 남긴다 — 적재되지 않은 동안 판정은 모두 거부다. */
	private synchronized void loadQuietly() {
		if (client.isLoaded()) return;
		if (lastFailure != null && clock.instant().isBefore(lastFailure.plus(RETRY_AFTER_FAILURE))) return;
		try {
			load();
		} catch (MemberAttributeSourceException error) {
			lastFailure = clock.instant();
			log.error("사람 속성 파일이 올바르지 않아 적재하지 않았다 — 고칠 때까지 판정은 거부한다: {}", error.getProblems());
		} catch (RuntimeException error) {
			lastFailure = clock.instant();
			log.warn("Casbin 적재 실패 — 적재될 때까지 판정은 거부한다: {}", error.getMessage());
		}
	}

	private void load() {
		MemberAttributeSource.Load members = source.load();
		client.load(modelText, CasbinPolicy.rules(workspaceGrants.findAll(), rankGrants.findAll()), members.members());
		lastFailure = null;
		Map<String, MemberAttribute> current = new HashMap<>();
		for (MemberAttribute member : members.members()) current.put(member.subject(), member);
		Set<String> changed = lastLoaded == null ? Set.of() : changedSubjects(lastLoaded, current);
		lastLoaded = current;
		log.info("사람 속성 적재: {}명, 건너뜀 {}, 파일 {}, 회수 {}명", current.size(), members.skipped(), members.fingerprint(),
				changed.size());
		for (String subject : changed) {
			try {
				revoker.getObject().revoke(subject);
			} catch (RuntimeException error) {
				// 적재는 끝났다. 회수가 실패해도 다시 구독하면 새 속성으로 판정하므로 남은 구독은 다음 재구독까지의 창이다.
				log.error("속성이 바뀐 사람의 협업방 권한 회수 실패: {}", subject, error);
			}
		}
	}

	/** 속성이 바뀌었거나 빠진 사람. 새로 들어온 사람은 거둘 것이 없다. */
	static Set<String> changedSubjects(Map<String, MemberAttribute> before, Map<String, MemberAttribute> after) {
		Set<String> changed = new LinkedHashSet<>();
		for (Map.Entry<String, MemberAttribute> entry : before.entrySet()) {
			if (!entry.getValue().equals(after.get(entry.getKey()))) changed.add(entry.getKey());
		}
		return changed;
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
