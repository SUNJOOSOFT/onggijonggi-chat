package com.onggijonggi.api.auth;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class Name : FixedWindowRateLimiter.java
 * Description : 키별 in-memory 고정 윈도우 카운터. 세는 일만 하고 초과를 어떻게 알릴지는 모른다 —
 *               HTTP는 429 응답으로(RateLimitWebFilter), WS 메시지는 ErrorFrame으로
 *               (CollabWebSocketHandler, 이슈 #74) 서로 다르게 알리기 때문이다.
 *
 *               <b>인스턴스 하나가 버킷 하나다.</b> 카운터가 인스턴스 필드라 인스턴스를 나누는 것이
 *               곧 정책을 나누는 것이고, 이 클래스를 싱글턴 빈으로 만들어 여러 정책이 함께 쓰면
 *               그 정책들이 조용히 한 통이 된다 — 같은 sub가 화면을 쓰면서(HTTP) 소켓에 붙고(핸드셰이크)
 *               말도 하는(WS 메시지) 것은 정상인데, 한 통에 담으면 서로를 굶긴다.
 *               쓰는 쪽이 자기 인스턴스를 만들어 소유한다.
 *
 *               윈도우가 지난 엔트리는 다음 접근 때 값이 갈리므로 동작에는 영향이 없지만, 키가 계속
 *               늘면 맵이 커지기만 한다. 그래서 임계 크기를 넘을 때만 지난 윈도우 엔트리를 쓸어낸다
 *               (이슈 #74) — 타이머 스레드를 따로 두지 않으려는 것이고, 한산할 때는 쓸어낼 이유도 없다.
 */
public final class FixedWindowRateLimiter {

	/** 이 크기를 넘을 때만 청소한다. 넉넉히 잡아 평상시에는 훑는 비용이 아예 들지 않게 한다. */
	private static final int EVICTION_THRESHOLD = 1024;

	private record Window(long windowIndex, AtomicInteger count) {
	}

	private final ConcurrentHashMap<String, Window> counters = new ConcurrentHashMap<>();
	private final Clock clock;
	private final long windowSeconds;
	private final int limit;

	public FixedWindowRateLimiter(Clock clock, long windowSeconds, int limit) {
		this.clock = clock;
		this.windowSeconds = windowSeconds;
		this.limit = limit;
	}

	/**
	 * 이 키의 이번 호출을 세고, 한도 안이면 true를 돌려준다. 초과해도 계속 세는 것은 고정 윈도우의
	 * 성질이다 — 창이 바뀌면 어차피 새 카운터로 갈린다.
	 */
	public boolean tryAcquire(String key) {
		long windowIndex = clock.instant().getEpochSecond() / windowSeconds;
		Window window = counters.compute(key, (ignored, existing) -> {
			if (existing == null || existing.windowIndex() != windowIndex) {
				return new Window(windowIndex, new AtomicInteger(1));
			}
			existing.count().incrementAndGet();
			return existing;
		});
		evictStaleIfCrowded(windowIndex);
		return window.count().get() <= limit;
	}

	/** 지금 창이 끝나기까지 남은 초. 호출자가 Retry-After나 안내 문구에 쓴다. */
	public long secondsUntilWindowResets() {
		return windowSeconds - (clock.instant().getEpochSecond() % windowSeconds);
	}

	/**
	 * 지난 창의 엔트리를 지운다. 맵이 임계 크기를 넘을 때만 돌아서, 키가 몇 개뿐인 평상시에는
	 * 훑지 않는다. ConcurrentHashMap의 순회는 약한 일관성이라 지우는 동안 다른 스레드가 세도 안전하다 —
	 * 그 사이에 들어온 키는 이번 창의 것이라 애초에 지움 대상이 아니다.
	 */
	/** 청소가 실제로 도는지 보려면 맵 크기를 봐야 한다 — 만료 엔트리는 밖에서 관찰할 방법이 없다. */
	int keyCountForTesting() {
		return counters.size();
	}

	private void evictStaleIfCrowded(long currentWindowIndex) {
		if (counters.size() < EVICTION_THRESHOLD) {
			return;
		}
		counters.values().removeIf(window -> window.windowIndex() < currentWindowIndex);
	}

}
