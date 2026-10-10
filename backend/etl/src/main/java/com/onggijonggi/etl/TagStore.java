package com.onggijonggi.etl;

import com.onggijonggi.common.document.TagPrompt;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Class Name : TagStore.java
 * Description : 처리 회차별 태그(thr_doc_tag, #362)의 대상 조회와 저장. 태깅 대상은 READY 문서의 현재 회차(가장 큰 DONE 회차 — 검색
 *               범위 ThreadDocumentService.searchScope와 같은 정의) 중 태그가 없거나, 태깅 설정이 지금과 다르거나, 실패해 다시 시도할 때가
 *               된 것이다. 미분류(DONE)는 설정이 바뀌기 전에는 다시 하지 않는다.
 *               이미 태그가 있는 회차의 재태깅이 실패하면 기존 태그를 지우지 않고 간격만 둔다 — 검색은 이전 태그로 계속된다.
 */
@Component
public class TagStore {

	/** 태깅할 회차와 원본을 읽는 데 필요한 문서 snapshot. */
	public record Target(UUID document, UUID tenant, UUID thread, int runSeq, String fileName, String digest, UUID sourceAttempt) {

		/** 원본 읽기(SourceReader)가 쓰는 처리 작업 모양. 회차 ID·시도 횟수는 쓰지 않는다. */
		RunStore.Job job() {
			return new RunStore.Job(null, document, tenant, thread, runSeq, 0, fileName, digest, sourceAttempt);
		}
	}

