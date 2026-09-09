package com.onggijonggi.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Class Name : FixedWindowRateLimiterTest.java
 * Description : 고정 윈도우 카운터의 계약(이슈 #74). 시간을 손으로 밀어 창 경계와 만료 청소를
 *               결정적으로 확인한다.
 */
class FixedWindowRateLimiterTest {

	private static final long WINDOW_SECONDS = 60;

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-09T00:00:00Z"));

	/** 테스트가 시간을 밀 수 있는 시계. Clock.fixed는 못 움직여서 얇게 감싼다. */
	private Clock clock() {
		return new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
	}

	private void advance(Duration amount) {
		now.updateAndGet(instant -> instant.plus(amount));
	}

	@Test
	void allowsUpToTheLimitAndRefusesBeyond() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock(), WINDOW_SECONDS, 3);

		assertThat(limiter.tryAcquire("sub-1")).isTrue();
		assertThat(limiter.tryAcquire("sub-1")).isTrue();
		assertThat(limiter.tryAcquire("sub-1")).isTrue();
		assertThat(limiter.tryAcquire("sub-1")).isFalse();
	}

	@Test
	void countsEachKeySeparately() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock(), WINDOW_SECONDS, 1);

		assertThat(limiter.tryAcquire("sub-1")).isTrue();
		// 한 사람이 한도를 다 썼다고 다른 사람이 막히면 안 된다.
		assertThat(limiter.tryAcquire("sub-2")).isTrue();
		assertThat(limiter.tryAcquire("sub-1")).isFalse();
	}

	@Test
	void startsANewWindowWhenTheOldOnePasses() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock(), WINDOW_SECONDS, 1);
		assertThat(limiter.tryAcquire("sub-1")).isTrue();
		assertThat(limiter.tryAcquire("sub-1")).isFalse();

		advance(Duration.ofSeconds(WINDOW_SECONDS));

		assertThat(limiter.tryAcquire("sub-1")).isTrue();
	}

	@Test
	void reportsSecondsLeftInTheCurrentWindow() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock(), WINDOW_SECONDS, 1);
		// 창 시작 정각이라 한 창이 통째로 남아 있다.
		assertThat(limiter.secondsUntilWindowResets()).isEqualTo(WINDOW_SECONDS);

		advance(Duration.ofSeconds(20));

		assertThat(limiter.secondsUntilWindowResets()).isEqualTo(40);
	}

	@Test
	void forgetsKeysFromPastWindowsOnceTheMapGrows() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock(), WINDOW_SECONDS, 1);
		// 청소는 임계 크기를 넘겨야 도므로, 지난 창에서 그만큼 키를 만들어 둔다.
		for (int i = 0; i < 1024; i++) {
			limiter.tryAcquire("stale-" + i);
		}

		assertThat(limiter.keyCountForTesting()).isEqualTo(1024);

		advance(Duration.ofSeconds(WINDOW_SECONDS));
		limiter.tryAcquire("fresh");

		// 지난 창 키가 쓸려나가고 방금 센 것만 남는다. 창이 바뀌면 카운터는 어차피 새로 갈리므로
		// 동작이 아니라 맵 크기가 이 청소의 유일한 관찰 지점이다.
		assertThat(limiter.keyCountForTesting()).isEqualTo(1);
	}

}
