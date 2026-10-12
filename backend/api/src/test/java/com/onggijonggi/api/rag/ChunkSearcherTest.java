package com.onggijonggi.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.api.authz.ThreadScopeFilter;
import com.onggijonggi.api.chat.ThreadDocumentScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ChunkSearcherTest.java
 * Description : 범위 조건의 모양(방 범위 없이는 검색하지 않음)과 두 채널의 순위 결합(RRF)·문서당 상한·점수 보존을 확인한다.
 *               태그 채널(#362)은 대표 조각 선택(벡터 결과 재사용 → 문서 안 kNN → 최소 유사도 미달 제외), 장애 시 두 채널 유지, 꺼짐이면 요청 없음을 본다.
 *               실제 Elasticsearch에서의 범위 격리는 ThreadDocumentSearchIntegrationTest가 본다.
 */
class ChunkSearcherTest {

	private final ChunkSearcher searcher = new ChunkSearcher(new RagProperties(
			new RagProperties.Elasticsearch("http://localhost:1", "thr_doc_chunk", Duration.ofSeconds(1)), null, null,
			new RagProperties.Search(3, 2, 20, 100, 0.5, "60%", 60, 2, 10), true), WebClient.builder(), JsonMapper.builder().build());

	private static ChunkSearcher.Hit hit(String doc, int seq, Double vector, Double keyword) {
		return new ChunkSearcher.Hit(doc + ":1:" + seq, doc, 1, seq, "para=" + seq, "본문" + seq, vector, keyword);
	}

	@Test
	void aChunkFoundByBothChannelsRanksFirstAndKeepsBothScores() {
		var semantic = List.of(hit("a", 1, 0.9, null), hit("a", 2, 0.8, null));
		var keyword = List.of(hit("b", 1, null, 7.0), hit("a", 2, null, 5.0));

		var fused = searcher.fuse(keyword, semantic);

		assertThat(fused.get(0).seq()).isEqualTo(2);
		assertThat(fused.get(0).document()).isEqualTo("a");
		assertThat(fused.get(0).vectorScore()).isEqualTo(0.8);
		assertThat(fused.get(0).keywordScore()).isEqualTo(5.0);
	}

	@Test
	void aKeywordOnlyHitIsKeptAndEachDocumentIsCapped() {
		var semantic = List.of(hit("a", 1, 0.9, null), hit("a", 2, 0.8, null), hit("a", 3, 0.7, null));
		var keyword = List.of(hit("b", 1, null, 7.0));

		var fused = searcher.fuse(keyword, semantic);

		assertThat(fused).hasSize(3);
		assertThat(fused).filteredOn(hit -> hit.document().equals("a")).hasSize(2);
		assertThat(fused).anySatisfy(hit -> assertThat(hit.document()).isEqualTo("b"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"timed_out\":true,\"hits\":{\"hits\":[]}}", "{\"_shards\":{\"failed\":1},\"hits\":{\"hits\":[]}}",
			"{\"took\":1}", "", "<html>proxy</html>"})
	void anIncompleteOrMalformedResponseIsAnOutageNotAnEmptyResult(String body) throws Exception {
		try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", 200, body)) {
			var stubbed = new ChunkSearcher(new RagProperties(new RagProperties.Elasticsearch(es.url(), "thr_doc_chunk", Duration.ofSeconds(2)),
					null, null, new RagProperties.Search(3, 2, 20, 100, 0.5, "75%", 60, 2, 10), true), WebClient.builder(), JsonMapper.builder().build());
			var scope = new ThreadDocumentScope(UUID.randomUUID(), ThreadScopeFilter.of(List.of(UUID.randomUUID())),
					List.of(new ThreadDocumentScope.Target(UUID.randomUUID(), "a.txt", 1, "bge-m3", 3)));

			assertThatThrownBy(() -> stubbed.search(scope, "연차", new float[] {1, 0, 0})).isInstanceOf(RagUnavailableException.class);
		}
	}

	@Test
	void theFilterBindsTenantRoomAndEachDocumentToItsCurrentRunAndRefusesAnUnscopedSearch() {
		UUID tenant = UUID.randomUUID(), room = UUID.randomUUID(), doc = UUID.randomUUID();
		var scope = new ThreadDocumentScope(tenant, ThreadScopeFilter.of(List.of(room)),
				List.of(new ThreadDocumentScope.Target(doc, "a.txt", 2, "bge-m3", 1024)));

		String filter = JsonMapper.builder().build().writeValueAsString(ChunkSearcher.filter(scope));

		assertThat(filter).contains("\"tnn_id\":\"" + tenant + "\"").contains("\"thr_id\":[\"" + room + "\"]")
				.contains("\"doc_id\":\"" + doc + "\"").contains("\"run_seq\":2").contains("\"minimum_should_match\":1");
		for (ThreadScopeFilter unscoped : List.of(ThreadScopeFilter.unrestricted(), ThreadScopeFilter.of(List.of())))
			assertThatThrownBy(() -> ChunkSearcher.filter(new ThreadDocumentScope(tenant, unscoped, scope.targets())))
					.isInstanceOf(IllegalArgumentException.class);
	}

	/** 벡터 점수는 _score(=(1+cos)/2)를 코사인 유사도로 바꾸고 [-1, 1]로 자른다. 두 채널 요청에 각 기준이 실린다. */
	@Test
	void scoresAreConvertedAndClampedAndEachChannelCarriesItsCriterion() throws Exception {
		String hits = "{\"hits\":{\"hits\":[{\"_score\":%s,\"_source\":{\"chunk_id\":\"c\",\"doc_id\":\"%s\",\"run_seq\":1,\"seq\":%d,\"loc\":\"para=1\",\"content\":\"본문\"}}]}}";
		UUID doc = UUID.randomUUID();
		var scope = new ThreadDocumentScope(UUID.randomUUID(), ThreadScopeFilter.of(List.of(UUID.randomUUID())),
				List.of(new ThreadDocumentScope.Target(doc, "a.txt", 1, "bge-m3", 3)));
		for (var expected : List.of(List.of("0.75", 0.5), List.of("1.0000001", 1.0))) {
			try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", 200, String.format(hits, expected.get(0), doc, 1))) {
				var stubbed = new ChunkSearcher(new RagProperties(new RagProperties.Elasticsearch(es.url(), "thr_doc_chunk", Duration.ofSeconds(2)),
						null, null, new RagProperties.Search(3, 2, 20, 100, 0.5, "75%", 60, 2, 10), true), WebClient.builder(), JsonMapper.builder().build());

				var result = stubbed.search(scope, "연차", new float[] {1, 0, 0});

				assertThat(result).singleElement().satisfies(hit -> assertThat(hit.vectorScore()).isEqualTo((Double) expected.get(1)));
				assertThat(es.requests).anySatisfy(request -> assertThat(request.body()).contains("\"minimum_should_match\":\"75%\""));
				assertThat(es.requests).anySatisfy(request -> assertThat(request.body()).contains("\"similarity\":0.5"));
			}
		}
	}

	@Test
	void aTagChannelHitAddsItsRankAndATagOnlyRepresentativeIsKept() {
		var semantic = List.of(hit("a", 1, 0.9, null), hit("b", 1, 0.8, null));
		var keyword = List.of(hit("a", 1, null, 7.0));
		var tag = List.of(hit("b", 1, 0.8, null), hit("c", 4, 0.4, null));

		var fused = searcher.fuse(List.of(semantic, keyword, tag));

		assertThat(fused).extracting(ChunkSearcher.Hit::document).containsExactly("a", "b", "c");
	}

	/** a는 벡터 결과의 조각을 대표로 재사용하고, b는 문서 안 kNN 1건을, c는 최소 유사도 미달(빈 결과)이라 뺀다. b·c의 검색은 한 요청으로 묶는다. */
	@Test
	void representativesReuseVectorHitsThenSearchInsideTheDocumentAndDropWeakOnes() throws Exception {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
		String chunk = "{\"_score\":0.9,\"_source\":{\"chunk_id\":\"%s:1:%d\",\"doc_id\":\"%s\",\"run_seq\":1,\"seq\":%d,\"loc\":\"para=1\",\"content\":\"본문\"}}";
		Function<String, StubHttpServer.Reply> chunks = body -> {
			if (body.contains("\"match\"")) return hits();
			return hits(String.format(chunk, a, 2, a, 2));
		};
		// 묶음 검색: 머리줄·본문 줄이 번갈아 온다. 본문마다 b면 조각 1건, 아니면 빈 결과(최소 유사도 미달)를 같은 순서로 돌려준다.
		Function<String, StubHttpServer.Reply> lookups = ndjson -> {
			List<String> responses = new java.util.ArrayList<>();
			String[] lines = ndjson.split("\n");
			for (int i = 1; i < lines.length; i += 2)
				responses.add(lines[i].contains(b.toString()) ? hits(String.format(chunk, b, 7, b, 7)).body() : hits().body());
			return new StubHttpServer.Reply(200, "{\"responses\":[" + String.join(",", responses) + "]}");
		};
		String tags = "{\"_source\":{\"doc_id\":\"%s\",\"run_seq\":1}}";
		try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", chunks).reply("/_msearch", lookups)
				.reply("/thr_doc_tag/_search", 200, "{\"hits\":{\"hits\":[" + String.format(tags, c) + "," + String.format(tags, b) + ","
						+ String.format(tags, a) + "]}}")) {
			var result = tagged(es, true).search(scope(a, b, c), "연차", new float[] {1, 0, 0});

			assertThat(result).extracting(ChunkSearcher.Hit::chunkId).containsExactly(a + ":1:2", b + ":1:7");
			assertThat(es.requests).filteredOn(request -> request.path().startsWith("/_msearch")).singleElement().satisfies(request -> {
				assertThat(request.body().split("\n")).as("b·c 두 검색(머리줄·본문)").hasSize(4);
				assertThat(request.body()).contains("\"index\":\"thr_doc_chunk\"").contains("\"similarity\":0.35").contains("\"k\":1,")
						.as("a는 벡터 결과를 재사용한다").doesNotContain(a.toString());
			});
			assertThat(es.requests).filteredOn(request -> request.path().startsWith("/thr_doc_tag")).singleElement()
					.satisfies(request -> assertThat(request.body()).contains("kyw^2").contains("\"minimum_should_match\":\"40%\"")
							// 조각 검색과 같은 범위(고객사·방·(문서, 회차)) 안의 태그만 찾는다.
							.contains("\"tnn_id\"").contains("\"thr_id\"").contains("\"doc_id\":\"" + a + "\"").contains("\"run_seq\":1"));
		}
	}

	/** 태그 채널이 실패하면(색인 없음) 두 채널로 계속하고, 1분 동안은 태그 채널을 다시 부르지 않다가 1분이 지나면 다시 시도한다. */
	@Test
	void aTagChannelOutageKeepsTheOtherChannelsAndRetriesAfterAMinute() throws Exception {
		UUID a = UUID.randomUUID();
		try (StubHttpServer es = outage(a)) {
			AtomicLong now = new AtomicLong(1_000_000);
			var searcher = tagged(es, true, clock(now));
			for (int i = 0; i < 2; i++)
				assertThat(searcher.search(scope(a), "연차", new float[] {1, 0, 0})).singleElement().satisfies(hit -> assertThat(hit.chunkId()).isEqualTo("c"));
			assertThat(tagRequests(es)).as("실패 뒤 1분 동안은 다시 부르지 않는다").isEqualTo(1);

			now.addAndGet(Duration.ofSeconds(61).toMillis());
			searcher.search(scope(a), "연차", new float[] {1, 0, 0});
			assertThat(tagRequests(es)).as("1분이 지나면 다시 시도한다(색인이 생겼을 수 있다)").isEqualTo(2);
		}
	}

	/** 태그 채널이 꺼져 있으면 태그 색인을 아예 부르지 않는다. */
	@Test
	void aDisabledTagChannelSendsNothing() throws Exception {
		UUID a = UUID.randomUUID();
		try (StubHttpServer es = outage(a)) {
			assertThat(tagged(es, false).search(scope(a), "연차", new float[] {1, 0, 0})).singleElement()
					.satisfies(hit -> assertThat(hit.chunkId()).isEqualTo("c"));
			assertThat(tagRequests(es)).isZero();
		}
	}

	/** 조각 검색은 조각 하나("c")를, 태그 색인은 404(색인 없음)를 돌려준다. */
	private static StubHttpServer outage(UUID document) throws Exception {
		String chunk = "{\"_score\":0.9,\"_source\":{\"chunk_id\":\"c\",\"doc_id\":\"" + document + "\",\"run_seq\":1,\"seq\":1,\"loc\":\"para=1\",\"content\":\"본문\"}}";
		return new StubHttpServer().reply("/thr_doc_chunk/_search", 200, "{\"hits\":{\"hits\":[" + chunk + "]}}")
				.reply("/thr_doc_tag/_search", 404, "{\"error\":{\"type\":\"index_not_found_exception\"}}");
	}

	private static long tagRequests(StubHttpServer es) {
		return es.requests.stream().filter(request -> request.path().startsWith("/thr_doc_tag")).count();
	}

	/** 테스트가 옮기는 시계. */
	private static Clock clock(AtomicLong millis) {
		return new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
		};
	}

	private static StubHttpServer.Reply hits(String... hits) {
		return new StubHttpServer.Reply(200, "{\"hits\":{\"hits\":[" + String.join(",", hits) + "]}}");
	}

	private static ThreadDocumentScope scope(UUID... documents) {
		return new ThreadDocumentScope(UUID.randomUUID(), ThreadScopeFilter.of(List.of(UUID.randomUUID())),
				List.of(documents).stream().map(document -> new ThreadDocumentScope.Target(document, "a.txt", 1, "bge-m3", 3)).toList());
	}

	private static ChunkSearcher tagged(StubHttpServer es, boolean enabled) {
		return tagged(es, enabled, Clock.systemUTC());
	}

	private static ChunkSearcher tagged(StubHttpServer es, boolean enabled, Clock clock) {
		return new ChunkSearcher(new RagProperties(new RagProperties.Elasticsearch(es.url(), "thr_doc_chunk", Duration.ofSeconds(2)), null, null,
				new RagProperties.Search(5, 2, 20, 100, 0.5, "75%", 60, 2, 10), true),
				new RagTagProperties(enabled, "thr_doc_tag", "40%", 10, 0.35), WebClient.builder(), JsonMapper.builder().build(), clock);
	}

	/** 묶음 검색이 모두 실패하면 태그 채널 전체를 장애로 보고 두 채널 결과만 돌려준 뒤, 1분 동안 태그 채널을 쉰다. */
	@Test
	void aWhollyFailedBatchSkipsTheTagChannel() throws Exception {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		String chunk = "{\"_score\":0.9,\"_source\":{\"chunk_id\":\"c\",\"doc_id\":\"" + a + "\",\"run_seq\":1,\"seq\":1,\"loc\":\"para=1\",\"content\":\"본문\"}}";
		try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", 200, "{\"hits\":{\"hits\":[" + chunk + "]}}")
				.reply("/thr_doc_tag/_search", 200, "{\"hits\":{\"hits\":[{\"_source\":{\"doc_id\":\"" + b + "\",\"run_seq\":1}}]}}")
				.reply("/_msearch", 200, "{\"responses\":[{\"error\":{\"type\":\"search_phase_execution_exception\"},\"status\":500}]}")) {
			var searcher = tagged(es, true);

			assertThat(searcher.search(scope(a, b), "연차", new float[] {1, 0, 0})).extracting(ChunkSearcher.Hit::chunkId).containsExactly("c");
			searcher.search(scope(a, b), "연차", new float[] {1, 0, 0});
			assertThat(es.requests).filteredOn(request -> request.path().startsWith("/thr_doc_tag")).hasSize(1);
		}
	}

	/** 묶음 검색의 일부만 실패하면 그 문서만 빼고 나머지 대표 조각은 쓴다 — 한 문서 탓에 태그 채널 전체를 끄지 않는다. */
	@Test
	void aPartlyFailedBatchDropsOnlyTheFailedDocument() throws Exception {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(), d = UUID.randomUUID();
		String vector = "{\"_score\":0.9,\"_source\":{\"chunk_id\":\"c\",\"doc_id\":\"" + a + "\",\"run_seq\":1,\"seq\":1,\"loc\":\"para=1\",\"content\":\"본문\"}}";
		String found = "{\"hits\":{\"hits\":[{\"_score\":0.75,\"_source\":{\"chunk_id\":\"d\",\"doc_id\":\"" + d
				+ "\",\"run_seq\":1,\"seq\":4,\"loc\":\"para=4\",\"content\":\"본문\"}}]}}";
		try (StubHttpServer es = new StubHttpServer().reply("/thr_doc_chunk/_search", 200, "{\"hits\":{\"hits\":[" + vector + "]}}")
				.reply("/thr_doc_tag/_search", 200, "{\"hits\":{\"hits\":[{\"_source\":{\"doc_id\":\"" + b + "\",\"run_seq\":1}},{\"_source\":{\"doc_id\":\""
						+ d + "\",\"run_seq\":1}}]}}")
				.reply("/_msearch", 200, "{\"responses\":[{\"error\":{\"type\":\"illegal_argument_exception\"},\"status\":400}," + found + "]}")) {
			var searcher = tagged(es, true);

			assertThat(searcher.search(scope(a, b, d), "연차", new float[] {1, 0, 0})).extracting(ChunkSearcher.Hit::chunkId).containsExactlyInAnyOrder("c", "d");
			searcher.search(scope(a, b, d), "연차", new float[] {1, 0, 0});
			assertThat(es.requests).as("태그 채널을 끄지 않았다").filteredOn(request -> request.path().startsWith("/thr_doc_tag")).hasSize(2);
		}
	}
}
