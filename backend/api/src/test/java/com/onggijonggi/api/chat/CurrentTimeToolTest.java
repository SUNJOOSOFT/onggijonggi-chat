package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class CurrentTimeToolTest {

	/** 2026-10-07T06:00:00Z — 서울은 15:00 수요일, 뉴욕은 02:00 수요일이다. */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T06:00:00Z"), ZoneOffset.UTC);

	private final CurrentTimeTool tool = new CurrentTimeTool(CLOCK, ZoneId.of("Asia/Seoul"));

	@Test
	void convertsTheCurrentInstantIntoTheBrowserTimeZone() {
		assertThat(tool.currentDateTime(new ToolContext(tool.context("America/New_York"))))
				.isEqualTo("2026-10-07T02:00:00-04:00[America/New_York] WEDNESDAY");
	}

	@Test
	void fallsBackToTheServerDefaultWhenTheBrowserSentNothing() {
		assertThat(tool.currentDateTime(new ToolContext(tool.context(null))))
				.isEqualTo("2026-10-07T15:00:00+09:00[Asia/Seoul] WEDNESDAY");
	}

	@Test
	void fallsBackToTheServerDefaultWhenTheTimeZoneCannotBeParsed() {
		assertThat(tool.context("Mars/Olympus")).containsEntry(CurrentTimeTool.TIME_ZONE, ZoneId.of("Asia/Seoul"));
		assertThat(tool.context(" ")).containsEntry(CurrentTimeTool.TIME_ZONE, ZoneId.of("Asia/Seoul"));
		assertThat(tool.context("A".repeat(65))).containsEntry(CurrentTimeTool.TIME_ZONE, ZoneId.of("Asia/Seoul"));
	}
}
