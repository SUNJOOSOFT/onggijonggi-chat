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
 */
@Entity
@Table(name = "thr_risk_crs")
public class ThrRiskCursor {

	@Id
	@Column(name = "thr_id")
	private UUID thrId;

	@Column(name = "last_seq", nullable = false)
	private long lastSeq;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ThrRiskCursor() {
	}

	public ThrRiskCursor(UUID thrId) {
		this.thrId = thrId;
		this.lastSeq = 0L;
		this.updatedAt = Instant.now();
	}

	public UUID getThrId() {
		return thrId;
	}

	public long getLastSeq() {
		return lastSeq;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void advanceTo(long seq) {
		this.lastSeq = seq;
		this.updatedAt = Instant.now();
	}

}
