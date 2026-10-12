package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.common.document.TagPrompt;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : TagStoreTest.java
 * Description : 처리 회차별 태그(#362)의 대상 조회와 저장을 실제 최신 스키마(PostgreSQL)에서 확인한다 — 현재 회차만, READY만, 태그 없음 →
 *               설정이 바뀐 태그 → 실패 재시도 순서, 미분류·같은 설정은 다시 하지 않음, 재태깅 실패가 기존 태그를 지우지 않음, 정리된
 *               회차에는 태그를 남기지 않음.
 */
@Testcontainers(disabledWithoutDocker = true)
class TagStoreTest {

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("tags").withUsername("test").withPassword("test");
	static JdbcTemplate jdbc;
	static TagStore store;
	static final String CURRENT = "tag-v1:m:now";

	@BeforeAll
	static void setUp() {
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		store = new TagStore(jdbc, new DataSourceTransactionManager(dataSource));
	}

	@BeforeEach
	void clear() {
		jdbc.execute("truncate thr_doc_tag, thr_doc_run, thr_doc cascade");
	}

	@Test
	void targetsAreTheCurrentRunsOfReadyDocumentsThatNeedTagsInPriorityOrder() throws SQLException {
		UUID untagged = readyDocument();
		UUID upToDate = readyDocument();
		tag(upToDate, 1, "DONE", CURRENT, "now() - interval '1 hour'");
		UUID unclassified = readyDocument();
		jdbc.update("insert into thr_doc_tag(id, doc_id, tnn_id, thr_id, run_seq, status, ctg, tag_cnf) values (?, ?, ?, ?, 1, 'DONE', ?, ?)",
				UUID.randomUUID(), unclassified, UUID.randomUUID(), UUID.randomUUID(), TagPrompt.UNCLASSIFIED, CURRENT);
		UUID outdated = readyDocument();
		tag(outdated, 1, "DONE", "tag-v1:m:old", "now() - interval '1 hour'");
		UUID retryDue = readyDocument();
		tag(retryDue, 1, "FAILED", CURRENT, "now() - interval '1 second'");
		UUID retryLater = readyDocument();
		tag(retryLater, 1, "FAILED", CURRENT, "now() + interval '1 hour'");
		UUID failedDoc = readyDocument();
		jdbc.update("update thr_doc set status = 'FAILED' where id = ?", failedDoc);
		UUID rebuilt = readyDocument();
		tag(rebuilt, 1, "DONE", CURRENT, "now() - interval '1 hour'");
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status) values (?, ?, ?, ?, 2, 'DONE')",
				UUID.randomUUID(), rebuilt, UUID.randomUUID(), UUID.randomUUID());

		var targets = store.targets(CURRENT, 20);

		assertThat(targets).extracting(TagStore.Target::document).containsExactlyInAnyOrder(untagged, outdated, retryDue, rebuilt);
		assertThat(targets.get(targets.size() - 2).document()).as("설정이 바뀐 태그는 태그 없음 뒤").isEqualTo(outdated);
		assertThat(targets.get(targets.size() - 1).document()).as("실패 재시도가 마지막").isEqualTo(retryDue);
		assertThat(targets).filteredOn(target -> target.document().equals(rebuilt)).singleElement()
				.satisfies(target -> assertThat(target.runSeq()).as("현재(가장 큰 DONE) 회차").isEqualTo(2));
		assertThat(store.targets(CURRENT, 1)).hasSize(1);
	}

	@Test
	void tagsAreSavedOnlyWhileTheRunIsStillCurrentAndAFailedRetagKeepsTheOldTags() throws SQLException {
		UUID document = readyDocument();
		TagStore.Target target = store.targets(CURRENT, 10).get(0);

		assertThat(store.saveDone(target, new TagPrompt.Tags("기타", List.of("연차", "이월"), "요약"), CURRENT)).isTrue();
		Map<String, Object> row = jdbc.queryForMap("select status, ctg, array_to_string(kyw, ',') as kyw, smm, tag_cnf from thr_doc_tag where doc_id = ?", document);
		assertThat(row).containsEntry("status", "DONE").containsEntry("ctg", "기타").containsEntry("kyw", "연차,이월").containsEntry("smm", "요약");

		store.saveFailed(target, "TAGGING_UNAVAILABLE", "tag-v1:m:newer", Duration.ofHours(1), Duration.ofHours(1));
		assertThat(jdbc.queryForMap("select status, ctg, tag_cnf, err from thr_doc_tag where doc_id = ?", document))
				.as("이전 태그는 남기고 간격만 둔다").containsEntry("status", "DONE").containsEntry("ctg", "기타").containsEntry("tag_cnf", CURRENT)
				.containsEntry("err", "TAGGING_UNAVAILABLE");
		assertThat(store.targets("tag-v1:m:newer", 10)).as("간격이 지나기 전에는 다시 잡지 않는다").isEmpty();

		jdbc.update("update thr_doc set status = 'DELETED', pnn = false, deleted_at = now() where id = ?", document);
		assertThat(store.saveDone(target, TagPrompt.Tags.unclassified(), CURRENT)).as("지워진 문서에는 남기지 않는다").isFalse();
		store.delete(document, 1);
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_tag", Integer.class)).isZero();
	}

	@Test
	void aFirstFailureIsRecordedForALaterRetry() throws SQLException {
		UUID document = readyDocument();
		TagStore.Target target = store.targets(CURRENT, 10).get(0);

		store.saveFailed(target, "SOURCE_MISSING", CURRENT, Duration.ofDays(7), Duration.ofDays(7));

		assertThat(jdbc.queryForMap("select status, ctg, tag_cnf, att_cnt from thr_doc_tag where doc_id = ?", document))
				.containsEntry("status", "FAILED").containsEntry("ctg", null).containsEntry("tag_cnf", CURRENT).containsEntry("att_cnt", 1);
		assertThat(store.targets(CURRENT, 10)).isEmpty();
		assertThat(store.targets("tag-v1:m:changed", 10)).as("설정이 바뀌면 영구 실패의 긴 간격을 기다리지 않는다").hasSize(1);
		jdbc.update("update thr_doc_tag set next_at = now() - interval '1 second'");
		assertThat(store.targets(CURRENT, 10)).hasSize(1);
	}

	/** ETL 기동 때 지난 실패의 간격·횟수를 풀어 바로 다시 대상이 되게 한다 — 태그가 있는 회차의 재태깅 실패도(설정·주소를 고쳐 재기동한 경우). */
	@Test
	void failuresAreReleasedOnStartupIncludingAFailedRetag() throws SQLException {
		UUID failed = readyDocument();
		UUID retagged = readyDocument();
		for (TagStore.Target target : store.targets(CURRENT, 10)) {
			if (target.document().equals(retagged)) store.saveDone(target, new TagPrompt.Tags("기타", List.of(), "요약"), "tag-v1:m:old");
			store.saveFailed(target, "TAGGING_REJECTED", CURRENT, Duration.ofDays(7), Duration.ofDays(7));
		}
		assertThat(store.targets(CURRENT, 10)).isEmpty();

		assertThat(store.releaseFailures()).isEqualTo(2);

		assertThat(store.targets(CURRENT, 10)).extracting(TagStore.Target::document).containsExactlyInAnyOrder(failed, retagged);
		assertThat(jdbc.queryForObject("select sum(att_cnt) from thr_doc_tag", Integer.class)).isZero();
		assertThat(jdbc.queryForMap("select status, ctg from thr_doc_tag where doc_id = ?", retagged)).as("태그는 그대로").containsEntry("status", "DONE")
				.containsEntry("ctg", "기타");
		assertThat(store.releaseFailures()).as("이미 풀린 행은 다시 세지 않는다").isZero();
	}

	/** 태그 색인을 다시 채울 대상은 READY 문서의 DONE 회차에 붙은 DONE 태그뿐이고, id 순으로 나눠 읽는다. */
	@Test
	void storedTagsAreTheSearchableOnesInPages() throws SQLException {
		UUID first = readyDocument();
		UUID second = readyDocument();
		UUID failed = readyDocument();
		UUID deleted = readyDocument();
		for (TagStore.Target target : store.targets(CURRENT, 10)) {
			if (target.document().equals(failed)) store.saveFailed(target, "TAGGING_REJECTED", CURRENT, Duration.ofDays(7), Duration.ofDays(7));
			else store.saveDone(target, new TagPrompt.Tags("기타", List.of("연차", "이월"), "요약"), CURRENT);
		}
		jdbc.update("update thr_doc set status = 'DELETED', pnn = false, deleted_at = now() where id = ?", deleted);
		// id 첫 바이트가 0x00·0xff인 경우를 둔다 — PostgreSQL uuid 순서(바이트, 부호 없음)의 양 끝을 모두 읽는지 본다.
		jdbc.update("update thr_doc_tag set id = '00000000-0000-4000-8000-000000000001' where doc_id = ?", first);
		jdbc.update("update thr_doc_tag set id = 'ffffffff-0000-4000-8000-000000000001' where doc_id = ?", second);

		var page = store.stored(TagStore.FIRST, 1);
		var rest = store.stored(page.get(0).id(), 10);

		assertThat(page).hasSize(1);
		assertThat(List.of(page.get(0), rest.get(0))).extracting(TagStore.Stored::document).containsExactly(first, second);
		assertThat(rest).hasSize(1).singleElement().satisfies(tag -> {
			assertThat(tag.tags()).isEqualTo(new TagPrompt.Tags("기타", List.of("연차", "이월"), "요약"));
			assertThat(tag.fingerprint()).isEqualTo(CURRENT);
			assertThat(tag.runSeq()).isEqualTo(1);
		});
	}

	/** 일시 장애가 잇따르면 간격을 두 배씩 늘려 상한까지 간다. 성공하면 횟수·사유가 지워진다. */
	@Test
	void repeatedFailuresBackOffUpToTheLimitAndASuccessClearsThem() throws SQLException {
		readyDocument();
		TagStore.Target target = store.targets(CURRENT, 10).get(0);

		for (long expected : new long[] {60, 120, 240, 240}) {
			store.saveFailed(target, "TAGGING_UNAVAILABLE", CURRENT, Duration.ofMinutes(1), Duration.ofMinutes(4));
			assertThat(jdbc.queryForObject("select round(extract(epoch from next_at - now())) from thr_doc_tag", Long.class)).isBetween(expected - 2, expected);
		}
		assertThat(jdbc.queryForObject("select att_cnt from thr_doc_tag", Integer.class)).isEqualTo(4);

		assertThat(store.saveDone(target, new TagPrompt.Tags("기타", List.of("연차"), "요약"), CURRENT)).isTrue();
		assertThat(jdbc.queryForMap("select status, att_cnt, err from thr_doc_tag")).containsEntry("status", "DONE").containsEntry("att_cnt", 0)
				.containsEntry("err", null);
	}

	private static void tag(UUID document, int runSeq, String status, String fingerprint, String nextAt) {
		jdbc.update("insert into thr_doc_tag(id, doc_id, tnn_id, thr_id, run_seq, status, ctg, tag_cnf, next_at) values (?, ?, ?, ?, ?, ?, ?, ?, "
				+ nextAt + ")", UUID.randomUUID(), document, UUID.randomUUID(), UUID.randomUUID(), runSeq, status, "DONE".equals(status) ? "기타" : null,
				fingerprint);
	}

	/** READY 문서와 그 1회차(DONE). 방·사용자 FK는 이 저장소와 무관해 픽스처 연결에서만 건너뛴다. */
	private static UUID readyDocument() throws SQLException {
		UUID id = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				var statement = connection.createStatement()) {
			statement.execute("set session_replication_role = replica");
			try (var doc = connection.prepareStatement("insert into thr_doc(id, tnn_id, thr_id, user_id, file_name, file_size, src_key, status)"
					+ " values (?, ?, ?, ?, 'a.txt', 1, ?, 'READY')")) {
				doc.setObject(1, id); doc.setObject(2, UUID.randomUUID()); doc.setObject(3, UUID.randomUUID()); doc.setObject(4, UUID.randomUUID());
				doc.setString(5, "a".repeat(64));
				doc.executeUpdate();
			}
		}
		jdbc.update("insert into thr_doc_run(id, doc_id, tnn_id, thr_id, run_seq, status, chunk_cnt) values (?, ?, ?, ?, 1, 'DONE', 3)",
				UUID.randomUUID(), id, UUID.randomUUID(), UUID.randomUUID());
		return id;
	}
}
