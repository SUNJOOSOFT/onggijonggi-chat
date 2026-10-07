package com.onggijonggi.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 검색 공급자 응답 해석. DuckDuckGo 표본은 실제 html.duckduckgo.com/html 결과 페이지의 구조를 줄인 것이다. */
class SearchProviderParseTest {

	private static final String DDG = """
			<html><body><div class="serp__results"><div id="links" class="results">
			  <div class="result results_links results_links_deep result--ad">
			    <h2 class="result__title"><a class="result__a" href="https://duckduckgo.com/y.js?ad=1">광고</a></h2>
			  </div>
			  <div class="result results_links results_links_deep web-result ">
			    <h2 class="result__title"><a class="result__a" href="https://spring.io/">Spring | Home</a></h2>
			    <a class="result__snippet" href="https://spring.io/">Spring makes Java simple.</a>
			  </div>
			  <div class="result results_links results_links_deep web-result ">
			    <h2 class="result__title"><a class="result__a"
			      href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.spring.io%2Fspring%2Dframework%2Freference%2F&amp;rut=abc">Reference</a></h2>
			  </div>
			  <div class="result results_links results_links_deep web-result ">
			    <h2 class="result__title"><a class="result__a" href="https://example.com/third">Third</a></h2>
			  </div>
			</div></div></body></html>
			""";

	@Test
	void parsesDuckDuckGoResultsSkippingAdsAndDecodingRedirectLinks() {
		assertThat(DuckDuckGoSearchProvider.parse(DDG, 2)).containsExactly(
				new WebSearchProvider.Result("Spring | Home", "https://spring.io/", "Spring makes Java simple."),
				new WebSearchProvider.Result("Reference", "https://docs.spring.io/spring-framework/reference/", ""));
	}

	@Test
	void reportsDuckDuckGoBotCheckAsFailureNotAsNoResults() {
		assertThatThrownBy(() -> DuckDuckGoSearchProvider.parse(
				"<html><body><div class=\"anomaly-modal__modal\">bots</div></body></html>", 5))
				.isInstanceOf(WebToolException.class).hasMessageContaining("막았다");
		assertThatThrownBy(() -> DuckDuckGoSearchProvider.parse("<html><body>바뀐 페이지</body></html>", 5))
				.isInstanceOf(WebToolException.class).hasMessageContaining("해석하지 못했다");
	}

	@Test
	void emptyDuckDuckGoResultListIsNoResults() {
		assertThat(DuckDuckGoSearchProvider.parse("<html><body><div class=\"results\"></div></body></html>", 5)).isEmpty();
	}

	@Test
	void parsesSearxngJsonAndSkipsNonHttpUrls() {
		String body = """
				{"query":"spring","results":[
				  {"url":"https://spring.io/","title":"Spring","content":"Java"},
				  {"url":"magnet:?xt=urn","title":"Torrent","content":""},
				  {"url":"https://docs.spring.io/","title":"Docs"}
				]}""";
		assertThat(SearxngSearchProvider.parse(new ObjectMapper(), body, 5)).containsExactly(
				new WebSearchProvider.Result("Spring", "https://spring.io/", "Java"),
				new WebSearchProvider.Result("Docs", "https://docs.spring.io/", ""));
	}

	@Test
	void reportsUnreadableSearxngResponseAsFailure() {
		assertThatThrownBy(() -> SearxngSearchProvider.parse(new ObjectMapper(), "<html>proxy error</html>", 5))
				.isInstanceOf(WebToolException.class);
		assertThatThrownBy(() -> SearxngSearchProvider.parse(new ObjectMapper(), "{}", 5))
				.isInstanceOf(WebToolException.class);
	}
}
