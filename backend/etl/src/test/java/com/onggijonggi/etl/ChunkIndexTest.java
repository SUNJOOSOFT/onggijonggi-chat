package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.common.document.Chunker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ChunkIndexTest.java
 * Description : Elasticsearch 응답별 분류를 가짜 ES로 확인한다. 실제 ES와의 적재·검색은 IngestionIntegrationTest가 본다.
 *               bulk 항목 오류(400 영구, 404 별칭 없음·429 일시), 인덱스 생성 400(이미 있음만 별칭으로), 삭제의 부분 실패.
 */
class ChunkIndexTest {

	private StubHttpServer es;
	private ChunkIndex index;
	private final RunStore.Job job = new RunStore.Job(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			1, 1, "a.txt", "a".repeat(64), null);
	private final List<Chunker.Chunk> chunks = List.of(new Chunker.Chunk("c:1:1", 1, "본문", "para=1"));
	private final List<float[]> vectors = List.of(new float[] {1, 0, 0});

	@BeforeEach
	void setUp() throws Exception {
		es = new StubHttpServer().reply("/_alias/", 200, "{\"thr_doc_chunk_v1\":{\"aliases\":{\"thr_doc_chunk\":{}}}}");
		var properties = new EtlProperties(null, new EtlProperties.Elasticsearch(es.url(), "thr_doc_chunk", "thr_doc_chunk_v1", 200, "thr_doc_tag", "thr_doc_tag_v1"),
				null, new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5));
		index = new ChunkIndex(properties, JsonMapper.builder().build());
	}

	@AfterEach
	void tearDown() {
		es.close();
	}

	private static String bulkItemError(int status) {
		return "{\"errors\":true,\"items\":[{\"index\":{\"status\":" + status + ",\"error\":{\"reason\":\"r\"}}}]}";
	}

	@Test
	void bulkItemErrorsAreClassifiedAndAMissingAliasIsRecreatedNextTime() {
		es.reply("/thr_doc_chunk/_bulk", 200, bulkItemError(400));
		assertThatThrownBy(() -> index.write(job, chunks, vectors, "bge-m3"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isTrue());

		es.reply("/thr_doc_chunk/_bulk", 200, bulkItemError(429));
		assertThatThrownBy(() -> index.write(job, chunks, vectors, "bge-m3"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());

		es.reply("/thr_doc_chunk/_bulk", 200, bulkItemError(404));
		assertThatThrownBy(() -> index.write(job, chunks, vectors, "bge-m3"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
		int aliasChecks = (int) es.requests.stream().filter(request -> request.path().startsWith("/_alias/")).count();
		es.reply("/thr_doc_chunk/_bulk", 200, "{\"errors\":false,\"items\":[]}");
		index.write(job, chunks, vectors, "bge-m3");
		assertThat(es.requests.stream().filter(request -> request.path().startsWith("/_alias/")).count())
				.as("별칭이 없다는 응답 뒤에는 다시 확인한다").isEqualTo(aliasChecks + 1);
		assertThat(es.requests).anySatisfy(request -> assertThat(request.path()).contains("require_alias=true"));
	}

	@Test
	void indexCreationRejectionIsPermanentUnlessTheIndexAlreadyExists() {
		es.reply("/_alias/", 404, "{}").reply("/thr_doc_chunk_v1", 400, "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"nori_tokenizer not found\"}}");
		assertThatThrownBy(() -> index.ensure())
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("INDEX_REJECTED");
					assertThat(failure.getMessage()).contains("nori_tokenizer");
				});

		es.reply("/thr_doc_chunk_v1", 400, "{\"error\":{\"type\":\"resource_already_exists_exception\"}}")
				.reply("/thr_doc_chunk_v1/_alias/", 200, "{}");
		index.ensure();
		assertThat(es.requests).anySatisfy(request -> assertThat(request.path()).isEqualTo("/thr_doc_chunk_v1/_alias/thr_doc_chunk"));
	}

	@Test
	void aliasMissingWhileCountingIsTransientAndRecreatedNextTime() {
		es.reply("/thr_doc_chunk/_count", 404, "{}");
		assertThatThrownBy(() -> index.count(job.document(), 1))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("INDEX_UNAVAILABLE");
					assertThat(failure.permanent()).isFalse();
				});
		long aliasChecks = es.requests.stream().filter(request -> request.path().startsWith("/_alias/")).count();
		es.reply("/thr_doc_chunk/_bulk", 200, "{\"errors\":false,\"items\":[]}");
		index.write(job, chunks, vectors, "bge-m3");
		assertThat(es.requests.stream().filter(request -> request.path().startsWith("/_alias/")).count())
				.as("404 뒤에는 별칭을 다시 확인한다").isEqualTo(aliasChecks + 1);
	}

	@Test
	void deletionNeitherChecksNorCreatesTheIndex() {
		es.reply("/thr_doc_chunk/_delete_by_query", 404, "{}");
		index.delete(job.document(), 1);
		assertThat(es.requests).as("정리 작업은 별칭을 확인하거나 인덱스를 만들지 않는다")
				.noneSatisfy(request -> assertThat(request.path()).matches("/_alias/.*|/thr_doc_chunk_v1(\\?.*)?"));
	}

	@Test
	void aFailedOrShortMigrationKeepsTheAliasOnThePreviousIndex() throws Exception {
		try (StubHttpServer migrating = new StubHttpServer()) {
			migrating.reply("/_alias/", 200, "{\"thr_doc_chunk_v1\":{\"aliases\":{\"thr_doc_chunk\":{}}}}")
					.reply("/thr_doc_chunk_v2", 200, "{}")
					.reply("/_reindex", 200, "{\"task\":\"node:1\"}")
					.reply("/_tasks/", 200, "{\"completed\":true,\"error\":{\"type\":\"search_phase_execution_exception\"}}");
			var properties = new EtlProperties(null, new EtlProperties.Elasticsearch(migrating.url(), "thr_doc_chunk", "thr_doc_chunk_v2", 200, "thr_doc_tag", "thr_doc_tag_v1"),
					null, new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5));
			assertThatThrownBy(() -> new ChunkIndex(properties, JsonMapper.builder().build()).ensure())
					.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());

			migrating.reply("/_tasks/", 200, "{\"completed\":true,\"response\":{\"timed_out\":false,\"failures\":[]}}")
					.reply("/thr_doc_chunk_v1/_count", 200, "{\"count\":5}")
					.reply("/thr_doc_chunk_v2/_count", 200, "{\"count\":3}");
			assertThatThrownBy(() -> new ChunkIndex(properties, JsonMapper.builder().build()).ensure())
					.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());

			assertThat(migrating.requests).as("옮기기가 실패하거나 모자라면 별칭을 넘기지 않는다")
					.noneSatisfy(request -> assertThat(request.path()).startsWith("/_aliases"));
		}
	}

	/** 상태 조회가 잇달아 끊겨 이전이 일시 실패로 끝나도, 다음 시도는 복사를 새로 시작하지 않고 같은 작업을 이어서 기다린다. */
	@Test
	void aMigrationWhosePollingFailedResumesTheSameTask() throws Exception {
		try (StubHttpServer migrating = new StubHttpServer()) {
			migrating.reply("/_alias/", 200, "{\"thr_doc_chunk_v1\":{\"aliases\":{\"thr_doc_chunk\":{}}}}")
					.reply("/thr_doc_chunk_v2", 200, "{}")
					.reply("/_reindex", 200, "{\"task\":\"node:1\"}")
					.reply("/_tasks/", 503, "{}");
			var properties = new EtlProperties(null, new EtlProperties.Elasticsearch(migrating.url(), "thr_doc_chunk", "thr_doc_chunk_v2", 200, "thr_doc_tag", "thr_doc_tag_v1"),
					null, new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5));
			ChunkIndex migratingIndex = new ChunkIndex(properties, JsonMapper.builder().build());
			assertThatThrownBy(migratingIndex::ensure).isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
			// 이전 작업이 아직 돌고 있는 동안 정리 작업의 삭제는 미룬다(이전 전 인덱스에서 지우면 새 인덱스에 남는다).
			assertThatThrownBy(() -> migratingIndex.delete(job.document(), 1))
					.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
			assertThat(migrating.requests).noneSatisfy(request -> assertThat(request.path()).contains("_delete_by_query"));

			migrating.reply("/_tasks/", 200, "{\"completed\":true,\"response\":{\"timed_out\":false,\"failures\":[]}}")
					.reply("/thr_doc_chunk_v1/_count", 200, "{\"count\":3}")
					.reply("/thr_doc_chunk_v2/_count", 200, "{\"count\":3}")
					.reply("/_aliases", 200, "{}");
			migratingIndex.ensure();

			assertThat(migrating.requests.stream().filter(request -> request.path().startsWith("/_reindex")).count()).isEqualTo(1);
			assertThat(migrating.requests).anySatisfy(request -> assertThat(request.path()).isEqualTo("/_aliases"));
		}
	}

	/** 자동 복구(#348)의 대조: 문서마다 현재 회차만 세고, 일부 샤드만 답한 집계는 쓰지 않으며, 별칭이 없으면 다시 준비하게 한다. */
	@Test
	void currentRunCountsAreReadAndAnIncompleteOrMissingIndexIsNotTreatedAsEmpty() {
		UUID doc = job.document();
		es.reply("/thr_doc_chunk/_search", 200, "{\"timed_out\":false,\"_shards\":{\"failed\":0},\"aggregations\":{\"docs\":{\"buckets\":["
				+ "{\"key\":\"" + doc + "\",\"doc_count\":3}]}}}");
		assertThat(index.runCounts(java.util.Map.of(doc, 2))).containsExactly(java.util.Map.entry(doc, 3L));
		assertThat(es.requests.get(es.requests.size() - 1).body()).as("그 문서의 현재 회차만 센다").contains("\"run_seq\":2").contains(doc.toString());
		assertThat(index.runCounts(java.util.Map.of())).isEmpty();
		UUID other = UUID.randomUUID();
		index.runCounts(java.util.Map.of(doc, 2, other, 1));
		tools.jackson.databind.JsonNode sent = JsonMapper.builder().build().readTree(es.requests.get(es.requests.size() - 1).body());
		assertThat(sent.path("query").path("bool").path("should").size()).as("문서마다 (문서, 현재 회차) 하나씩").isEqualTo(2);
		assertThat(sent.path("aggs").path("docs").path("terms").path("size").asInt()).isEqualTo(2);

		for (String incomplete : List.of("{\"timed_out\":true,\"_shards\":{\"failed\":0},\"aggregations\":{\"docs\":{\"buckets\":[]}}}",
				"{\"timed_out\":false,\"_shards\":{\"failed\":0}}")) {
			es.reply("/thr_doc_chunk/_search", 200, incomplete);
			assertThatThrownBy(() -> index.runCounts(java.util.Map.of(doc, 2)))
					.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
		}

		es.reply("/thr_doc_chunk/_search", 200, "{\"timed_out\":false,\"_shards\":{\"failed\":1},\"aggregations\":{\"docs\":{\"buckets\":[]}}}");
		assertThatThrownBy(() -> index.runCounts(java.util.Map.of(doc, 2)))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());

		index.ensure();
		assertThat(index.prepared()).isTrue();
		es.reply("/thr_doc_chunk/_search", 404, "{}");
		assertThatThrownBy(() -> index.runCounts(java.util.Map.of(doc, 2)))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
		assertThat(index.prepared()).as("다음 준비 때 인덱스를 다시 만든다").isFalse();
	}

	@Test
	void partialOrTimedOutDeletionIsNotTreatedAsDone() {
		es.reply("/thr_doc_chunk/_delete_by_query", 200, "{\"timed_out\":false,\"failures\":[{\"cause\":\"shard\"}]}");
		assertThatThrownBy(() -> index.delete(job.document(), 1))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());

		es.reply("/thr_doc_chunk/_delete_by_query", 200, "{\"timed_out\":true,\"failures\":[]}");
		assertThatThrownBy(() -> index.delete(job.document(), 1)).isInstanceOf(EtlFailure.class);

		es.reply("/thr_doc_chunk/_delete_by_query", 200, "{\"timed_out\":false,\"failures\":[],\"deleted\":3}");
		index.delete(job.document(), 1);
		es.reply("/thr_doc_chunk/_delete_by_query", 404, "{}");
		index.delete(job.document(), 1);
	}
}