	/** stored를 처음 부를 때의 기준. PostgreSQL은 uuid를 바이트(부호 없음)로 비교하므로 모두 0인 값이 가장 작다(Java UUID 비교와 다르다). */
	public static final UUID FIRST = new UUID(0, 0);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public TagStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
	}

	/**
	 * 태깅할 회차를 최대 limit개. 대상은 READY 문서의 현재 회차(가장 큰 DONE 회차) 가운데
	 *   1. 태그 행이 없다,
	 *   2. 다시 할 때가 됐다(next_at 지남) — 실패했거나(FAILED) 태그의 설정 지문이 지금과 다르다,
	 *   3. 실패했고 설정 지문이 지금과 다르다 — 설정을 바꿨으면 실패 간격(영구 실패 7일)을 기다리지 않는다.
	 * 태그가 있던 회차의 재태깅이 실패하면 행은 DONE(이전 태그)으로 남고 next_at만 미뤄진다(saveFailed) — 그래서 2에 걸린다.
	 * 순서는 태그 없음 → 이전 태그가 있는 재태깅 → 실패 재시도다.
	 */
	public List<Target> targets(String fingerprint, int limit) {
		return jdbc.query("select d.id, d.tnn_id, d.thr_id, c.run_seq, d.file_name, d.src_key, d.src_att_id"
				+ " from thr_doc d join thr_doc_run c on c.doc_id = d.id and c.status = 'DONE'"
				+ " and c.run_seq = (select max(m.run_seq) from thr_doc_run m where m.doc_id = d.id and m.status = 'DONE')"
				+ " left join thr_doc_tag t on t.doc_id = d.id and t.run_seq = c.run_seq"
				+ " where d.status = 'READY' and (t.id is null"
				+ " or (t.next_at <= now() and (t.status = 'FAILED' or t.tag_cnf <> ?))"
				+ " or (t.status = 'FAILED' and t.tag_cnf <> ?))"
				+ " order by case when t.id is null then 0 when t.status = 'DONE' then 1 else 2 end, c.updated_at, d.id limit ?",
				(rs, row) -> new Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4),
						rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class)),
				fingerprint, fingerprint, limit);
	}

	/**
	 * 태그를 확정한다. 그 회차가 아직 현재 처리 결과로 남아 있을 때만(회차 DONE, 문서 READY) — 태깅하는 동안 문서가 지워지거나 회차가
	 * 정리됐으면 남기지 않고 false를 돌려준다(호출자가 태그 색인에 쓴 문서를 지운다).
	 */
	public boolean saveDone(Target target, TagPrompt.Tags tags, String fingerprint) {
		Boolean saved = transactions.execute(tx -> {
			if (jdbc.queryForList("select d.id from thr_doc d join thr_doc_run r on r.doc_id = d.id and r.run_seq = ? and r.status = 'DONE'"
					+ " where d.id = ? and d.status = 'READY' for share of d, r", target.runSeq(), target.document()).isEmpty())
				return false;
			jdbc.update(con -> {
				PreparedStatement statement = con.prepareStatement("insert into thr_doc_tag(id, doc_id, tnn_id, thr_id, run_seq, status, ctg, kyw, smm, tag_cnf)"
						+ " values (?, ?, ?, ?, ?, 'DONE', ?, ?, ?, ?) on conflict (doc_id, run_seq) do update set status = 'DONE', ctg = excluded.ctg,"
						+ " kyw = excluded.kyw, smm = excluded.smm, tag_cnf = excluded.tag_cnf, att_cnt = 0, err = null, next_at = now(), updated_at = now()");
				Array keywords = con.createArrayOf("text", tags.keywords().toArray());
				statement.setObject(1, UUID.randomUUID());
				statement.setObject(2, target.document());
				statement.setObject(3, target.tenant());
				statement.setObject(4, target.thread());
				statement.setInt(5, target.runSeq());
				statement.setString(6, tags.category());
				statement.setArray(7, keywords);
				statement.setString(8, tags.summary());
				statement.setString(9, fingerprint);
				return statement;
			});
			return true;
		});
		return Boolean.TRUE.equals(saved);
	}

	/**
	 * 태깅 실패를 남긴다. 다음 시도는 firstDelay 뒤이고, 잇따라 실패할수록 두 배씩 늘려 maxDelay까지 간다. 이미 태그가 있는 회차(설정 변경
	 * 재태깅 실패)는 태그를 지우지 않고 간격만 둔다. 그 회차가 지워졌으면 아무것도 남기지 않는다.
	 */
	public void saveFailed(Target target, String code, String fingerprint, Duration firstDelay, Duration maxDelay) {
		transactions.executeWithoutResult(tx -> {
			// saveDone과 같이 회차 행을 잠가 정리(purged)와 순서를 지킨다 — 정리가 끝난 회차에 실패 행을 남기지 않게.
			if (jdbc.queryForList("select id from thr_doc_run where doc_id = ? and run_seq = ? and status = 'DONE' for share", target.document(),
					target.runSeq()).isEmpty())
				return;
			jdbc.update("insert into thr_doc_tag(id, doc_id, tnn_id, thr_id, run_seq, status, tag_cnf, att_cnt, err, next_at)"
					+ " values (?, ?, ?, ?, ?, 'FAILED', ?, 1, ?, now() + ? * interval '1 millisecond')"
					+ " on conflict (doc_id, run_seq) do update set att_cnt = thr_doc_tag.att_cnt + 1, err = excluded.err,"
					+ " next_at = now() + least(?, ? * power(2, least(thr_doc_tag.att_cnt, 20))) * interval '1 millisecond', updated_at = now(),"
					// 실패만 있던 행은 지금 설정 지문으로 맞춘다. 태그가 있는 행(DONE)은 이전 지문을 그대로 둬 다음 간격 뒤 다시 대상이 된다.
					+ " tag_cnf = case when thr_doc_tag.status = 'FAILED' then excluded.tag_cnf else thr_doc_tag.tag_cnf end",
					UUID.randomUUID(), target.document(), target.tenant(), target.thread(), target.runSeq(), fingerprint, code, firstDelay.toMillis(),
				maxDelay.toMillis(), firstDelay.toMillis());
		});
	}

	/**
	 * 태깅 실패의 대기 간격과 재시도 횟수를 풀어 바로 다시 대상이 되게 한다(ETL 기동 때). 태깅 설정·서버 주소는 재기동으로만 바뀌므로,
	 * 고친 뒤 영구 실패의 긴 간격이나 늘어난 일시 장애 간격을 기다리지 않게 한다. 이미 태그가 있는 회차의 재태깅 실패도 포함한다.
	 * 풀어 준 행 수를 돌려준다.
	 */
	public int releaseFailures() {
		return jdbc.update("update thr_doc_tag set next_at = now(), att_cnt = 0, updated_at = now() where err is not null and next_at > now()");
	}

	/** DB에 있는 태그 한 건(태그 색인을 다시 채울 때 쓴다). */
	public record Stored(UUID id, UUID document, UUID tenant, UUID thread, int runSeq, TagPrompt.Tags tags, String fingerprint) {
	}

	/**
	 * 검색에 쓰일 수 있는 태그(READY 문서의 DONE 회차에 붙은 DONE 태그)를 id 순으로 after 다음부터 최대 limit건. 태그 색인을 새로 만든
	 * 뒤 DB 기준으로 다시 채울 때 쓴다 — LLM을 다시 부르지 않는다.
	 */
	public List<Stored> stored(UUID after, int limit) {
		return jdbc.query("select t.id, t.doc_id, t.tnn_id, t.thr_id, t.run_seq, t.ctg, t.kyw, t.smm, t.tag_cnf from thr_doc_tag t"
				+ " join thr_doc d on d.id = t.doc_id and d.status = 'READY'"
				+ " join thr_doc_run r on r.doc_id = t.doc_id and r.run_seq = t.run_seq and r.status = 'DONE'"
				+ " where t.status = 'DONE' and t.id > ? order by t.id limit ?",
				(rs, row) -> new Stored(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
						rs.getInt(5), new TagPrompt.Tags(rs.getString(6), List.of((String[]) rs.getArray(7).getArray()), rs.getString(8) == null ? "" : rs.getString(8)),
						rs.getString(9)),
				after, limit);
	}

	/** 회차 정리(RunSweeper) 때 그 회차의 태그를 지운다. 멱등이다. */
	public void delete(UUID document, int runSeq) {
		jdbc.update("delete from thr_doc_tag where doc_id = ? and run_seq = ?", document, runSeq);
	}
}
