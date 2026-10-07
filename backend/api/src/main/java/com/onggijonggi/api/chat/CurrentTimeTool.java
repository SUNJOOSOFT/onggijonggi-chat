package com.onggijonggi.api.chat;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Class Name : CurrentTimeTool.java
 * Description : AI 턴에 붙이는 현재 시각 도구. 시간대는 발화한 사람의 브라우저가 보낸 값(IANA 이름)을
 *               toolContext로 받는다 — LocaleContextHolder 같은 ThreadLocal은 WebFlux에서 채워지지 않고,
 *               도구는 요청 스레드가 아닌 스레드에서 실행되므로 쓰지 않는다.
 *               보낸 값이 없거나 해석할 수 없으면 서버 기본값(app.thread.ai.time-zone)이다.
 */
@Component
public class CurrentTimeTool {

	static final String TIME_ZONE = "timeZone";
	/** IANA 시간대 이름의 길이 상한. 클라이언트가 보내는 값이라 해석 전에 자른다. */
	private static final int MAX_ZONE_LENGTH = 64;

	private final Clock clock;
	private final ZoneId defaultZone;

	@Autowired
	public CurrentTimeTool(@Value("${app.thread.ai.time-zone:Asia/Seoul}") String defaultZone) {
		this(Clock.systemUTC(), ZoneId.of(defaultZone));
	}

	CurrentTimeTool(Clock clock, ZoneId defaultZone) {
		this.clock = clock;
		this.defaultZone = defaultZone;
	}

	/** toolContext에 실을 값. requested는 브라우저가 보낸 시간대이고 null일 수 있다. */
	Map<String, Object> context(String requested) {
		return Map.of(TIME_ZONE, zoneOf(requested));
	}

	@Tool(description = "사용자 시간대 기준의 현재 날짜·시각·요일을 돌려준다. 오늘, 지금, 요일, 며칠 뒤처럼 현재 시점이 필요한 질문에 쓴다.")
	public String currentDateTime(ToolContext toolContext) {
		Object zone = toolContext.getContext().get(TIME_ZONE);
		ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone instanceof ZoneId id ? id : defaultZone))
				.truncatedTo(ChronoUnit.SECONDS);
		// 모델은 날짜에서 요일을 잘못 계산하기 쉬워 함께 준다.
		return now.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) + " " + now.getDayOfWeek();
	}

	private ZoneId zoneOf(String requested) {
		if (requested == null || requested.isBlank() || requested.length() > MAX_ZONE_LENGTH) return defaultZone;
		try {
			return ZoneId.of(requested);
		} catch (DateTimeException invalid) {
			return defaultZone;
		}
	}
}
