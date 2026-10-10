package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.api.rag.SearchResult;
import com.onggijonggi.api.rag.StubHttpServer;
import com.onggijonggi.api.rag.ThreadDocumentSearch;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.document.ChunkIndexContract;
import com.onggijonggi.common.document.TagIndexContract;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ThreadDocumentSearchIntegrationTest.java
 * Description : 실제 PostgreSQL(Flyway)과 nori Elasticsearch, 가짜 임베딩으로 방 문서 검색(#344)을 확인한다. 다른 방·다른 고객사·
 *               고정 해제·이전 회차 청크가 섞이지 않는지, 키워드에서만 잡히는 고유명사도 채택하는지, 대상이 없으면 임베딩을 부르지
 *               않는지, 장애·모델 불일치가 근거 없음이 아니라 UNAVAILABLE인지 본다. 벡터는 축 하나만 1인 단위 벡터라 코사인 유사도가
 *               같은 축이면 1, 다른 축이면 0이다. 태그 채널(#362)을 켜 두고, 본문·키워드로는 못 찾는 표현을 태그로 찾아 그 문서의
 *               대표 조각을 근거로 세우는지 본다(태그가 없으면 NO_MATCH). 검색(rag) 테스트지만 chat 패키지에 둔다 — 공용 픽스처(FakeChatModelConfig 등)가
 *               package-private이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class ThreadDocumentSearchIntegrationTest {

	private static final int DIMENSIONS = 1024;
	private static final String ALIAS = "thr_doc_chunk";
	private static final String INDEX = "thr_doc_chunk_v2";
	private static final String TAG_INDEX = "thr_doc_tag_v1";
	/** 이 낱말이 든 질문은 "이월" 축과 코사인 0.45인 벡터다 — 벡터 채널 기준(0.5)엔 못 미치고 태그 대표 조각 기준(0.4)은 넘는다. */
	private static final String NEAR = "휴가";
	/** 이 낱말까지 든 질문은 "이월" 축과 코사인 0.3이다 — 태그는 맞아도 대표 조각 기준(0.4)에 못 미친다. */
	private static final String FAR = "문의";

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("thread_search").withUsername("test").withPassword("test");
	@Container static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(new ImageFromDockerfile("ogjg-test/elasticsearch-nori", false)
			.withFileFromPath(".", Path.of("../../infra/elasticsearch")))
			.withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
			.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
			.waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).withStartupTimeout(Duration.ofMinutes(4)));
	/** 질문에 이 낱말이 있으면 그 축의 단위 벡터를 돌려준다. 없으면 다른 어떤 청크와도 직교하는 축이다. */
	static final Map<String, Integer> AXES = new LinkedHashMap<>(Map.of("이월", 0, "보안", 1));
	static final StubHttpServer EMBEDDING;
	/** 검색 문장 다시 쓰기용 게이트웨이(OpenAI 호환). 다시 쓰기는 채팅의 ChatModel이 아니라 전용 클라이언트로 부른다. */
	static final StubHttpServer GATEWAY;
	static final String REWRITTEN = "연차 이월 기한이 지나면 어떻게 되나";

	static {
		try {
			EMBEDDING = new StubHttpServer();
			GATEWAY = new StubHttpServer().reply("/v1/chat/completions", 200, "{\"id\":\"r\",\"object\":\"chat.completion\",\"created\":0,"
					+ "\"model\":\"gemma\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
					+ REWRITTEN + "\"}}]}");
		} catch (IOException error) {
			throw new IllegalStateException(error);
		}
	}

	@DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.flyway.enabled", () -> "true");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		r.add("app.rbac.workspace-setup-path", () -> Path.of("../../infra/config/workspace-setup.default.yml").toAbsolutePath().normalize().toString());
		r.add("app.rag.elasticsearch.url", ThreadDocumentSearchIntegrationTest::elasticsearchUrl);
		r.add("app.rag.embedding.url", EMBEDDING::url);
		r.add("spring.ai.openai.base-url", () -> GATEWAY.url() + "/v1");
		r.add("app.rag.tag.enabled", () -> "true");
	}

	@AfterAll static void stopEmbedding() {
		EMBEDDING.close();
		GATEWAY.close();
	}

	@Autowired ThreadDocumentSearch search;
	@Autowired ThreadDocumentService documents;
	@Autowired JdbcTemplate jdbc;
	@Autowired ThrRepository threads;
	@Autowired ThrMbrRepository members;
	@Autowired RbacBootstrapService bootstrap;
	@Autowired ObjectMapper json;
	@MockitoBean ThreadSourceStorage storage;
	@MockitoBean WorkspaceAuthorizer authorizer;
	CurrentActor owner;
	UUID tenant;
	UUID room;
	UUID otherRoom;

	@BeforeEach void setup() throws IOException {
		reset(storage, authorizer);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(true);
		bootstrap.runCurrentConfiguration();
		jdbc.update("delete from thr");
		jdbc.update("delete from thr_doc_end");
		tenant = jdbc.queryForObject("select id from tnn where tnn_key='ogjg'", UUID.class);
		owner = actor();
		room = room(owner);
		otherRoom = room(owner);
		EMBEDDING.requests.clear();
		GATEWAY.requests.clear();
		EMBEDDING.reply("/v1/embeddings", body -> new StubHttpServer.Reply(200, embedding(body)));
		recreateIndex();
	}

	@Test void onlyPinnedReadyDocumentsOfThisRoomAndTheirCurrentRunAreSearched() {
		UUID pinned = readyDocument(room, true, 1), unpinned = readyDocument(room, false, 1), elsewhere = readyDocument(otherRoom, true, 1);
		UUID reprocessed = readyDocument(room, true, 2);
		index(pinned, room, tenant, 1, 1, "연차는 다음 해 3월 말까지 이월할 수 있다.", 0);
		index(unpinned, room, tenant, 1, 1, "고정하지 않은 문서의 이월 규정.", 0);
		index(elsewhere, otherRoom, tenant, 1, 1, "다른 방 문서의 이월 규정.", 0);
		// 같은 문서·방 ID를 단 다른 고객사의 청크와, 재처리 전 1회차 청크(정리 전이라 남아 있다).
		index(pinned, room, UUID.randomUUID(), 1, 2, "다른 고객사 청크의 이월 규정.", 0);
		index(reprocessed, room, tenant, 1, 1, "재처리 전 1회차의 이월 규정.", 0);
		index(reprocessed, room, tenant, 2, 1, "재처리 뒤 2회차의 이월 규정.", 0);
		refresh();

		SearchResult result = search("연차 이월 규정");

		assertThat(result.status()).isEqualTo(SearchResult.Status.FOUND);
		assertThat(result.chunks()).extracting(SearchResult.Chunk::documentId, SearchResult.Chunk::runSeq)
				.containsExactlyInAnyOrder(tuple(pinned, 1), tuple(reprocessed, 2));
		assertThat(result.chunks()).allSatisfy(chunk -> {
			assertThat(chunk.fileName()).isEqualTo("guide.txt");
			assertThat(chunk.chunkId()).isEqualTo(chunk.documentId() + ":" + chunk.runSeq() + ":" + chunk.seq());
			assertThat(chunk.loc()).isEqualTo("para=1");
			assertThat(chunk.vectorScore()).isGreaterThan(0.99);
		});
	}

	@Test void aProperNounFoundOnlyByKeywordIsKeptAndAnUnrelatedQuestionHasNoEvidence() {
		UUID doc = readyDocument(room, true, 1);
		index(doc, room, tenant, 1, 1, "사내 포털 옹기마루에서 장비를 신청한다.", 5);
		refresh();

		SearchResult noun = search("옹기마루");
		assertThat(noun.status()).isEqualTo(SearchResult.Status.FOUND);
		assertThat(noun.chunks()).singleElement().satisfies(chunk -> {
			assertThat(chunk.vectorScore()).as("벡터는 직교라 기준 미달").isNull();
			assertThat(chunk.keywordScore()).isPositive();
		});
		assertThat(search("오늘 날씨")).extracting(SearchResult::status, SearchResult::reason)
				.containsExactly(SearchResult.Status.NO_EVIDENCE, SearchResult.Reason.NO_MATCH);
	}

	@Test void withoutTargetsNeitherTheEmbeddingNorTheModelIsCalled() {
		readyDocument(room, false, 1);

		SearchResult result = search.search(room, owner, "연차 이월", List.of(new ChatMessage("user", "앞 질문")), "gemma").block();

		assertThat(result.status()).isEqualTo(SearchResult.Status.NO_EVIDENCE);
		assertThat(result.reason()).isEqualTo(SearchResult.Reason.NO_PINNED_DOCUMENTS);
		assertThat(result.rewritten()).isFalse();
		assertThat(EMBEDDING.requests).isEmpty();
		assertThat(GATEWAY.requests).isEmpty();
	}

	@Test void aFollowUpIsSearchedWithTheRewrittenQuery() {
		UUID doc = readyDocument(room, true, 1);
		index(doc, room, tenant, 1, 1, "연차는 다음 해 3월 말까지 이월할 수 있다.", 0);
		refresh();

		SearchResult result = search.search(room, owner, "그럼 그거 넘기면?", List.of(new ChatMessage("user", "연차 이월 규정 알려줘"),
				new ChatMessage("assistant", "3월 말까지 이월할 수 있습니다.")), "gemma").block();

		assertThat(result.rewritten()).isTrue();
		assertThat(result.query()).isEqualTo(REWRITTEN);
		assertThat(EMBEDDING.requests).singleElement().satisfies(request -> assertThat(request.body()).contains(REWRITTEN));
		assertThat(GATEWAY.requests).singleElement().satisfies(request -> assertThat(request.body()).contains("\"temperature\":0.0"));
	}

	@Test void outagesAndAModelMismatchAreUnavailableNotNoEvidence() {
		UUID doc = readyDocument(room, true, 1);
		index(doc, room, tenant, 1, 1, "연차는 다음 해 3월 말까지 이월할 수 있다.", 0);
		refresh();

		EMBEDDING.reply("/v1/embeddings", 503, "{}");
		assertThat(search("연차 이월").reason()).isEqualTo(SearchResult.Reason.BACKEND_ERROR);

		EMBEDDING.reply("/v1/embeddings", body -> new StubHttpServer.Reply(200, embedding(body)));
		es().delete().uri("/" + INDEX + "/_alias/" + ALIAS).retrieve().toBodilessEntity();
		assertThat(search("연차 이월").status()).as("대상이 있는데 색인 별칭이 없다").isEqualTo(SearchResult.Status.UNAVAILABLE);
		es().put().uri("/" + INDEX + "/_alias/" + ALIAS).retrieve().toBodilessEntity();
		assertThat(search("연차 이월").status()).isEqualTo(SearchResult.Status.FOUND);

		jdbc.update("update thr_doc_run set emb_mdl='other-model' where doc_id=?", doc);
		int calls = EMBEDDING.requests.size();
		assertThat(search("연차 이월")).extracting(SearchResult::status, SearchResult::reason)
				.containsExactly(SearchResult.Status.UNAVAILABLE, SearchResult.Reason.MODEL_MISMATCH);
		assertThat(EMBEDDING.requests).as("모델이 다르면 질문을 임베딩하지 않는다").hasSize(calls);
	}

	@Test void aNonParticipantIsRefusedBeforeSearching() {
		readyDocument(room, true, 1);
		assertThatThrownBy(() -> search.search(room, actor(), "연차 이월", List.of(), null).block())
				.isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
		assertThat(EMBEDDING.requests).isEmpty();
	}

	@Test void aDocumentFoundOnlyByItsTagsIsRepresentedByItsClosestChunk() {
		UUID doc = readyDocument(room, true, 1);
		index(doc, room, tenant, 1, 1, "연차는 다음 해 3월 말까지 이월할 수 있다.", 0);
		index(doc, room, tenant, 1, 2, "보안 서약서는 입사일에 제출한다.", 1);
		refresh();
		String question = "남은 휴가 넘기기";

		assertThat(search(question).reason()).as("본문 키워드·벡터만으로는 못 찾는다").isEqualTo(SearchResult.Reason.NO_MATCH);

		tag(doc, room, tenant, 1, List.of("휴가", "연차 이월"), "남은 휴가를 다음 해로 넘기는 규정이다.");
		// 다른 방 문서에도 같은 태그와 질문에 가까운 조각이 있다 — 범위 필터가 빠지면 이 문서가 근거로 섞인다.
		UUID elsewhere = readyDocument(otherRoom, true, 1);
		index(elsewhere, otherRoom, tenant, 1, 1, "다른 방의 연차 이월 규정.", 0);
		refresh();
		tag(elsewhere, otherRoom, tenant, 1, List.of("휴가", "연차 이월"), "남은 휴가를 다음 해로 넘기는 규정이다.");
		SearchResult result = search(question);

		assertThat(result.status()).isEqualTo(SearchResult.Status.FOUND);
		assertThat(result.chunks()).singleElement().satisfies(chunk -> {
			assertThat(chunk.documentId()).isEqualTo(doc);
			assertThat(chunk.seq()).as("문서 안에서 질문과 가장 가까운 조각").isEqualTo(1);
			assertThat(chunk.vectorScore()).isBetween(0.44, 0.46);
			assertThat(chunk.keywordScore()).isNull();
		});

		// 태그는 맞지만 문서 안 어느 조각도 질문과 대표 조각 기준(코사인 0.4)만큼 가깝지 않으면 근거로 넣지 않는다.
		assertThat(search("남은 휴가 넘기기 " + FAR).reason()).isEqualTo(SearchResult.Reason.NO_MATCH);
	}

	private SearchResult search(String question) {
		return search.search(room, owner, question, List.of(), null).block();
	}

	/** 등록·처리 완료한 문서. currentRun이 2면 재처리로 2회차가 완료된 상태다(1회차는 정리 전이라 DONE으로 남아 있다). */
	private UUID readyDocument(UUID thread, boolean pinned, int currentRun) {
		UUID id = UUID.randomUUID();
		documents.upload(thread, id, owner, "guide.txt", ("원문 " + id).getBytes(StandardCharsets.UTF_8));
		assertThat(documents.processing(id, "PENDING", "PROCESSING")).isTrue();
		assertThat(documents.processing(id, "PROCESSING", "READY")).isTrue();
		jdbc.update("update thr_doc_run set status='DONE', emb_mdl='bge-m3', emb_dim=? where doc_id=?", DIMENSIONS, id);
		for (int run = 2; run <= currentRun; run++)
			jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, emb_mdl, emb_dim) values (?, ?, ?, ?, ?, 'DONE', 'bge-m3', ?)",
					UUID.randomUUID(), id, tenant, thread, run, DIMENSIONS);
		if (pinned) documents.change(thread, id, owner, "PINNED", UUID.randomUUID());
		return id;
	}

	private UUID room(CurrentActor owner) {
		UUID common = jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class);
		Thr value = Thr.collab(owner.userId(), "검색 방");
		value.placeIn(tenant, common);
		threads.saveAndFlush(value);
		members.saveAndFlush(new ThrMbr(value.getId(), owner.userId(), ThrMbrRole.OWNER, owner.userId()));
		return value.getId();
	}

	private CurrentActor actor() {
		UUID id = UUID.randomUUID(); String subject = "search-" + id;
		jdbc.update("insert into app_user(id,keycloak_subj) values (?,?)", id, subject);
		return new CurrentActor(id, subject, subject);
	}

	private void index(UUID doc, UUID thread, UUID chunkTenant, int runSeq, int seq, String content, int axis) {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("chunk_id", ChunkIndexContract.chunkId(doc, runSeq, seq));
		source.put("doc_id", doc.toString());
		source.put("thr_id", thread.toString());
		source.put("tnn_id", chunkTenant.toString());
		source.put("run_seq", runSeq);
		source.put("seq", seq);
		source.put("content", content);
		source.put("loc", "para=" + seq);
		source.put("emb", unit(axis));
		source.put("emb_mdl", "bge-m3");
		es().post().uri("/" + ALIAS + "/_doc").contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsString(source).getBytes(StandardCharsets.UTF_8)).retrieve().toBodilessEntity();
	}

	private void tag(UUID doc, UUID thread, UUID tagTenant, int runSeq, List<String> keywords, String summary) {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put(TagIndexContract.DOC_ID, doc.toString());
		source.put(TagIndexContract.THR_ID, thread.toString());
		source.put(TagIndexContract.TNN_ID, tagTenant.toString());
		source.put(TagIndexContract.RUN_SEQ, runSeq);
		source.put(TagIndexContract.CATEGORY, "인사·총무");
		source.put(TagIndexContract.KEYWORDS, keywords);
		source.put(TagIndexContract.SUMMARY, summary);
		source.put(TagIndexContract.CONFIG, "tag-v1:test");
		es().put().uri("/" + TagIndexContract.ALIAS + "/_doc/{id}?refresh=true", TagIndexContract.tagId(doc, runSeq)).contentType(MediaType.APPLICATION_JSON)
				.body(json.writeValueAsString(source).getBytes(StandardCharsets.UTF_8)).retrieve().toBodilessEntity();
	}

	private void refresh() {
		es().post().uri("/" + ALIAS + "/_refresh").retrieve().toBodilessEntity();
	}

	private void recreateIndex() throws IOException {
		recreateIndex(INDEX, ALIAS, ChunkIndexContract.MAPPING);
		recreateIndex(TAG_INDEX, TagIndexContract.ALIAS, TagIndexContract.MAPPING);
	}

	/** 공용 매핑(ETL이 쓰는 것과 같은 파일)으로 인덱스와 별칭을 새로 만든다. */
	private static void recreateIndex(String index, String alias, String resource) throws IOException {
		RestClient es = es();
		try {
			es.delete().uri("/" + index).retrieve().toBodilessEntity();
		} catch (org.springframework.web.client.HttpClientErrorException.NotFound absent) {
			// 처음에는 없다.
		}
		byte[] mapping;
		try (InputStream in = new ClassPathResource(resource).getInputStream()) {
			mapping = in.readAllBytes();
		}
		es.put().uri("/" + index).contentType(MediaType.APPLICATION_JSON).body(mapping).retrieve().toBodilessEntity();
		es.put().uri("/" + index + "/_alias/" + alias).retrieve().toBodilessEntity();
	}

	private String embedding(String body) {
		String text = json.readTree(body).path("input").get(0).asString("");
		if (text.contains(NEAR)) {
			float cosine = text.contains(FAR) ? 0.3f : 0.45f;
			List<Float> near = unit(2);
			near.set(0, cosine);
			near.set(2, (float) Math.sqrt(1 - cosine * cosine));
			return json.writeValueAsString(Map.of("model", "bge-m3", "data", List.of(Map.of("index", 0, "embedding", near))));
		}
		int axis = AXES.entrySet().stream().filter(entry -> text.contains(entry.getKey())).map(Map.Entry::getValue)
				.findFirst().orElse(DIMENSIONS - 1);
		return json.writeValueAsString(Map.of("model", "bge-m3", "data", List.of(Map.of("index", 0, "embedding", unit(axis)))));
	}

	private static List<Float> unit(int axis) {
		List<Float> vector = new ArrayList<>(DIMENSIONS);
		for (int i = 0; i < DIMENSIONS; i++) vector.add(i == axis ? 1f : 0f);
		return vector;
	}

	private static String elasticsearchUrl() {
		return "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200);
	}

	private static RestClient es() {
		return RestClient.create(elasticsearchUrl());
	}
}
