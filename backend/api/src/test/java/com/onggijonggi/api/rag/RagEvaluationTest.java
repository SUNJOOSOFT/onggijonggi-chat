package com.onggijonggi.api.rag;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.onggijonggi.api.authz.ThreadScopeFilter;
import com.onggijonggi.api.chat.ChatMessage;
import com.onggijonggi.api.chat.ThreadDocumentScope;
import com.onggijonggi.common.document.Chunker;
import com.onggijonggi.common.document.TagIndexContract;
import com.onggijonggi.common.document.TagPrompt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : RagEvaluationTest.java
 * Description : 방 문서 검색 평가 세트(#344). 가상 회사 규정 문서 10개(주제가 겹치는 문서 포함)(rag-eval/documents)를 ETL과 같은 청킹(공용 Chunker)·매핑으로
 *               nori Elasticsearch에 색인하고, 질문 76개(rag-eval/questions.json — 직접·다른 표현·고유명사·무관·후속·문서 선택·주제)를
 *               검색 기준 조합마다 돌려 적중률·무관 질문 오채택률·다시 쓰기 효과·지연을 표로 낸다(build/rag-eval/report.md).
 *               대화 모델이 있으면 ETL과 같은 태깅 규칙(공용 TagPrompt)으로 문서를 태깅해 태그 색인을 만들고, 태그 채널(#362) 끔/켬과
 *               그 기준값을 비교한다. 주제(tag) 질문은 기대 문서가 상위에 오면 적중이다.
 *               실제 사내 모델이 필요해 기본 test·CI에서 빠지고 ragEval로만 돈다:
 *                 RAG_EVAL_EMBEDDING_URL  임베딩 엔드포인트(OpenAI 호환, bge-m3). 없으면 건너뛴다.
 *                 RAG_EVAL_LLM_URL        다시 쓰기·태깅용 대화 모델 엔드포인트(OpenAI 호환, /v1 앞까지). 다시 쓰기는 운영과 같은
 *                                         클라이언트로 부른다. 없으면 후속 질문은 다시 쓰지 않고 태그 채널은 비교하지 않는다.
 *                 RAG_EVAL_LLM_MODEL      대화 모델 이름(기본 gemma).
 *               평가용 모델은 사내망 엔드포인트만 쓴다 — 평가 문서·질문이 외부 공급자로 나가지 않게 한다.
 */
@Tag("rag-eval")
class RagEvaluationTest {

	private static final int DIMENSIONS = 1024;
	private static final String ALIAS = "thr_doc_chunk";
	private static final String INDEX = "thr_doc_chunk_v2";
	private static final UUID TENANT = UUID.randomUUID();
	private static final UUID ROOM = UUID.randomUUID();
	private static final List<String> DOCUMENTS = List.of("leave.md", "security.md", "travel.md", "equipment.md", "remote-work.md",
			"benefits.md", "attendance.md", "info-assets.md", "expenses.md", "privacy.md");
	private static final List<Double> VECTOR_MINIMUMS = List.of(0.0, 0.4, 0.45, 0.5, 0.55, 0.6);
	private static final List<String> KEYWORD_MINIMUMS = List.of("1", "60%", "75%", "100%");
	/** 질문별 결과를 보여 줄 기준(설정 기본값과 같게 둔다). */
	private static final double BASELINE_VECTOR = 0.5;
	private static final String BASELINE_KEYWORD = "75%";
	private static final String TAG_INDEX = "thr_doc_tag_v1";
	/** ETL 기본 태깅 설정(TaggingProperties)과 같게 둔다. */
	private static final TagPrompt.Settings TAGGING = new TagPrompt.Settings(TagPrompt.DEFAULT_CATEGORIES, 10, 200);
	private static final List<String> TAG_MINIMUMS = List.of("30%", "40%", "60%", "2<60%", "3<75%");
	private static final List<Double> TAG_CHUNK_MINIMUMS = List.of(0.35, 0.4, 0.45);

	private final ObjectMapper json = JsonMapper.builder().build();

	record Question(String id, String type, String question, String document, String contains, List<ChatMessage> history) { }

	record Outcome(Question question, String query, boolean rewritten, List<ChunkSearcher.Hit> hits) { }

	@Test
	void evaluate() throws Exception {
		String embeddingUrl = System.getenv("RAG_EVAL_EMBEDDING_URL");
		assumeThat(embeddingUrl).as("RAG_EVAL_EMBEDDING_URL이 없어 평가를 건너뛴다").isNotBlank();
		String llmUrl = System.getenv("RAG_EVAL_LLM_URL");
		String llmModel = System.getenv().getOrDefault("RAG_EVAL_LLM_MODEL", "gemma");

		try (GenericContainer<?> elasticsearch = new GenericContainer<>(new ImageFromDockerfile("ogjg-test/elasticsearch-nori", false)
				.withFileFromPath(".", Path.of("../../infra/elasticsearch")))
				.withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
				.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
				.waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).withStartupTimeout(Duration.ofMinutes(4)))) {
			elasticsearch.start();
			String esUrl = "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200);
			var embedder = new QueryEmbedder(properties(esUrl, embeddingUrl, 0.5, "60%"), WebClient.builder(), json);
			Map<UUID, String> texts = new LinkedHashMap<>();
			Map<UUID, String> names = index(RestClient.create(esUrl), embedder, texts);
			Map<UUID, TagPrompt.Tags> tags = llmUrl == null || llmUrl.isBlank() ? Map.of() : tag(RestClient.create(esUrl), texts, llmUrl, llmModel);
			var scope = new ThreadDocumentScope(TENANT, ThreadScopeFilter.of(List.of(ROOM)), names.keySet().stream()
					.map(id -> new ThreadDocumentScope.Target(id, names.get(id), 1, "bge-m3", DIMENSIONS)).toList());
			List<Question> questions = questions();

			// 질문마다 검색 문장(다시 쓰기 포함)과 벡터를 한 번만 만든다. 기준 조합은 같은 입력으로 비교한다.
			QueryRewriter rewriter = llmUrl == null || llmUrl.isBlank() ? null
					: new QueryRewriter(properties(esUrl, embeddingUrl, 0.5, "75%"), llmUrl + "/v1", "", llmModel);
			Map<String, QueryRewriter.Result> queries = new LinkedHashMap<>();
			Map<String, float[]> vectors = new HashMap<>();
			List<Long> rewriteMillis = new ArrayList<>(), embedMillis = new ArrayList<>();
			for (Question question : questions) {
				QueryRewriter.Result query = new QueryRewriter.Result(question.question(), false);
				if (rewriter != null && question.history() != null && !question.history().isEmpty()) {
					long started = System.nanoTime();
					query = rewriter.rewrite(question.question(), question.history(), llmModel);
					rewriteMillis.add((System.nanoTime() - started) / 1_000_000);
				}
				queries.put(question.id(), query);
				for (String text : List.of(query.query(), question.question())) {
					if (vectors.containsKey(text)) continue;
					long started = System.nanoTime();
					vectors.put(text, embedder.embed(text));
					embedMillis.add((System.nanoTime() - started) / 1_000_000);
				}
			}

			StringBuilder report = new StringBuilder("# 방 문서 검색 평가 (#344)\n\n");
			report.append("- 문서 ").append(DOCUMENTS.size()).append("개, 청크 ").append(chunkCount(RestClient.create(esUrl)))
					.append("개, 질문 ").append(questions.size()).append("개, 상위 5개 기준\n");
			report.append("- 다시 쓰기: ").append(rewriter == null ? "없음(RAG_EVAL_LLM_URL 미설정)" : llmModel)
					.append(", 지연 평균 ").append(average(rewriteMillis)).append("ms / 질문 임베딩 평균 ").append(average(embedMillis)).append("ms\n\n");
			report.append("| 벡터 하한 | 키워드 일치 | 답 있는 질문 적중(상위5) | 상위1 | 정밀도(결과 중 기대 문서) | 무관 질문 오채택 | 후속(다시 쓰기) | 후속(원문) |\n|---|---|---|---|---|---|---|---|\n");
			for (double vectorMinimum : VECTOR_MINIMUMS) {
				for (String keywordMinimum : KEYWORD_MINIMUMS) {
					var searcher = new ChunkSearcher(properties(esUrl, embeddingUrl, vectorMinimum, keywordMinimum), WebClient.builder(), json);
					List<Outcome> rewritten = new ArrayList<>(), original = new ArrayList<>();
					for (Question question : questions) {
						QueryRewriter.Result query = queries.get(question.id());
						rewritten.add(new Outcome(question, query.query(), query.rewritten(),
								searcher.search(scope, query.query(), vectors.get(query.query()))));
						if (question.type().equals("followup"))
							original.add(new Outcome(question, question.question(), false,
									searcher.search(scope, question.question(), vectors.get(question.question()))));
					}
					report.append(String.format("| %.2f | %s | %s | %s | %s | %s | %s | %s |%n", vectorMinimum, keywordMinimum,
							rate(rewritten, outcome -> answerable(outcome) && hit(outcome, names, 5), RagEvaluationTest::answerable),
							rate(rewritten, outcome -> answerable(outcome) && hit(outcome, names, 1), RagEvaluationTest::answerable),
							precision(rewritten, names),
							rate(rewritten, outcome -> !answerable(outcome) && !outcome.hits().isEmpty(), outcome -> !answerable(outcome)),
							rate(rewritten, outcome -> outcome.question().type().equals("followup") && hit(outcome, names, 5),
									outcome -> outcome.question().type().equals("followup")),
							rate(original, outcome -> hit(outcome, names, 5), outcome -> true)));
				}
			}
			if (!tags.isEmpty()) {
				report.append("\n## 태그 채널(#362, 벡터 " + BASELINE_VECTOR + " · 키워드 " + BASELINE_KEYWORD + ")\n\n")
						.append("태그 일치 질문은 태그 색인에 같은 질의를 직접 보내 한 문서라도 맞은 질문 수다(태그 채널이 실제로 돌았는지 확인용).\n")
						.append("끔과 다른 질문은 상위 결과(조각 순서)가 끔과 달라진 질문 수다.\n\n")
						.append("| 태그 일치 | 대표 조각 하한 | 태그 일치 질문(무관) | 끔과 다른 질문 | 답 있는 질문 적중(상위5) | 상위1 | 정밀도 | 무관 질문 오채택 | 주제 질문 | 다른 표현 |\n|---|---|---|---|---|---|---|---|---|---|\n");
				List<Outcome> off = outcomes(searcher(esUrl, embeddingUrl, null, 0), questions, queries, vectors, scope);
				report.append(tagRow("끔", "-", "-", off, off, names));
				RestClient es = RestClient.create(esUrl);
				for (String tagMinimum : TAG_MINIMUMS) {
					String matched = tagMatches(es, tagMinimum, questions, queries, scope);
					for (double chunkMinimum : TAG_CHUNK_MINIMUMS)
						report.append(tagRow(tagMinimum, String.format("%.2f", chunkMinimum), matched,
								outcomes(searcher(esUrl, embeddingUrl, tagMinimum, chunkMinimum), questions, queries, vectors, scope), off, names));
				}
				report.append("\n문서 태그:\n");
				tags.forEach((id, tag) -> report.append("- ").append(names.get(id)).append(": ").append(tag.category()).append(" · ")
						.append(String.join(", ", tag.keywords())).append("\n"));
			}
			report.append("\n## 질문별 결과(벡터 " + BASELINE_VECTOR + " · 키워드 " + BASELINE_KEYWORD
					+ ")\n\n| 질문 | 유형 | 검색 문장 | 적중 | 상위 결과(문서·위치·벡터·키워드) |\n|---|---|---|---|---|\n");
			var baseline = new ChunkSearcher(properties(esUrl, embeddingUrl, BASELINE_VECTOR, BASELINE_KEYWORD), WebClient.builder(), json);
			for (Question question : questions) {
				QueryRewriter.Result query = queries.get(question.id());
				var outcome = new Outcome(question, query.query(), query.rewritten(), baseline.search(scope, query.query(), vectors.get(query.query())));
				report.append("| ").append(question.id()).append(" | ").append(question.type()).append(" | ")
						.append(query.rewritten() ? query.query() : "(원문)").append(" | ")
						.append(answerable(outcome) ? (hit(outcome, names, 5) ? "O" : "X") : (outcome.hits().isEmpty() ? "O(없음)" : "X(오채택)"))
						.append(" | ").append(summary(outcome, names)).append(" |\n");
			}
			Path out = Path.of("build/rag-eval/report.md");
			Files.createDirectories(out.getParent());
			Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
			System.out.println(report);
		}
	}

	private static boolean answerable(Outcome outcome) {
		return outcome.question().document() != null;
	}

	/** 기대 문서의 청크가 상위 top 안에 있고 그 청크에 기대 낱말이 있으면(주제 질문처럼 기대 낱말이 없으면 문서만 맞아도) 적중이다. */
	private static boolean hit(Outcome outcome, Map<UUID, String> names, int top) {
		String contains = outcome.question().contains();
		return outcome.hits().stream().limit(top).anyMatch(hit -> outcome.question().document().equals(names.get(UUID.fromString(hit.document())))
				&& (contains == null || hit.content().contains(contains)));
	}

	private List<Outcome> outcomes(ChunkSearcher searcher, List<Question> questions, Map<String, QueryRewriter.Result> queries,
			Map<String, float[]> vectors, ThreadDocumentScope scope) {
		List<Outcome> outcomes = new ArrayList<>();
		for (Question question : questions) {
			QueryRewriter.Result query = queries.get(question.id());
			outcomes.add(new Outcome(question, query.query(), query.rewritten(), searcher.search(scope, query.query(), vectors.get(query.query()))));
		}
		return outcomes;
	}

	/** 태그 채널 끔/켬 한 줄. 기본 기준값(벡터·키워드)은 고정한다. */
	private String tagRow(String tagMinimum, String chunkMinimum, String matched, List<Outcome> outcomes, List<Outcome> off, Map<UUID, String> names) {
		long changed = 0;
		for (int i = 0; i < outcomes.size(); i++)
			if (!outcomes.get(i).hits().stream().map(ChunkSearcher.Hit::chunkId).toList()
					.equals(off.get(i).hits().stream().map(ChunkSearcher.Hit::chunkId).toList())) changed++;
		return String.format("| %s | %s | %s | %d | %s | %s | %s | %s | %s | %s |%n", tagMinimum, chunkMinimum, matched, changed,
				rate(outcomes, outcome -> answerable(outcome) && hit(outcome, names, 5), RagEvaluationTest::answerable),
				rate(outcomes, outcome -> answerable(outcome) && hit(outcome, names, 1), RagEvaluationTest::answerable),
				precision(outcomes, names),
				rate(outcomes, outcome -> !answerable(outcome) && !outcome.hits().isEmpty(), outcome -> !answerable(outcome)),
				rate(outcomes, outcome -> type(outcome, "tag") && hit(outcome, names, 5), outcome -> type(outcome, "tag")),
				rate(outcomes, outcome -> type(outcome, "paraphrase") && hit(outcome, names, 5), outcome -> type(outcome, "paraphrase")));
	}

	/** 태그 색인에 검색과 같은 질의(같은 범위 필터)를 직접 보내, 한 문서라도 맞은 질문 수(무관 질문 수)를 센다. */
	private String tagMatches(RestClient es, String tagMinimum, List<Question> questions, Map<String, QueryRewriter.Result> queries,
			ThreadDocumentScope scope) {
		int matched = 0, unrelated = 0;
		for (Question question : questions) {
			Map<String, Object> body = Map.of("size", 0, "track_total_hits", true, "query",
					ChunkSearcher.tagQuery(queries.get(question.id()).query(), tagMinimum, ChunkSearcher.filter(scope)));
			String response = es.post().uri("/" + TagIndexContract.ALIAS + "/_search").contentType(MediaType.APPLICATION_JSON)
					.body(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)).retrieve().body(String.class);
			if (json.readTree(response).path("hits").path("total").path("value").asLong() > 0) {
				matched++;
				if (question.document() == null) unrelated++;
			}
		}
		return matched + "/" + questions.size() + " (" + unrelated + ")";
	}

	private static boolean type(Outcome outcome, String type) {
		return outcome.question().type().equals(type);
	}

	/** 기본 기준값에 태그 채널을 끄거나(tagMinimum null) 켠 검색기. */
	private ChunkSearcher searcher(String esUrl, String embeddingUrl, String tagMinimum, double chunkMinimum) {
		var tags = tagMinimum == null ? RagTagProperties.disabled() : new RagTagProperties(true, TagIndexContract.ALIAS, tagMinimum, 10, chunkMinimum);
		return new ChunkSearcher(properties(esUrl, embeddingUrl, BASELINE_VECTOR, BASELINE_KEYWORD), tags, WebClient.builder(), json);
	}

	/** ETL과 같은 프롬프트·응답 검사로 문서마다 태그를 뽑아 태그 색인에 넣는다(평가 문서는 짧아 한 번에 보낸다). */
	private Map<UUID, TagPrompt.Tags> tag(RestClient es, Map<UUID, String> texts, String llmUrl, String llmModel) throws IOException {
		byte[] mapping;
		try (InputStream in = new ClassPathResource(TagIndexContract.MAPPING).getInputStream()) {
			mapping = in.readAllBytes();
		}
		es.put().uri("/" + TAG_INDEX).contentType(MediaType.APPLICATION_JSON).body(mapping).retrieve().toBodilessEntity();
		es.put().uri("/" + TAG_INDEX + "/_alias/" + TagIndexContract.ALIAS).retrieve().toBodilessEntity();
		RestClient llm = RestClient.create(llmUrl);
		Map<UUID, TagPrompt.Tags> tags = new LinkedHashMap<>();
		for (var entry : texts.entrySet()) {
			Map<String, Object> request = Map.of("model", llmModel, "temperature", 0, "max_tokens", 1024, "messages", List.of(
					Map.of("role", "system", "content", TagPrompt.system(TAGGING)), Map.of("role", "user", "content", TagPrompt.user(entry.getValue()))));
			String response = llm.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
					.body(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)).retrieve().body(String.class);
			TagPrompt.Tags tag = TagPrompt.parse(json.readTree(response).path("choices").path(0).path("message").path("content").asString(""), TAGGING);
			tags.put(entry.getKey(), tag);
			Map<String, Object> source = new LinkedHashMap<>();
			source.put(TagIndexContract.DOC_ID, entry.getKey().toString());
			source.put(TagIndexContract.THR_ID, ROOM.toString());
			source.put(TagIndexContract.TNN_ID, TENANT.toString());
			source.put(TagIndexContract.RUN_SEQ, 1);
			source.put(TagIndexContract.CATEGORY, tag.category());
			source.put(TagIndexContract.KEYWORDS, tag.keywords());
			source.put(TagIndexContract.SUMMARY, tag.summary());
			source.put(TagIndexContract.CONFIG, "eval");
			es.put().uri("/" + TagIndexContract.ALIAS + "/_doc/{id}", TagIndexContract.tagId(entry.getKey(), 1)).contentType(MediaType.APPLICATION_JSON)
					.body(json.writeValueAsString(source).getBytes(StandardCharsets.UTF_8)).retrieve().toBodilessEntity();
		}
		es.post().uri("/" + TagIndexContract.ALIAS + "/_refresh").retrieve().toBodilessEntity();
		return tags;
	}

	/** 답 있는 질문의 결과 청크 가운데 기대 문서의 청크 비율. 다른 문서 청크가 답변 문맥에 섞이는 정도다. */
	private static String precision(List<Outcome> outcomes, Map<UUID, String> names) {
		long total = 0, expected = 0;
		for (Outcome outcome : outcomes) {
			if (!answerable(outcome)) continue;
			for (ChunkSearcher.Hit hit : outcome.hits()) {
				total++;
				if (outcome.question().document().equals(names.get(UUID.fromString(hit.document())))) expected++;
			}
		}
		return total == 0 ? "-" : String.format("%d%% (%d/%d)", Math.round(100.0 * expected / total), expected, total);
	}

	private static String rate(List<Outcome> outcomes, java.util.function.Predicate<Outcome> good, java.util.function.Predicate<Outcome> base) {
		long total = outcomes.stream().filter(base).count(), count = outcomes.stream().filter(good).count();
		return total == 0 ? "-" : count + "/" + total;
	}

	private static String summary(Outcome outcome, Map<UUID, String> names) {
		List<String> parts = new ArrayList<>();
		for (ChunkSearcher.Hit hit : outcome.hits().stream().limit(3).toList())
			parts.add(names.get(UUID.fromString(hit.document())) + " " + hit.loc() + " " + format(hit.vectorScore()) + "/" + format(hit.keywordScore()));
		return parts.isEmpty() ? "-" : String.join("; ", parts);
	}

	private static String format(Double score) {
		return score == null ? "-" : String.format("%.2f", score);
	}

	private static long average(List<Long> values) {
		return values.isEmpty() ? 0 : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
	}

	private static RagProperties properties(String esUrl, String embeddingUrl, double vectorMinimum, String keywordMinimum) {
		return new RagProperties(new RagProperties.Elasticsearch(esUrl, ALIAS, Duration.ofSeconds(10)),
				new RagProperties.Embedding(embeddingUrl, "bge-m3", DIMENSIONS, Duration.ofSeconds(30)),
				new RagProperties.Rewrite("", Duration.ofSeconds(30), 6),
				new RagProperties.Search(5, 3, 20, 100, vectorMinimum, keywordMinimum, 60, 2, 10), true);
	}

	/** ETL과 같은 공용 매핑·청킹(800/1200/100)으로 색인한다. 문서 ID → 파일 이름. */
	private Map<UUID, String> index(RestClient es, QueryEmbedder embedder, Map<UUID, String> texts) throws IOException {
		byte[] mapping;
		try (InputStream in = new ClassPathResource("es-thr-doc-chunk-index.json").getInputStream()) {
			mapping = in.readAllBytes();
		}
		es.put().uri("/" + INDEX).contentType(MediaType.APPLICATION_JSON).body(mapping).retrieve().toBodilessEntity();
		es.put().uri("/" + INDEX + "/_alias/" + ALIAS).retrieve().toBodilessEntity();
		Chunker chunker = new Chunker(new Chunker.Settings(800, 1200, 100));
		Map<UUID, String> names = new LinkedHashMap<>();
		for (String name : DOCUMENTS) {
			UUID id = UUID.randomUUID();
			names.put(id, name);
			String text;
			try (InputStream in = new ClassPathResource("rag-eval/documents/" + name).getInputStream()) {
				text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			texts.put(id, text);
			for (Chunker.Chunk chunk : chunker.chunk(id, 1, List.of(new Chunker.Section(null, text)))) {
				Map<String, Object> source = new LinkedHashMap<>();
				source.put("chunk_id", chunk.id());
				source.put("doc_id", id.toString());
				source.put("thr_id", ROOM.toString());
				source.put("tnn_id", TENANT.toString());
				source.put("run_seq", 1);
				source.put("seq", chunk.seq());
				source.put("content", chunk.content());
				source.put("loc", chunk.loc());
				source.put("emb", embedder.embed(chunk.content()));
				source.put("emb_mdl", "bge-m3");
				es.put().uri("/" + ALIAS + "/_doc/" + chunk.id()).contentType(MediaType.APPLICATION_JSON)
						.body(json.writeValueAsString(source).getBytes(StandardCharsets.UTF_8)).retrieve().toBodilessEntity();
			}
		}
		es.post().uri("/" + ALIAS + "/_refresh").retrieve().toBodilessEntity();
		return names;
	}

	private long chunkCount(RestClient es) {
		return json.readTree(es.get().uri("/" + ALIAS + "/_count").retrieve().body(String.class)).path("count").asLong();
	}

	private List<Question> questions() throws IOException {
		try (InputStream in = new ClassPathResource("rag-eval/questions.json").getInputStream()) {
			List<Question> questions = new ArrayList<>();
			for (JsonNode node : json.readTree(in)) {
				List<ChatMessage> history = new ArrayList<>();
				for (JsonNode message : node.path("history")) history.add(new ChatMessage(message.path("role").asString(), message.path("content").asString()));
				questions.add(new Question(node.path("id").asString(), node.path("type").asString(), node.path("question").asString(),
						node.hasNonNull("document") ? node.path("document").asString() : null,
						node.hasNonNull("contains") ? node.path("contains").asString() : null, history));
			}
			return questions;
		}
	}
}
