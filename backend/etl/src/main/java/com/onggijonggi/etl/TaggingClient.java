package com.onggijonggi.etl;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : TaggingClient.java
 * Description : 사내 LLM 서버(OpenAI 호환 /v1/chat/completions)에 태깅 요청을 보낸다(#362). 결과가 매번 같도록 온도 0으로 부른다.
 *               연결 실패·5xx·429는 일시 장애, 그 밖의 4xx(입력 초과·모델 없음)는 영구 실패로 분류한다(HttpCalls). 응답 본문을 해석하지
 *               못하면 서버 쪽 일시 문제로 본다. 출력 한도에서 잘린 응답은 그대로 돌려준다 — 태그 검사(TagPrompt.parse)가 미분류로 둔다.
 */
@Component
public class TaggingClient {

	private final RestClient client;
	private final ObjectMapper json;
	private final TaggingProperties settings;

	public TaggingClient(TaggingProperties settings, ObjectMapper json) {
		this.settings = settings;
		this.client = HttpCalls.client(settings.url(), settings.timeout());
		this.json = json;
	}

	/** system 지시와 user 자료로 한 번 부르고 응답 본문(content)을 돌려준다. */
	public String complete(String system, String user) {
		var body = Map.of("model", settings.model(), "temperature", 0, "max_tokens", settings.maxTokens(),
				"messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
		String response;
		try {
			response = client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
					.body(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)).retrieve().body(String.class);
		} catch (RuntimeException error) {
			throw HttpCalls.classify("TAGGING", error);
		}
		JsonNode content;
		try {
			content = json.readTree(response == null ? "{}" : response).path("choices").path(0).path("message").path("content");
		} catch (RuntimeException unreadable) {
			throw EtlFailure.transientFailure("TAGGING_UNAVAILABLE", "응답을 해석하지 못했다", unreadable);
		}
		if (!content.isString()) throw EtlFailure.transientFailure("TAGGING_UNAVAILABLE", "응답에 본문이 없다", null);
		return content.asString();
	}
}
