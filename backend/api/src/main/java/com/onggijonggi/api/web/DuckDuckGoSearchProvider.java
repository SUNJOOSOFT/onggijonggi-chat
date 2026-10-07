package com.onggijonggi.api.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Class Name : DuckDuckGoSearchProvider.java
 * Description : DuckDuckGo의 HTML 검색 페이지(html.duckduckgo.com/html)를 읽어 결과를 뽑는다. 키가 필요 없지만 공식 API가
 *               아니다 — 요청이 몰리면 봇 확인 페이지(anomaly)가 오거나, 페이지 구조가 바뀌면 결과를 못 뽑는다. 둘 다 장애로
 *               돌려준다(결과 0건과 구분한다). 광고 결과(result--ad)는 뺀다.
 */
class DuckDuckGoSearchProvider implements WebSearchProvider {

	private static final URI ENDPOINT = URI.create("https://html.duckduckgo.com/html/");
	/** 비어 있는 User-Agent는 바로 막힌다. */
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

	private final HttpClient client;
	private final Duration timeout;

	DuckDuckGoSearchProvider(HttpClient client, Duration timeout) {
		this.client = client;
		this.timeout = timeout;
	}

	@Override
	public List<Result> search(String query, int limit) {
		HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(timeout)
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
				.build();
		HttpResponse<String> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException error) {
			throw new WebToolException("DuckDuckGo에 연결하지 못했다", error);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new WebToolException("검색이 중단됐다", interrupted);
		}
		// 봇 확인 페이지는 202로 온다.
		if (response.statusCode() != 200) throw new WebToolException("DuckDuckGo가 검색을 거절했다(HTTP " + response.statusCode() + ")");
		return parse(response.body(), limit);
	}

	static List<Result> parse(String html, int limit) {
		Document page = Jsoup.parse(html);
		if (page.selectFirst(".anomaly-modal__modal, #challenge-form") != null)
			throw new WebToolException("DuckDuckGo가 자동 요청으로 보고 막았다 — 잠시 뒤 다시 시도할 수 있다");
		Element container = page.selectFirst(".results, #links");
		if (container == null) throw new WebToolException("DuckDuckGo 결과 페이지를 해석하지 못했다");
		List<Result> results = new ArrayList<>();
		for (Element item : container.select(".result:not(.result--ad)")) {
			if (results.size() >= limit) break;
			Element link = item.selectFirst("a.result__a");
			if (link == null) continue;
			String url = targetUrl(link.attr("href"));
			if (url == null) continue;
			Element snippet = item.selectFirst(".result__snippet");
			results.add(new Result(link.text(), url, snippet == null ? "" : snippet.text()));
		}
		return results;
	}

	/** 결과 링크는 대상 주소이거나, DuckDuckGo 경유 링크(//duckduckgo.com/l/?uddg=<인코딩된 주소>)다. */
	static String targetUrl(String href) {
		if (href.startsWith("http://") || href.startsWith("https://")) {
			String host;
			try {
				host = URI.create(href).getHost();
			} catch (IllegalArgumentException malformed) {
				return null;
			}
			if (host != null && !host.endsWith("duckduckgo.com")) return href;
		}
		int at = href.indexOf("uddg=");
		if (at < 0) return null;
		int end = href.indexOf('&', at);
		String encoded = end < 0 ? href.substring(at + 5) : href.substring(at + 5, end);
		String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
		return decoded.startsWith("http://") || decoded.startsWith("https://") ? decoded : null;
	}
}
