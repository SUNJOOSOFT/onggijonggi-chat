package com.onggijonggi.api.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Class Name : WebProperties.java
 * Description : AI 턴의 웹 검색·웹 읽기 도구 설정(app.web.*). enabled가 false면 두 도구를 모델에 주지 않는다.
 *               검색 공급자는 search.provider로 고른다 — duckduckgo(키 없음, 비공식 HTML) 또는 searxng(자체 호스팅).
 */
@ConfigurationProperties(prefix = "app.web")
public record WebProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue Search search,
		@DefaultValue Searxng searxng,
		@DefaultValue Fetch fetch) {

	/** maxResults는 모델에 돌려주는 검색 결과 수다. */
	public record Search(@DefaultValue("duckduckgo") String provider, @DefaultValue("5") int maxResults,
			@DefaultValue("10s") Duration timeout) { }

	/** SearXNG 주소. settings.yml의 search.formats에 json이 있어야 한다. */
	public record Searxng(@DefaultValue("") String url) { }

	/**
	 * 웹 읽기. maxBytes는 내려받는 본문 상한, maxChars는 모델에 돌려주는 글자 수 상한이다(문맥을 다 쓰지 않게).
	 * maxRedirects만큼 리다이렉트를 따라가며, 매번 주소를 다시 검사한다.
	 */
	public record Fetch(@DefaultValue("2097152") int maxBytes, @DefaultValue("8000") int maxChars,
			@DefaultValue("3") int maxRedirects, @DefaultValue("10s") Duration timeout) { }
}
