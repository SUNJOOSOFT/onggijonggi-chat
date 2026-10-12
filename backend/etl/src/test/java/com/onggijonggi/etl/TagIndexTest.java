package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.common.document.TagPrompt;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : TagIndexTest.java
 * Description : 태그 검색 인덱스(#362)의 생성·쓰기·삭제를 가짜 ES로 확인한다 — 별칭이 없으면 매핑으로 인덱스와 별칭을 만들고, 별칭으로
 *               문서를 쓰며, 별칭이 사라지면 다시 만들 수 있게 일시 실패로 끝내고, 삭제는 멱등이다.
 */
class TagIndexTest {

	private StubHttpServer es;
	private TagIndex index;
	private final TagStore.Target target = new TagStore.Target(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, "a.txt", "d", null);

	@BeforeEach
	void setUp() throws Exception {
		es = new StubHttpServer();
		var properties = new EtlProperties(null, new EtlProperties.Elasticsearch(es.url(), "thr_doc_chunk", "thr_doc_chunk_v2", 200, "thr_doc_tag", "thr_doc_tag_v1"),
				null, new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(5));
		index = new TagIndex(properties, JsonMapper.builder().build());
	}

	@AfterEach
	void tearDown() {
		es.close();
	}

	@Test
	void aMissingAliasCreatesTheIndexFromTheMappingAndDocumentsAreWrittenThroughTheAlias() {
		es.reply("/_alias/", 404, "{}").reply("/thr_doc_tag_v1", 200, "{}").reply("/thr_doc_tag/_doc/", 201, "{}");

		assertThat(index.ensure()).isTrue();
		index.write(target, new TagPrompt.Tags("기타", List.of("연차"), "요약"), "tag-v1:x");

		var create = es.requests.stream().filter(request -> request.path().equals("/thr_doc_tag_v1")).findFirst().orElseThrow();
		JsonNode body = JsonMapper.builder().build().readTree(create.body());
		assertThat(body.path("aliases").has("thr_doc_tag")).isTrue();
		assertThat(body.path("mappings").path("properties").has("kyw")).isTrue();
		var write = es.requests.get(es.requests.size() - 1);
		// 색인 문서 ID의 콜론은 주소에서 %3A로 인코딩된다(ES가 풀어 같은 ID로 저장한다).
		assertThat(write.path()).startsWith("/thr_doc_tag/_doc/" + target.document() + "%3A2").contains("require_alias=true");
		JsonNode document = JsonMapper.builder().build().readTree(write.body());
		assertThat(document.path("kyw").get(0).asString()).isEqualTo("연차");
		assertThat(document.path("run_seq").asInt()).isEqualTo(2);
	}

	/** 태깅 주기마다 별칭을 다시 확인하고, 새로 만들었는지 알린다. */
	@Test
	void eachEnsureLooksAgainAndTellsWhetherItCreatedTheIndex() {
		es.reply("/_alias/", 200, "{\"thr_doc_tag_v1\":{\"aliases\":{\"thr_doc_tag\":{}}}}");
		assertThat(index.ensure()).isFalse();
		es.reply("/_alias/", 404, "{}").reply("/thr_doc_tag_v1", 200, "{}");
		assertThat(index.ensure()).as("사라져서 새로 만들었다").isTrue();
	}

	/** DB 태그를 한 요청(_bulk)으로 다시 쓰고, 일부라도 실패하면 일시 장애다. */
	@Test
	void storedTagsAreRestoredInOneBulkRequest() {
		var stored = new TagStore.Stored(UUID.randomUUID(), target.document(), target.tenant(), target.thread(), 2,
				new TagPrompt.Tags("기타", List.of("연차"), "요약"), "tag-v1:x");
		es.reply("/thr_doc_tag/_bulk", 200, "{\"errors\":false,\"items\":[]}");
		index.restore(List.of(stored));
		var bulk = es.requests.get(es.requests.size() - 1);
		assertThat(bulk.path()).startsWith("/thr_doc_tag/_bulk").contains("require_alias=true");
		assertThat(bulk.body().split("\n")).hasSize(2);
		assertThat(bulk.body()).contains("\"_id\":\"" + target.document() + ":2\"").contains("\"kyw\":[\"연차\"]");

		es.reply("/thr_doc_tag/_bulk", 200, "{\"errors\":true,\"items\":[]}");
		assertThatThrownBy(() -> index.restore(List.of(stored)))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
	}

	@Test
	void aVanishedAliasIsTransientAndDeletionIsIdempotent() {
		es.reply("/_alias/", 200, "{\"thr_doc_tag_v1\":{\"aliases\":{\"thr_doc_tag\":{}}}}").reply("/thr_doc_tag/_doc/", 404, "{}");

		assertThatThrownBy(() -> index.write(target, TagPrompt.Tags.unclassified(), "c"))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.permanent()).isFalse());
		// 다음 태깅 주기가 별칭을 다시 확인해 인덱스를 다시 만든다(ES 초기화 뒤 ETL을 재기동하지 않아도 복구된다).
		es.reply("/_alias/", 404, "{}").reply("/thr_doc_tag_v1", 200, "{}").reply("/thr_doc_tag/_doc/", 201, "{}");
		assertThat(index.ensure()).isTrue();
		index.write(target, TagPrompt.Tags.unclassified(), "c");
		assertThat(es.requests).anySatisfy(request -> assertThat(request.method() + " " + request.path()).isEqualTo("PUT /thr_doc_tag_v1"));
		index.delete(target.document(), 2);
		assertThat(es.requests).anySatisfy(request -> assertThat(request.method()).isEqualTo("DELETE"));
	}
}
