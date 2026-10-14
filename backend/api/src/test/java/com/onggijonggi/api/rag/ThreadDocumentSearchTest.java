package com.onggijonggi.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.ThreadScopeFilter;
import com.onggijonggi.api.chat.SearchableDocument;
import com.onggijonggi.api.chat.ThreadDocumentScope;
import com.onggijonggi.api.chat.ThreadDocumentService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Class Name : ThreadDocumentSearchTest.java
 * Description : 검색 전용 스케줄러(격벽)의 대기열이 가득 차도 방 접근 확인은 먼저 끝나(없는 방·남의 방은 404) 외부 호출 없이
 *               UNAVAILABLE이 되는지 확인한다. 정상 흐름은 ThreadDocumentSearchIntegrationTest가 본다.
 */
class ThreadDocumentSearchTest {

	private final ThreadDocumentService documents = mock(ThreadDocumentService.class);
	private final QueryEmbedder embedder = mock(QueryEmbedder.class);
	private final ThreadDocumentSearch search = new ThreadDocumentSearch(documents, mock(QueryRewriter.class), embedder,
			mock(ChunkSearcher.class), Schedulers.fromExecutor(task -> { throw new RejectedExecutionException("full"); }));
	private final UUID room = UUID.randomUUID();
	private final CurrentActor actor = new CurrentActor(UUID.randomUUID(), "s", "s");

	@Test
	void aFullSearchQueueIsUnavailableAfterTheAccessCheckWithoutExternalCalls() {
		when(documents.searchScope(eq(room), any())).thenReturn(new ThreadDocumentScope(UUID.randomUUID(), ThreadScopeFilter.of(List.of(room)),
				List.of(new ThreadDocumentScope.Target(UUID.randomUUID(), "a.txt", 1, "bge-m3", 1024))));

		SearchResult result = search.search(room, actor, "연차", List.of(), null).block();

		assertThat(result.status()).isEqualTo(SearchResult.Status.UNAVAILABLE);
		assertThat(result.reason()).isEqualTo(SearchResult.Reason.OVERLOADED);
		verify(documents).searchScope(eq(room), any());
		verifyNoInteractions(embedder);
	}

	/** 답변 턴이 검색 도구를 붙일지 정하는 문서 목록은 DB만 읽는다. 외부 호출이 없어 검색 대기열과 무관하다. */
	@Test
	void listsTheSearchableDocumentsEvenWhenTheSearchQueueIsFull() {
		List<SearchableDocument> listed = List.of(SearchableDocument.untagged("a.txt"),
				new SearchableDocument("b.pdf", "인사·총무", List.of("연차"), "연차 규정"));
		when(documents.searchableDocuments(eq(room), any())).thenReturn(listed);

		assertThat(search.searchableDocuments(room, actor).block()).isEqualTo(listed);
		verifyNoInteractions(embedder);
	}

	@Test
	void aRefusedAccessStaysNotFoundEvenWhenTheSearchQueueIsFull() {
		when(documents.searchScope(eq(room), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

		StepVerifier.create(search.search(room, actor, "연차", List.of(), null))
				.expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
				.verify();
		verifyNoInteractions(embedder);
	}

	/** 실제 검색 스케줄러(RagConfiguration)가 포화되면 거절하고, 검색은 그 거절을 UNAVAILABLE로 바꾼다. */
	@Test
	void theRealSearchSchedulerRejectsWhenSaturatedAndSearchTurnsItIntoUnavailable() throws Exception {
		var properties = new RagProperties(null, null, null, new RagProperties.Search(5, 3, 20, 100, 0.5, "75%", 60, 1, 1), true);
		Scheduler scheduler = new RagConfiguration().ragSearchScheduler(properties);
		CountDownLatch release = new CountDownLatch(1);
		try {
			CountDownLatch running = new CountDownLatch(1);
			Runnable blocked = () -> {
				running.countDown();
				try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
			};
			// 스레드 1개가 작업 중이고 대기열 1칸이 찬 상태를 만든다.
			scheduler.schedule(blocked);
			assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
			scheduler.schedule(blocked);
			assertThatThrownBy(() -> scheduler.schedule(() -> { })).isInstanceOf(RejectedExecutionException.class);
			when(documents.searchScope(eq(room), any())).thenReturn(new ThreadDocumentScope(UUID.randomUUID(), ThreadScopeFilter.of(List.of(room)),
					List.of(new ThreadDocumentScope.Target(UUID.randomUUID(), "a.txt", 1, "bge-m3", 1024))));
			var saturated = new ThreadDocumentSearch(documents, mock(QueryRewriter.class), embedder, mock(ChunkSearcher.class), scheduler);

			assertThat(saturated.search(room, actor, "연차", List.of(), null).block().status()).isEqualTo(SearchResult.Status.UNAVAILABLE);
		} finally {
			release.countDown();
			scheduler.dispose();
		}
	}
}
