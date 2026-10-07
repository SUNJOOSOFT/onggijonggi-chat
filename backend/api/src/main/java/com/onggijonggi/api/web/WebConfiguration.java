package com.onggijonggi.api.web;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : WebConfiguration.java
 * Description : 웹 도구 설정(app.web.*)을 바인딩하고 검색 공급자를 고른다. 공급자 이름이 틀렸거나 searxng인데 주소가 비어 있으면
 *               도구가 켜져 있을 때 기동을 멈춘다 — 조용히 꺼진 채 돌면 모델이 "검색할 수 없다"만 되풀이한다.
 *               HttpClient는 리다이렉트를 따라가지 않는다 — 웹 읽기가 리다이렉트마다 주소를 다시 검사한다(WebFetcher).
 */
@Configuration
@EnableConfigurationProperties(WebProperties.class)
class WebConfiguration {

	@Bean
	WebTools webTools(WebProperties properties, ObjectMapper json) {
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(properties.fetch().timeout())
				.build();
		return new WebTools(properties, searchProvider(properties, client, json),
				new WebFetcher(client, new PublicUrlGuard(), properties.fetch()));
	}

	private static WebSearchProvider searchProvider(WebProperties properties, HttpClient client, ObjectMapper json) {
		WebProperties.Search search = properties.search();
		return switch (search.provider()) {
			case "duckduckgo" -> new DuckDuckGoSearchProvider(client, search.timeout());
			case "searxng" -> {
				if (properties.enabled() && properties.searxng().url().isBlank())
					throw new IllegalStateException("app.web.search.provider=searxng인데 app.web.searxng.url이 비어 있다");
				yield new SearxngSearchProvider(client, properties.searxng().url(), search.timeout(), json);
			}
			default -> throw new IllegalStateException("모르는 웹 검색 공급자다(duckduckgo·searxng): " + search.provider());
		};
	}
}
