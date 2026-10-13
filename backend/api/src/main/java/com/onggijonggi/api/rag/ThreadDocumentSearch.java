package com.onggijonggi.api.rag;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.chat.ChatMessage;
import com.onggijonggi.api.chat.SearchableDocument;
import com.onggijonggi.api.chat.ThreadDocumentScope;
import com.onggijonggi.api.chat.ThreadDocumentService;
import com.onggijonggi.api.common.TraceIdWebFilter;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadDocumentSearch.java
 * Description : 방 문서 검색(#344)의 흐름. ① 방 접근 확인과 검색 범위(ThreadDocumentService.searchScope — 실패는 404, 검색 전에 끝난다)
 *               → ② 대상 문서가 없으면 근거 없음(임베딩·LLM을 부르지 않는다) → ③ 설정·모델 확인 → ④ 후속 질문이면 검색 문장 다시 쓰기
 *               → ⑤ 질문 임베딩 → ⑥ 하이브리드 검색. 장애는 UNAVAILABLE로 돌려주고 근거 없음으로 숨기지 않는다.
 *               권한은 턴 시작 시 한 번 확인한다 — 답변이 닿는 길(협업방 구독·기록 재조회)은 기존 회수·접근 확인이 지킨다.
 */
@Service
public class ThreadDocumentSearch {

	private static final Logger log = LoggerFactory.getLogger(ThreadDocumentSearch.class);

	private final ThreadDocumentService documents;
	private final QueryRewriter rewriter;
	private final QueryEmbedder embedder;
	private final ChunkSearcher searcher;
	private final Scheduler scheduler;

	public ThreadDocumentSearch(ThreadDocumentService documents, QueryRewriter rewriter, QueryEmbedder embedder, ChunkSearcher searcher,
			@Qualifier("ragSearchScheduler") Scheduler scheduler) {
		this.documents = documents;
		this.rewriter = rewriter;
		this.embedder = embedder;
		this.searcher = searcher;
		this.scheduler = scheduler;
	}

	/**
	 * 지금 검색할 수 있는 문서(고정·READY)와 그 태그. 답변 턴이 검색 도구를 붙일지, 모델에 어떤 문서가 있는지 알릴지 정할 때 쓴다.
	 * 범위는 search와 같은 searchScope라 방 접근 확인도 같다.
	 */
	public Mono<List<SearchableDocument>> searchableDocuments(UUID thread, CurrentActor actor) {
		return Mono.fromCallable(() -> documents.searchableDocuments(thread, actor))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** history는 질문 앞의 대화(오래된 것부터), modelId는 그 대화의 모델(다시 쓰기 기본값)이다. */
	public Mono<SearchResult> search(UUID thread, CurrentActor actor, String question, List<ChatMessage> history, String modelId) {
		// 장애는 200 응답의 UNAVAILABLE이라 공통 오류 로그에 남지 않는다. 응답 헤더 X-Trace-Id로 찾을 수 있게 로그에 traceId를 단다.
		return Mono.deferContextual(context -> {
			String trace = context.getOrDefault(TraceIdWebFilter.TRACE_ID_ATTR, "-");
			// 방 접근 확인(DB, 가볍다)은 공용 boundedElastic에서 먼저 한다 — 검색 대기열이 차도 남의 방·없는 방은 404로 끝나야 한다.
			// 외부 호출(다시 쓰기·임베딩·ES)만 검색 전용 스케줄러로 보낸다.
			return Mono.fromCallable(() -> documents.searchScope(thread, actor))
					.subscribeOn(Schedulers.boundedElastic())
					.flatMap(scope -> Mono.fromCallable(() -> run(thread, trace, scope, question, history, modelId))
							.subscribeOn(scheduler)
							.onErrorResume(RejectedExecutionException.class, full -> {
								log.warn("방 문서 검색 대기열이 가득 찼다: thread={} traceId={}", thread, trace);
								return Mono.just(SearchResult.unavailable(SearchResult.Reason.OVERLOADED, question));
							}));
		});
	}

	private SearchResult run(UUID thread, String trace, ThreadDocumentScope scope, String question, List<ChatMessage> history,
			String modelId) {
		if (scope.targets().isEmpty()) return SearchResult.noEvidence(SearchResult.Reason.NO_PINNED_DOCUMENTS, question, false);
		long started = System.nanoTime();
		try {
			if (!embedder.configured()) throw new RagUnavailableException(SearchResult.Reason.NOT_CONFIGURED, "임베딩 주소(app.rag.embedding.url)가 비어 있다");
			for (ThreadDocumentScope.Target target : scope.targets()) {
				// 다른 모델로 임베딩한 문서 벡터와 질문 벡터를 섞지 않는다 — 차원이 같아도 모델이 다르면 유사도가 의미 없다. 모델을 바꿨으면 문서를 다시 처리해야 한다.
				if (!embedder.model().equals(target.embeddingModel()) || embedder.dimensions() != target.dimensions())
					throw new RagUnavailableException(SearchResult.Reason.MODEL_MISMATCH, "문서 임베딩 모델(" + target.embeddingModel() + "/" + target.dimensions()
							+ ")이 검색 설정(" + embedder.model() + "/" + embedder.dimensions() + ")과 다르다 — 재처리가 필요하다: doc=" + target.document());
			}
			// 색인 본문과 같이 NFC로 맞춘다(macOS 등에서 온 분해형 한글이 키워드와 어긋나지 않게).
			QueryRewriter.Result query = rewriter.rewrite(Normalizer.normalize(question, Normalizer.Form.NFC), history, modelId);
			List<ChunkSearcher.Hit> hits = searcher.search(scope, query.query(), embedder.embed(query.query()));
			Map<String, ThreadDocumentScope.Target> byId = scope.targets().stream()
					.collect(Collectors.toMap(target -> target.document().toString(), Function.identity()));
			// 검색 조건에 이미 (문서, 현재 회차)가 걸려 있지만, 범위 밖 청크가 응답에 섞여도 내보내지 않게 한 번 더 대조한다.
			List<SearchResult.Chunk> chunks = hits.stream()
					.filter(hit -> byId.containsKey(hit.document()) && byId.get(hit.document()).runSeq() == hit.runSeq())
					.map(hit -> new SearchResult.Chunk(hit.chunkId(), UUID.fromString(hit.document()),
							byId.get(hit.document()).fileName(), hit.runSeq(),
							hit.seq(), hit.loc(), hit.content(), hit.vectorScore(), hit.keywordScore()))
					.toList();
			log.debug("방 문서 검색: thread={} traceId={} 대상 {}건 결과 {}건 다시 쓰기 {} {}ms", thread, trace, scope.targets().size(), chunks.size(),
					query.rewritten(), (System.nanoTime() - started) / 1_000_000);
			return chunks.isEmpty() ? SearchResult.noEvidence(SearchResult.Reason.NO_MATCH, query.query(), query.rewritten())
					: SearchResult.found(query.query(), query.rewritten(), chunks);
		} catch (RagUnavailableException unavailable) {
			log.warn("방 문서 검색을 끝내지 못했다: thread={} traceId={} reason={} {}", thread, trace, unavailable.reason(), unavailable.getMessage(),
					unavailable.getCause());
			return SearchResult.unavailable(unavailable.reason(), question);
		}
	}
}
