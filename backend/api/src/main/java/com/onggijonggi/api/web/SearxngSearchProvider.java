package com.onggijonggi.api.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : SearxngSearchProvider.java
 * Description : 자체 호스팅 SearXNG의 JSON 검색(/search?format=json)을 부른다. SearXNG가 여러 검색엔진을 대신 조회하므로
 *               엔진 하나가 막혀도 결과가 나온다. settings.yml의 search.formats에 json이 없으면 403이 온다.
 */
class SearxngSearchProvider implements WebSearchProvider {

	private final HttpClient client;
	private final String baseUrl;
	private final Duration timeout;
	private final ObjectMapper json;

	SearxngSearchProvider(HttpClient client, String baseUrl, Duration timeout, ObjectMapper json) {
		this.client = client;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.timeout = timeout;
		this.json = json;
	}

	@Override
	public List<Result> search(String query, int limit) {
		URI uri = URI.create(baseUrl + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
		HttpResponse<String> response;
		try {
			response = client.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException error) {
			throw new WebToolException("검색 서버에 연결하지 못했다", error);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new WebToolException("검색이 중단됐다", interrupted);
		}
		if (response.statusCode() != 200) throw new WebToolException("검색 서버가 검색을 거절했다(HTTP " + response.statusCode() + ")");
		return parse(json, response.body(), limit);
	}

	static List<Result> parse(ObjectMapper json, String body, int limit) {
		JsonNode results;
		try {
			results = json.readTree(body).path("results");
		} catch (RuntimeException unreadable) {
			throw new WebToolException("검색 서버 응답을 해석하지 못했다", unreadable);
		}
		if (!results.isArray()) throw new WebToolException("검색 서버 응답에 결과 목록이 없다");
		List<Result> parsed = new ArrayList<>();
		for (JsonNode item : results) {
			if (parsed.size() >= limit) break;
			String url = item.path("url").asString("");
			if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
			parsed.add(new Result(item.path("title").asString(""), url, item.path("content").asString("")));
		}
		return parsed;
	}
}
