package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.common.document.TagPrompt;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : TaggingClientTest.java
 * Description : 태깅 요청(#362)의 모양(모델·온도 0·출력 상한·system/user)과 응답·실패 분류를 가짜 서버로 확인한다.
 */
class TaggingClientTest {

	private StubHttpServer llm;
	private TaggingClient client;

	@BeforeEach
	void setUp() throws Exception {
		llm = new StubHttpServer();
		client = new TaggingClient(properties(llm.url()), JsonMapper.builder().build());
	}

	@AfterEach
	void tearDown() {
		llm.close();
	}

	static TaggingProperties properties(String url) {
		return new TaggingProperties(url, "tag-model", Duration.ofSeconds(5), List.of("인사·총무", "기타"), 10, 200, 512, 100, 40, 3, 20,
				Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofMillis(100));
	}

	/** 카테고리 목록이 비었거나(환경 변수를 빈 값으로 넘김) 공백뿐이면 기본 목록을 쓴다 — 빈 목록이면 모든 문서가 미분류로 굳는다. */
	@Test
	void anEmptyCategoryListFallsBackToTheDefaults() {
		for (List<String> empty : java.util.Arrays.<List<String>>asList(null, List.of(), List.of(" ", "")))
			assertThat(new TaggingProperties("u", "m", Duration.ofSeconds(5), empty, 10, 200, 512, 100, 40, 3, 20, Duration.ofSeconds(1),
					Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofMillis(100)).categories()).isEqualTo(TagPrompt.DEFAULT_CATEGORIES);
		assertThat(new TaggingProperties("u", "m", Duration.ofSeconds(5), List.of(" 휴가 ", "기타"), 10, 200, 512, 100, 40, 3, 20,
				Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofMillis(100)).categories())
				.containsExactly("휴가", "기타");
		assertThat(new TaggingProperties("u", "m", Duration.ofSeconds(5), List.of("휴가", " 휴가", "기타", "휴가"), 10, 200, 512, 100, 40, 3, 20,
				Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofMillis(100)).categories())
				.as("중복은 한 번만 — 지문이 바뀌어 불필요하게 다시 태깅되지 않게").containsExactly("휴가", "기타");
	}

	@Test
	void theRequestIsDeterministicAndTheAnswerContentIsReturned() {
		llm.reply("/v1/chat/completions", 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"category\\\":\\\"기타\\\"}\"}}]}");

		assertThat(client.complete("지시", "<document>본문</document>")).isEqualTo("{\"category\":\"기타\"}");

		JsonNode sent = JsonMapper.builder().build().readTree(llm.requests.get(0).body());
		assertThat(sent.path("model").asString()).isEqualTo("tag-model");
		assertThat(sent.path("temperature").asInt(-1)).isZero();
		assertThat(sent.path("max_tokens").asInt()).isEqualTo(512);
		assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("system");
		assertThat(sent.path("messages").get(1).path("content").asString()).isEqualTo("<document>본문</document>");
	}

	@Test
	void serverTroubleIsTransientAndARejectedRequestIsPermanent() {
		llm.reply("/v1/chat/completions", 503, "{}");
		assertThatThrownBy(() -> client.complete("지시", "본문"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("TAGGING_UNAVAILABLE");
					assertThat(failure.permanent()).isFalse();
				});

		llm.reply("/v1/chat/completions", 400, "{\"error\":\"too long\"}");
		assertThatThrownBy(() -> client.complete("지시", "본문"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("TAGGING_REJECTED");
					assertThat(failure.permanent()).isTrue();
				});

		for (String broken : List.of("not json", "{\"choices\":[]}")) {
			llm.reply("/v1/chat/completions", 200, broken);
			assertThatThrownBy(() -> client.complete("지시", "본문")).as(broken)
					.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
		}
	}
}
