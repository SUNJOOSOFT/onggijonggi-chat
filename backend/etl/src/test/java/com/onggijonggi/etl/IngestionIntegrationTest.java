package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.common.document.ThreadDocumentStates;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : IngestionIntegrationTest.java
 * Description : 실제 최신 Flyway 스키마(PostgreSQL)와 nori를 넣은 실제 Elasticsearch, 가짜 문서 워커·임베딩 서버로 ETL 워커를
 *               끝까지 돌린다 — 등록 → READY, 글자 없음·계약 불일치 FAILED, 일시 장애 재시도, 재시도 소진, 처리 중 삭제,
 *               재처리로 이전 회차 정리, 죽은 워커의 회차 재개. 방·사용자 FK는 ETL과 무관해 픽스처 연결에서만 건너뛴다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class IngestionIntegrationTest {

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("etl").withUsername("test").withPassword("test");
	@Container static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(new ImageFromDockerfile("ogjg-test/elasticsearch-nori", false)
			.withFileFromPath(".", Path.of("../../infra/elasticsearch")))
			.withEnv("discovery.type", "single-node").withEnv("xpack.security.enabled", "false")
			.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m").withExposedPorts(9200)
			.waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).withStartupTimeout(Duration.ofMinutes(4)));
	static final FakeServices FAKE;

	static {
		try {
			FAKE = new FakeServices();
		} catch (java.io.IOException error) {
			throw new IllegalStateException(error);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("app.etl.worker.url", FAKE::url);
		registry.add("app.etl.worker.api-key", () -> FakeServices.API_KEY);
		registry.add("app.etl.embedding.url", FAKE::url);
		registry.add("app.etl.elasticsearch.url", () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
		registry.add("app.etl.retry-delays", () -> "200ms,200ms");
		registry.add("app.etl.poll-delay", () -> "100ms");
		registry.add("app.etl.sweep-delay", () -> "300ms");
		// 다시 만들기 주기는 테스트가 직접 부른다 — 저절로 돌면 다른 테스트의 회차 번호·건수가 흔들린다.
		registry.add("app.etl.rebuild-delay", () -> "1h");
		// 대체된 회차 정리 유예(운영 2분)를 없애 정리 결과를 바로 본다. 유예 자체는 RebuildStoreTest가 본다.
		registry.add("app.etl.superseded-grace", () -> "0s");
		// 태깅(#362)은 가짜 대화 서버로 돈다. 다른 테스트의 문서도 태깅하지만(원본을 지운 문서는 실패로 남는다) 결과에는 영향이 없다.
		registry.add("app.etl.tagging.url", FAKE::url);
		registry.add("app.etl.tagging.model", () -> "tag-model");
		registry.add("app.etl.tagging.idle-delay", () -> "200ms");
		registry.add("app.etl.tagging.retry-first-delay", () -> "500ms");
		registry.add("app.etl.tagging.retry-delay", () -> "1s");
		// 운영 기본값과 같은 2개 스레드로 돌려 선점 경합도 함께 지난다.
		registry.add("app.etl.concurrency", () -> "2");
		registry.add("app.etl.embedding.batch-size", () -> "4");
		// 적재를 여러 요청으로 나누는 경로도 지나게 한다.
		registry.add("app.etl.elasticsearch.bulk-size", () -> "2");
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired RunStore runStore;
	@Autowired ChunkRebuilder rebuilder;

	@AfterEach
	void reset() {
		// 장애 흉내를 먼저 걷어 낸 뒤, 워커가 이 테스트의 진행 중 회차를 모두 끝낼 때까지 기다린다 — 다음 테스트와 겹치지 않게.
		CountDownLatch gate = FAKE.embeddingGate;
		FAKE.embeddingGate = null;
		if (gate != null) gate.countDown();
		FAKE.embeddingFailures.set(0);
		FAKE.embeddingCalls.set(0);
		FAKE.wrongDimensions = false;
		FAKE.chatFailures.set(0);
		await("진행 중 회차가 모두 끝남", () -> jdbc.queryForObject(
				"select count(*) from thr_doc_run where status in ('PENDING', 'RUNNING')", Integer.class) == 0);
		FAKE.sources.clear();
	}

	@AfterAll
	static void stopFake() {
		FAKE.close();
	}

	@Test
	void registeredDocumentBecomesReadyWithEveryChunkIndexedAndTraceable() {
		String text = ("휴가 규정 " + "연차는 사흘 전에 신청하고 팀장 승인을 받는다. ".repeat(30) + "\n\n").repeat(4);
		Fixture doc = register("휴가규정.txt", text);

		await(() -> "READY".equals(status(doc)));
		Map<String, Object> run = run(doc, 1);
		assertThat(run).containsEntry("status", "DONE").containsEntry("emb_mdl", "bge-m3").containsEntry("emb_dim", 1024);
		int chunks = (Integer) run.get("chunk_cnt");
		assertThat(chunks).isGreaterThan(2).isEqualTo(count(doc));
		JsonNode first = es().get().uri("/thr_doc_chunk/_doc/{id}?_source_exclude_vectors=false", doc.id + ":1:1").retrieve().body(JsonNode.class).path("_source");
		assertThat(first.path("doc_id").asString()).isEqualTo(doc.id.toString());
		assertThat(first.path("thr_id").asString()).isEqualTo(doc.thread.toString());
		assertThat(first.path("tnn_id").asString()).isEqualTo(doc.tenant.toString());
		assertThat(first.path("loc").asString()).isEqualTo("para=1");
		assertThat(first.path("content").asString()).startsWith("휴가 규정");
		assertThat(first.path("emb").size()).isEqualTo(1024);
	}

	@Test
	void textlessSourceFailsAtOnceWithoutRetry() {
		Fixture doc = register("빈.txt", "   \n\n  ");
		await(() -> "FAILED".equals(status(doc)));
		// 실패 회차는 정리 작업이 곧 PURGED로 바꾼다(청크 정리 끝).
		assertThat(run(doc, 1)).containsEntry("err", "EMPTY_TEXT").containsEntry("att_cnt", 1);
		assertThat(run(doc, 1).get("status")).isIn("FAILED", "PURGED");
	}

	@Test
	void transientEmbeddingOutageIsRetriedUntilReady() {
		FAKE.embeddingFailures.set(2);
		Fixture doc = register("재시도.txt", "일시 장애 뒤 처리된다.");
		await(() -> "READY".equals(status(doc)));
		assertThat(run(doc, 1)).containsEntry("status", "DONE").containsEntry("att_cnt", 3);
	}

	@Test
	void retriesAreBoundedAndTheDocumentFailsWhenExhausted() {
		FAKE.embeddingFailures.set(100);
		Fixture doc = register("소진.txt", "계속 실패한다.");
		await(() -> "FAILED".equals(status(doc)));
		assertThat(run(doc, 1)).containsEntry("err", "RETRY_EXHAUSTED:EMBEDDING_UNAVAILABLE").containsEntry("att_cnt", 3);
	}

	@Test
	void documentDeletedWhileProcessingStaysDeletedAndItsChunksArePurged() throws Exception {
		FAKE.embeddingGate = new CountDownLatch(1);
		int before = FAKE.embeddingCalls.get();
		Fixture doc = register("삭제.txt", "처리 중 삭제된다.");
		// 이 문서의 임베딩 요청이 가짜 서버에서 멈춰 있을 때(임베딩 중) 삭제한다.
		await(() -> "PROCESSING".equals(status(doc)) && FAKE.embeddingCalls.get() > before);
		jdbc.update("update thr_doc set status='DELETED', pnn=false, deleted_at=now() where id=?", doc.id);
		FAKE.embeddingGate.countDown();

		await(() -> "PURGED".equals(run(doc, 1).get("status")));
		assertThat(status(doc)).isEqualTo("DELETED");
		assertThat(count(doc)).isZero();
	}

	@Test
	void reprocessingAFailedDocumentIndexesTheNewRunAndPurgesTheOldOne() {
		FAKE.wrongDimensions = true;
		Fixture doc = register("재처리.txt", "임베딩 계약이 맞지 않아 실패한다.");
		await(() -> "FAILED".equals(status(doc)));
		assertThat(run(doc, 1)).containsEntry("err", "EMBEDDING_CONTRACT");
		FAKE.wrongDimensions = false;

		ThreadDocumentStates.transition(jdbc, doc.id, "FAILED", "PENDING");
		ThreadDocumentStates.createRun(jdbc, doc.id, doc.tenant, doc.thread);
		await(() -> "READY".equals(status(doc)) && "PURGED".equals(run(doc, 1).get("status")));
		assertThat(run(doc, 2)).containsEntry("status", "DONE");
		assertThat(count(doc)).isEqualTo(((Integer) run(doc, 2).get("chunk_cnt")).longValue());
	}

	@Test
	void runAbandonedByADeadWorkerIsResumedAfterItsLease() {
		Fixture doc = register("재개.txt", "죽은 워커의 회차를 다시 처리한다.", "PROCESSING", false);
		insertRunningRun(doc, 1, "now() - interval '1 second'");
		await(() -> "READY".equals(status(doc)));
		assertThat(run(doc, 1)).containsEntry("status", "DONE").containsEntry("att_cnt", 2);
	}

	@Test
	void runThatKeepsKillingWorkersFailsAtTheAttemptLimitInsteadOfBeingReclaimedForever() {
		Fixture doc = register("독.txt", "처리할 때마다 워커를 죽이는 문서라고 가정한다.", "PROCESSING", false);
		int calls = FAKE.embeddingCalls.get();
		// 재시도 간격 2개 → 최대 3회. 세 번째 시도 중 워커가 죽어 시한이 지난 상태.
		insertRunningRun(doc, 3, "now() - interval '1 second'");
		await(() -> "FAILED".equals(status(doc)));
		assertThat(run(doc, 1).get("err")).isEqualTo("RETRY_EXHAUSTED");
		assertThat(run(doc, 1).get("att_cnt")).isEqualTo(3);
		assertThat(FAKE.embeddingCalls.get()).isEqualTo(calls);
	}

	@Test
	void twoWorkersProcessManyDocumentsWithoutClaimingTheSameRunTwice() {
		var docs = java.util.stream.IntStream.range(0, 6)
				.mapToObj(i -> register("동시" + i + ".txt", "동시에 처리되는 문서 " + i + "번의 본문이다."))
				.toList();
		await(() -> docs.stream().allMatch(doc -> "READY".equals(status(doc))));
		for (Fixture doc : docs) {
			assertThat(run(doc, 1)).containsEntry("status", "DONE").containsEntry("att_cnt", 1);
			assertThat(count(doc)).isEqualTo(((Integer) run(doc, 1).get("chunk_cnt")).longValue());
		}
	}

	@Test
	void registeredPendingDocumentWithoutARunGetsOneAndIsProcessed() {
		// 회차 생성 전 버전의 BFF가 확정한 등록(롤링 배포 틈): REGISTERED 사건은 있는데 회차가 없다.
		Fixture doc = register("고아.txt", "회차 없이 남은 등록 문서다.", "PENDING", false);
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("set session_replication_role = replica");
			try (var event = connection.prepareStatement("insert into thr_doc_evt(id, tnn_id, thr_id, doc_id, act_user_id, evt_kind, req_key)"
					+ " values (?, ?, ?, ?, ?, 'REGISTERED', ?)")) {
				event.setObject(1, UUID.randomUUID()); event.setObject(2, doc.tenant); event.setObject(3, doc.thread);
				event.setObject(4, doc.id); event.setObject(5, UUID.randomUUID()); event.setObject(6, doc.id);
				event.executeUpdate();
			}
			statement.execute("update thr_doc set updated_at = now() - interval '2 minutes' where id = '" + doc.id + "'");
		} catch (java.sql.SQLException error) {
			throw new IllegalStateException(error);
		}
		await(() -> "READY".equals(status(doc)));
		assertThat(run(doc, 1)).containsEntry("status", "DONE");
	}

	@Test
	void deletedSearchIndexIsRecreatedWithItsMappingInsteadOfAutoCreatedBlank() {
		Fixture first = register("인덱스1.txt", "인덱스를 지우기 전에 처리한다.");
		await(() -> "READY".equals(status(first)));
		// 운영자가 인덱스를 지웠다. 워커는 인덱스를 이미 확인했다고 기억하고 있다. 공유 인덱스라 다른 테스트의 청크도 지워지지만,
		// 다른 테스트는 자기 문서만 세고(count) 인덱스가 없으면 0으로 본다.
		es().delete().uri("/thr_doc_chunk_v2").retrieve().toBodilessEntity();

		Fixture second = register("인덱스2.txt", "인덱스를 지운 뒤에 처리한다.");
		await(() -> "READY".equals(status(second)));
		JsonNode mapping = es().get().uri("/thr_doc_chunk/_mapping").retrieve().body(JsonNode.class);
		assertThat(mapping.has("thr_doc_chunk_v2")).as("별칭이 원래 이름의 인덱스를 가리킨다").isTrue();
		assertThat(mapping.path("thr_doc_chunk_v2").path("mappings").path("properties").path("emb").path("type").asString())
				.isEqualTo("dense_vector");
		assertThat(count(second)).isEqualTo(((Integer) run(second, 1).get("chunk_cnt")).longValue());
	}

	@Test
	void runReleasedAtShutdownIsPickedUpAtOnceWithoutSpendingAnAttempt() {
		Fixture doc = register("놓아주기.txt", "종료 대기 안에 끝나지 못해 놓아준 회차다.", "PROCESSING", false);
		// 다른 워커가 처리 중(시한 10분 남음)이던 회차. 그 워커가 종료하며 놓아준다.
		UUID run = insertRunningRun(doc, 1, "now() + interval '10 minutes'");
		runStore.release(new RunStore.Job(run, doc.id, doc.tenant, doc.thread, 1, 1, "놓아주기.txt", null, null));

		await(() -> "READY".equals(status(doc)));
		assertThat(run(doc, 1)).containsEntry("status", "DONE").containsEntry("att_cnt", 1);
	}

	/** #348: 운영자 전체 재처리 — 새 회차를 만드는 동안 문서는 READY로 이전 회차 청크가 검색되고, 끝나면 새 회차로 바뀌고 이전 회차가 정리된다. */
	@Test
	void operatorRebuildKeepsTheOldChunksSearchableUntilTheNewRunIsDone() {
		Fixture doc = register("전체재처리.txt", "다시 만드는 동안에도 검색된다. ".repeat(40));
		await(() -> "READY".equals(status(doc)));
		onlyThisDocumentIsLive(doc);
		long before = count(doc);
		FAKE.embeddingGate = new CountDownLatch(1);
		UUID request = UUID.randomUUID();
		jdbc.update("insert into doc_rbl(id, kind, status, req_subj) values (?, 'ALL', 'PENDING', 'admin')", request);

		rebuilder.tick();

		await("새 회차가 임베딩에서 멈춤", () -> jdbc.queryForObject("select count(*) from thr_doc_run where doc_id=? and run_seq=2 and status='RUNNING'",
				Integer.class, doc.id) == 1);
		assertThat(status(doc)).isEqualTo("READY");
		assertThat(count(doc)).as("이전 회차 청크가 그대로 있다").isEqualTo(before);
		assertThat(jdbc.queryForMap("select status, trg_cnt from doc_rbl where id=?", request).get("status")).isEqualTo("COMPLETED");
		FAKE.embeddingGate.countDown();
		FAKE.embeddingGate = null;

		await(() -> "PURGED".equals(run(doc, 1).get("status")));
		assertThat(run(doc, 2)).containsEntry("status", "DONE").containsEntry("run_kind", "REBUILD").containsEntry("rbl_id", request);
		assertThat(status(doc)).isEqualTo("READY");
		assertThat(count(doc)).isEqualTo(((Integer) run(doc, 2).get("chunk_cnt")).longValue());
	}

	/** #348: 검색 인덱스가 통째로 지워져도 운영자 조작 없이 빠진 문서를 원본에서 다시 만든다. 그동안 문서는 READY 그대로다. */
	@Test
	void documentsWhoseChunksVanishedWithTheIndexAreRecoveredFromTheirSources() {
		Fixture doc = register("자동복구.txt", "인덱스가 지워져도 다시 만들어진다. ".repeat(40));
		await(() -> "READY".equals(status(doc)));
		onlyThisDocumentIsLive(doc);
		es().delete().uri("/thr_doc_chunk_v2").retrieve().toBodilessEntity();

		rebuilder.verify();
		rebuilder.tick();

		await("복구 회차 완료", () -> {
			assertThat(status(doc)).as("복구하는 동안에도 READY").isEqualTo("READY");
			return "DONE".equals(jdbc.queryForObject("select status from thr_doc_run where doc_id=? and run_seq=2", String.class, doc.id));
		});
		assertThat(run(doc, 2)).containsEntry("run_kind", "RECOVER");
		assertThat(count(doc)).isEqualTo(((Integer) run(doc, 2).get("chunk_cnt")).longValue());
	}

	/** #362: 검색 준비가 끝난 문서에 태깅 작업이 태그를 붙이고(DB·태그 색인), 태깅 설정이 바뀌면 태그만 다시 뽑는다 — 임베딩은 다시 하지 않는다. */
	@Test
	void readyDocumentsAreTaggedAndRetaggedWithoutReembedding() {
		Fixture doc = register("태깅.txt", "연차 이월은 다음 해 3월 말까지다. ".repeat(20));
		await(() -> "READY".equals(status(doc)));
		await("태그 확정", () -> "DONE".equals(tagStatus(doc)));
		Map<String, Object> tag = jdbc.queryForMap("select ctg, array_to_string(kyw, ',') as kyw, smm from thr_doc_tag where doc_id=? and run_seq=1", doc.id);
		assertThat(tag).containsEntry("ctg", "인사·총무").containsEntry("kyw", "연차,이월").containsEntry("smm", "휴가 규정이다.");
		JsonNode indexed = es().get().uri("/thr_doc_tag/_doc/{id}", doc.id + ":1").retrieve().body(JsonNode.class).path("_source");
		assertThat(indexed.path("thr_id").asString()).isEqualTo(doc.thread.toString());
		assertThat(indexed.path("kyw").get(0).asString()).isEqualTo("연차");

		int embeddings = FAKE.embeddingCalls.get();
		jdbc.update("update thr_doc_tag set tag_cnf = 'tag-v0:old' where doc_id=?", doc.id);
		await("설정이 바뀐 태그를 다시 뽑음", () -> !"tag-v0:old".equals(jdbc.queryForObject("select tag_cnf from thr_doc_tag where doc_id=?", String.class, doc.id)));
		assertThat(FAKE.embeddingCalls.get()).as("태그만 다시 뽑고 임베딩은 다시 하지 않는다").isEqualTo(embeddings);
		assertThat(run(doc, 1)).as("새 회차를 만들지 않는다").containsEntry("status", "DONE");

		// 태그 색인만 사라지면(색인 삭제·스냅샷 복원) 다음 주기에 다시 만들고 DB의 태그로 채운다 — LLM은 다시 부르지 않는다.
		await("재태깅 반영", () -> "DONE".equals(tagStatus(doc)));
		int chats = FAKE.chatCalls.get();
		es().delete().uri("/thr_doc_tag_v1").retrieve().toBodilessEntity();
		await("태그 색인을 DB 태그로 다시 채움", () -> es().get().uri("/thr_doc_tag/_doc/{id}", doc.id + ":1")
				.exchange((request, response) -> response.getStatusCode().value()) == 200);
		assertThat(FAKE.chatCalls.get()).as("다시 태깅하지 않는다").isEqualTo(chats);
	}

	/** #362: 태깅 서버가 죽어 있어도 문서는 먼저 검색 준비 완료가 되고, 서버가 돌아오면 태그가 붙는다. 문서를 지우면 태그도 지운다. */
	@Test
	void aTaggingOutageDoesNotDelayReadinessAndTagsAreDroppedWithTheDocument() {
		FAKE.chatFailures.set(1_000_000);
		Fixture doc = register("태깅장애.txt", "태깅 서버가 잠시 멈춰도 문서는 준비된다. ".repeat(20));
		await(() -> "READY".equals(status(doc)));
		await("태깅 실패 기록", () -> "FAILED".equals(tagStatus(doc)));
		assertThat(status(doc)).isEqualTo("READY");

		FAKE.chatFailures.set(0);
		await("서버가 돌아온 뒤 태그", () -> "DONE".equals(tagStatus(doc)));

		jdbc.update("update thr_doc set status='DELETED', pnn=false, deleted_at=now() where id=?", doc.id);
		await("정리와 함께 태그 삭제", () -> tagStatus(doc) == null);
		Integer found = es().get().uri("/thr_doc_tag/_doc/{id}", doc.id + ":1").exchange((request, response) -> response.getStatusCode().value());
		assertThat(found).as("태그 색인 문서도 지운다").isEqualTo(404);
	}

	private String tagStatus(Fixture doc) {
		var rows = jdbc.queryForList("select status from thr_doc_tag where doc_id=? and run_seq=1", String.class, doc.id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 앞 테스트들이 남긴 문서를 지운 것으로 둔다 — 전체 재처리·자동 복구가 원본이 이미 없는 그 문서들까지 잡아 결과가 섞이지 않게. */
	private void onlyThisDocumentIsLive(Fixture doc) {
		jdbc.update("update thr_doc set status = 'DELETED', pnn = false, deleted_at = now() where id <> ? and status <> 'DELETED'", doc.id);
	}

	private record Fixture(UUID id, UUID tenant, UUID thread) { }

	/** 워커가 처리 중이던(RUNNING) 1회차를 직접 만든다. nextAt은 선점 시한 SQL 식이다. */
	private UUID insertRunningRun(Fixture doc, int attempts, String nextAt) {
		UUID run = UUID.randomUUID();
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, att_cnt, next_at) values (?, ?, ?, ?, 1, 'RUNNING', ?, "
				+ nextAt + ")", run, doc.id, doc.tenant, doc.thread, attempts);
		return run;
	}

	private Fixture register(String fileName, String text) {
		return register(fileName, text, "PENDING", true);
	}

	/** 등록 확정 상태의 문서. 방·사용자 FK는 ETL과 무관해 이 연결에서만 건너뛴다. */
	private Fixture register(String fileName, String text, String status, boolean queue) {
		Fixture doc = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		String digest = SourceReader.sha256(bytes);
		FAKE.sources.put(digest, bytes);
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("set session_replication_role = replica");
			try (var insert = connection.prepareStatement("insert into thr_doc(id, tnn_id, thr_id, user_id, file_name, file_size, src_key,"
					+ " src_att_id, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				insert.setObject(1, doc.id); insert.setObject(2, doc.tenant); insert.setObject(3, doc.thread);
				insert.setObject(4, UUID.randomUUID()); insert.setString(5, fileName); insert.setLong(6, bytes.length);
				insert.setString(7, digest); insert.setObject(8, UUID.randomUUID()); insert.setString(9, status);
				insert.executeUpdate();
			}
		} catch (java.sql.SQLException error) {
			throw new IllegalStateException(error);
		}
		if (queue) ThreadDocumentStates.createRun(jdbc, doc.id, doc.tenant, doc.thread);
		return doc;
	}

	private String status(Fixture doc) {
		return jdbc.queryForObject("select status from thr_doc where id=?", String.class, doc.id);
	}

	private Map<String, Object> run(Fixture doc, int seq) {
		return jdbc.queryForMap("select * from thr_doc_run where doc_id=? and run_seq=?", doc.id, seq);
	}

	/** 인덱스가 아직 없으면(한 번도 적재하지 않음) 0건이다. */
	private long count(Fixture doc) {
		try {
			es().post().uri("/thr_doc_chunk/_refresh").retrieve().toBodilessEntity();
			return es().post().uri("/thr_doc_chunk/_count").header("Content-Type", "application/json")
					.body("{\"query\":{\"term\":{\"doc_id\":\"" + doc.id + "\"}}}").retrieve().body(JsonNode.class).path("count").asLong();
		} catch (org.springframework.web.client.HttpClientErrorException.NotFound noIndex) {
			return 0;
		}
	}

	private RestClient es;

	private RestClient es() {
		if (es == null) es = RestClient.create("http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
		return es;
	}

	private static void await(BooleanSupplier condition) {
		await("조건", condition);
	}

	private static void await(String what, BooleanSupplier condition) {
		Instant deadline = Instant.now().plusSeconds(60);
		while (!condition.getAsBoolean()) {
			if (Instant.now().isAfter(deadline)) throw new AssertionError("60초 안에 이뤄지지 않았다: " + what);
			try {
				Thread.sleep(100);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AssertionError(interrupted);
			}
		}
	}
}
