package com.onggijonggi.api.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : RateLimitWebFilterTest.java
 * Description : sub(JWT subject)별 고정 윈도우 RateLimit의 429 응답·Retry-After
 *               헤더·윈도우 리셋을 검증한다. 기본값(분당 20회)으로는 테스트가 느려지므로
 *               app.ratelimit.per-minute/window-seconds를 낮게 오버라이드한 별도 컨텍스트를 쓴다
 *               (ChatControllerTest와 다른 프로퍼티라 Spring이 컨텍스트를 별도로 캐싱한다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"app.ratelimit.per-minute=2", "app.ratelimit.window-seconds=" + RateLimitWebFilterTest.WINDOW_SECONDS})
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class,
		RateLimitWebFilterTest.RateLimitClockTestConfig.class})
class RateLimitWebFilterTest {

	static final int WINDOW_SECONDS = 2;
	private static final Instant INITIAL_INSTANT = Instant.ofEpochSecond(1);

	@LocalServerPort
	private int port;

	private RestTestClient restTestClient;

	@Autowired
	private MutableClock rateLimitClock;

	@BeforeEach
	void setUp() {
		// 시계만 되돌린다 — RateLimitWebFilter.counters는 필터 빈 자체의 수명(Spring 컨텍스트
		// 캐싱 동안 하나)이라 여기서 리셋되지 않는다. 이 클래스에 테스트를 추가할 때 같은 sub를
		// 재사용하면, 시계는 항상 같은 INITIAL_INSTANT로 돌아가 windowIndex도 같은 값이 나오므로
		// 카운트가 앞 테스트에서 이어져 시작하자마자 429가 난다 — 테스트마다 다른 sub를 쓴다.
		rateLimitClock.set(INITIAL_INSTANT);
		restTestClient = RestTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	/**
	* returnsRateLimitedAfterExceedingPerMinuteLimit: 한도(2회)까지는 200이 오고, 그다음 요청은
	* 429 + Retry-After 헤더 + RATE_LIMITED 봉투가 오는지, 윈도우가 지나면 다시 200으로 리셋되는지 검증한다.
	* 필터는 sub별 요청 수만 세므로 어느 인증 엔드포인트를 쓰든 결과는 같다(`/api/chat/stream`이
	* 있던 시절 쓰던 것을 이슈 #164로 걷어낸 뒤 `/api/chat/sessions`로 옮겼다).
	*/
	@Test
	void returnsRateLimitedAfterExceedingPerMinuteLimit() {
		String token = "Bearer " + TestJwtSupport.signedJwt("ratelimit-window-reset-user", List.of("USER"));

		for (int i = 0; i < 2; i++) {
			restTestClient.get()
					.uri("/api/chat/sessions")
					.header(HttpHeaders.AUTHORIZATION, token)
					.exchange()
					.expectStatus().isOk();
		}

		restTestClient.get()
				.uri("/api/chat/sessions")
				.header(HttpHeaders.AUTHORIZATION, token)
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
				.expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("RATE_LIMITED");

		rateLimitClock.advance(Duration.ofSeconds(1));

		restTestClient.get()
				.uri("/api/chat/sessions")
				.header(HttpHeaders.AUTHORIZATION, token)
				.exchange()
				.expectStatus().isOk();
	}

	/** 레이트리밋이 읽는 Clock 빈을 가변 테스트 Clock으로 갈아끼운다. */
	@TestConfiguration
	static class RateLimitClockTestConfig {

		@Bean
		@Primary
		MutableClock mutableRateLimitClock() {
			return new MutableClock(INITIAL_INSTANT, ZoneOffset.UTC);
		}
	}

	/** {@code Clock.fixed}는 불변이라 전진시킬 수 없어, 윈도우 리셋 검증에 쓸 전진 가능한 Clock을 직접 만든다. */
	static final class MutableClock extends Clock {

		private final AtomicReference<Instant> instant;
		private final ZoneId zone;

		MutableClock(Instant initialInstant, ZoneId zone) {
			this(new AtomicReference<>(initialInstant), zone);
		}

		private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId newZone) {
			return zone.equals(newZone) ? this : new MutableClock(instant, newZone);
		}

		@Override
		public Instant instant() {
			return instant.get();
		}

		void set(Instant newInstant) {
			instant.set(newInstant);
		}

		void advance(Duration duration) {
			instant.updateAndGet(current -> current.plus(duration));
		}
	}

}
