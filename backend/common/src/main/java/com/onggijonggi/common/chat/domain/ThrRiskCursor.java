package com.onggijonggi.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : ThrRiskCursor.java
 * Description : 위험 발화 사후 검증 배치가 스레드별로 어디까지 스캔했는지(V12__thr_risk_cursor.sql
 *               `thr_risk_crs`). msg 행을 건드리지 않는 이유는 클래스 Msg의 설명을 참고한다(#28).
 *
 *               frsSeqChc(V20260918005001015__thr_risk_cursor_seq0_checked.sql, 이슈 #234)는
 *               이 방의 seq=0 HUMAN 메시지가 위험 검사를 받았는지다. lastSeq 기본값이 0이고
 *               조회가 seq > lastSeq라 커서 없는 방의 첫 메시지(seq=0)가 영원히 스캔에서
 *               빠지는 결함이 있었다 — 새로 생성되는 커서는 true로 시작해(수정된 스캔이
 *               seq=0부터 포함하므로) 소급 검사가 필요 없고, 마이그레이션으로 이미 존재하던
 *               행만 false로 표시돼 다음 스캔에서 한 번 소급 검사된다.
 */
@Entity
@Table(name = "thr_risk_crs")
public class ThrRiskCursor {

	@Id
	@Column(name = "thr_id")
	private UUID thrId;

	/** 이 값은 DB trigger가 Thread의 tnn_id로 채운다. 엔티티는 쓰지 않는다(쓰면 trigger가 채운 값을 null로 덮어쓸 수 있다). */
	@Column(name = "tnn_id", insertable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "last_seq", nullable = false)
	private long lastSeq;

	@Column(name = "frs_seq_chc", nullable = false)
	private boolean frsSeqChc;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ThrRiskCursor() {
	}

	public ThrRiskCursor(UUID thrId) {
		this.thrId = thrId;
		this.lastSeq = 0L;
		this.frsSeqChc = true;
		this.updatedAt = Instant.now();
	}

	public UUID getThrId() {
		return thrId;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public long getLastSeq() {
		return lastSeq;
	}

	public boolean isFrsSeqChc() {
		return frsSeqChc;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void advanceTo(long seq) {
		this.lastSeq = seq;
		this.updatedAt = Instant.now();
	}

	public void markFrsSeqChc() {
		this.frsSeqChc = true;
		this.updatedAt = Instant.now();
	}

}
