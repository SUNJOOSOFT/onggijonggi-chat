package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ChunkIndexMigrationTest.java
 * Description : 분석기를 바꿔 인덱스 이름을 올린 배포(#344, v1 → v2)에서 ETL이 이전 인덱스의 청크를 새 인덱스로 옮기고 별칭을 넘기는지
 *               실제 nori Elasticsearch로 확인한다. ES 9.x는 벡터를 _source에서 빼고 저장하므로, 옮긴 뒤에도 kNN 검색이 그대로 되는지
 *               (벡터가 함께 옮겨졌는지)와 본문이 새 분석기(조사 제거)로 다시 분석됐는지를 본다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChunkIndexMigrationTest {

	private static final String ALIAS = "thr_doc_chunk";
	private static final int DIMENSIONS = 1024;

	@Container static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(new ImageFromDockerfile("ogjg-test/elasticsearch-nori", false)
			.withFileFromPath(".", Path.of("../../infra/elasticsearch")))
			.withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
			.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
			.waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).withStartupTimeout(Duration.ofMinutes(4)));

	private final ObjectMapper json = JsonMapper.builder().build();

	@Test
	void chunksOfThePreviousIndexMoveWithTheirVectorsAndTheAliasFollows() throws IOException {
		RestClient es = RestClient.create(url());
		// v1(#340)의 매핑으로 만든 인덱스와 청크.
		byte[] v1;
		try (InputStream in = new ClassPathResource("es-thr-doc-chunk-index-v1.json").getInputStream()) {
			v1 = in.readAllBytes();
		}
		es.put().uri("/thr_doc_chunk_v1").contentType(MediaType.APPLICATION_JSON).body(v1).retrieve().toBodilessEntity();
		es.put().uri("/thr_doc_chunk_v1/_alias/" + ALIAS).retrieve().toBodilessEntity();
		UUID doc = UUID.randomUUID();
		for (int seq = 1; seq <= 3; seq++) put(es, doc, seq, seq == 1 ? "연차는 다음 해 3월 말까지 이월할 수 있다." : "청크 " + seq, seq - 1);
		es.post().uri("/" + ALIAS + "/_refresh").retrieve().toBodilessEntity();

		ChunkIndex index = index("thr_doc_chunk_v2");
		index.ensure();

		JsonNode aliases = json.readTree(es.get().uri("/_alias/" + ALIAS).retrieve().body(String.class));
		assertThat(aliases.propertyNames()).containsExactly("thr_doc_chunk_v2");
		assertThat(index.count(doc, 1)).isEqualTo(3);
		// 벡터가 옮겨졌다: 1번 청크의 벡터로 kNN을 하면 그 청크가 유사도 1로 나온다.
		JsonNode knn = json.readTree(es.post().uri("/" + ALIAS + "/_search").contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsString(Map.of("size", 1, "knn", Map.of("field", "emb", "query_vector", unit(0), "k", 1, "num_candidates", 10))))
				.retrieve().body(String.class));
		assertThat(knn.path("hits").path("hits").get(0).path("_source").path("seq").asInt()).isEqualTo(1);
		assertThat(knn.path("hits").path("hits").get(0).path("_score").asDouble()).isGreaterThan(0.99);
		// 새 분석기: 조사("는")를 떼어 낸다.
		JsonNode tokens = json.readTree(es.post().uri("/thr_doc_chunk_v2/_analyze").contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsString(Map.of("field", "content", "text", "연차는"))).retrieve().body(String.class));
		List<String> terms = new ArrayList<>();
		for (JsonNode token : tokens.path("tokens")) terms.add(token.path("token").asString());
		assertThat(terms).containsExactly("연차");
		// 이전 인덱스는 지우지 않는다(운영자가 확인 뒤 지운다). 다시 기동해도 또 옮기지 않는다.
		assertThat(json.readTree(es.get().uri("/thr_doc_chunk_v1/_count").retrieve().body(String.class)).path("count").asLong()).isEqualTo(3);
		index("thr_doc_chunk_v2").ensure();
		assertThat(index.count(doc, 1)).isEqualTo(3);
	}

	@Test
	void anAliasPointingAtTheCurrentAndAnotherIndexKeepsOnlyTheCurrent() throws IOException {
		RestClient es = RestClient.create(url());
		byte[] v1;
		try (InputStream in = new ClassPathResource("es-thr-doc-chunk-index-v1.json").getInputStream()) {
			v1 = in.readAllBytes();
		}
		// 운영자가 별칭을 손으로 두 인덱스에 붙였다.
		es.put().uri("/manual_old").contentType(MediaType.APPLICATION_JSON).body(v1).retrieve().toBodilessEntity();
		es.put().uri("/manual_current").contentType(MediaType.APPLICATION_JSON).body(v1).retrieve().toBodilessEntity();
		es.put().uri("/manual_old/_alias/manual_alias").retrieve().toBodilessEntity();
		es.put().uri("/manual_current/_alias/manual_alias").retrieve().toBodilessEntity();
		var properties = new EtlProperties(null, new EtlProperties.Elasticsearch(url(), "manual_alias", "manual_current", 200, "thr_doc_tag", "thr_doc_tag_v1"), null,
				new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(30));

		new ChunkIndex(properties, json).ensure();

		JsonNode aliases = json.readTree(es.get().uri("/_alias/manual_alias").retrieve().body(String.class));
		assertThat(aliases.propertyNames()).containsExactly("manual_current");
		assertThat(json.readTree(es.get().uri("/manual_old/_count").retrieve().body(String.class)).has("count"))
				.as("뗀 인덱스는 지우지 않는다").isTrue();
	}

	private ChunkIndex index(String name) {
		var properties = new EtlProperties(null, new EtlProperties.Elasticsearch(url(), ALIAS, name, 200, "thr_doc_tag", "thr_doc_tag_v1"), null,
				new EtlProperties.Chunk(800, 1200, 100), 1, Duration.ofSeconds(1), Duration.ofMinutes(1), List.of(), Duration.ofSeconds(30));
		return new ChunkIndex(properties, json);
	}

	private void put(RestClient es, UUID doc, int seq, String content, int axis) {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("chunk_id", doc + ":1:" + seq);
		source.put("doc_id", doc.toString());
		source.put("thr_id", UUID.randomUUID().toString());
		source.put("tnn_id", UUID.randomUUID().toString());
		source.put("run_seq", 1);
		source.put("seq", seq);
		source.put("content", content);
		source.put("loc", "para=" + seq);
		source.put("emb", unit(axis));
		source.put("emb_mdl", "bge-m3");
		es.put().uri("/" + ALIAS + "/_doc/" + doc + ":1:" + seq).contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsString(source).getBytes(StandardCharsets.UTF_8)).retrieve().toBodilessEntity();
	}

	private static List<Float> unit(int axis) {
		List<Float> vector = new ArrayList<>(DIMENSIONS);
		for (int i = 0; i < DIMENSIONS; i++) vector.add(i == axis ? 1f : 0f);
		return vector;
	}

	private static String url() {
		return "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200);
	}
}
