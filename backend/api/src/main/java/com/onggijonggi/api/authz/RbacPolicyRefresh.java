package com.onggijonggi.api.authz;

import com.onggijonggi.api.chat.RoomSessionRegistry;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Class Name : RbacPolicyRefresh.java
 * Description : 03·CORE 커밋된 Workspace·부여 변경(#260)을 Casbin에 반영하고 영향받는 협업방 구독을 해제한다.
 *               반영이 실패해도 DB 변경은 되돌리지 않는다. 대신 그 Tenant를 막아 두고(판정이 켜진 동안 판정·관리 쓰기 거부)
 *               1초부터 최대 30초 간격으로 다시 시도한다. 구독 해제는 판정이 켜진 뒤(절체 후)에만 한다 — 꺼진 동안은 기존
 *               채팅·WebSocket 동작을 바꾸지 않는다. Casbin 반영은 스위치와 무관하게 한다(casbin 프로필 안에서 스위치만 꺼 둔
 *               절체 전 운영에서도 규칙이 최신이어야 켜는 순간 맞는 판정을 한다).
 *               isBlocked는 채팅 판정마다 불리므로 잠그지 않는다. 적재(gRPC)는 별도 잠금으로 한 번에 하나만 하고, 적재는 DB 전체를
 *               읽으므로 적재를 시작할 때 막혀 있던 Tenant는 성공하면 모두 풀린다. 적재 중에 새로 커밋된 변경은 표식(seq)이 달라
 *               풀리지 않고 다음 적재를 기다린다.
 */
@Component
public class RbacPolicyRefresh {
	private static final Logger log = LoggerFactory.getLogger(RbacPolicyRefresh.class);
	private final CasbinRuleLoader loader;
	private final RbacProperties properties;
	private final ThrRepository threads;
	private final RoomSessionRegistry rooms;
	private final Clock clock;
	private final Map<UUID, Retry> blocked = new ConcurrentHashMap<>();
	private final Object reloadLock = new Object();
	private final AtomicLong sequence = new AtomicLong();

	/** seq는 publish마다 새로 붙는 표식이다 — 적재 중 새로 들어온 변경을 이전 표식과 구별한다. */
	private record Retry(long seq, int attempt, Instant due) { }

	@Autowired
	public RbacPolicyRefresh(CasbinRuleLoader loader, RbacProperties properties, ThrRepository threads,
			RoomSessionRegistry rooms) {
		this(loader, properties, threads, rooms, Clock.systemUTC());
	}

	RbacPolicyRefresh(CasbinRuleLoader loader, RbacProperties properties, ThrRepository threads,
			RoomSessionRegistry rooms, Clock clock) {
		this.loader = loader;
		this.properties = properties;
		this.threads = threads;
		this.rooms = rooms;
		this.clock = clock;
	}

	public boolean isBlocked(UUID tenantId) {
		return blocked.containsKey(tenantId);
	}

	/** 쓰기 트랜잭션이 커밋된 뒤에만 부른다. 롤백된 변경은 반영하지 않는다. */
	public void publish(UUID tenantId, Set<UUID> affectedNodes, UUID movedThreadId) {
		blocked.put(tenantId, new Retry(sequence.incrementAndGet(), 0, clock.instant()));
		reloadPending();
		if (!properties.isEnforce()) return;
		// 변경은 이미 커밋됐다. 구독 해제가 실패해도 요청을 실패로 돌리지 않는다 — 다시 구독하면 최신 권한으로 판정하므로
		// 남은 구독은 다음 재구독까지의 창일 뿐이다. 로그로 남긴다.
		try {
			if (movedThreadId != null) rooms.evictCollabRoom(movedThreadId);
			for (UUID nodeId : affectedNodes) {
				for (Thr thread : threads.findByWorkspaceNodeIdAndKind(nodeId, ThrKind.COLLAB)) {
					rooms.evictCollabRoom(thread.getId());
				}
			}
		} catch (RuntimeException error) {
			log.error("커밋된 권한 변경 뒤 협업방 구독 해제에 실패했다 — 노드 {}", affectedNodes, error);
		}
	}

	@Scheduled(fixedDelay = 1000)
	public void retryFailed() {
		Instant now = clock.instant();
		if (blocked.values().stream().anyMatch(retry -> !retry.due().isAfter(now))) reloadPending();
	}

	private void reloadPending() {
		synchronized (reloadLock) {
			Map<UUID, Retry> pending = Map.copyOf(blocked);
			if (pending.isEmpty()) return;
			try {
				loader.reload();
				pending.forEach(blocked::remove);
			} catch (RuntimeException error) {
				pending.forEach((tenantId, retry) -> {
					int next = retry.attempt() + 1;
					long seconds = Math.min(1L << Math.min(next - 1, 5), 30);
					blocked.replace(tenantId, retry, new Retry(retry.seq(), next, clock.instant().plusSeconds(seconds)));
				});
				log.error("커밋된 권한 변경을 Casbin에 반영하지 못했다 — 반영될 때까지 Tenant {}를 막는다", pending.keySet(), error);
			}
		}
	}
}
