package com.onggijonggi.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : ThrInv.java
 * Description : 아직 로그인한 적 없는 사람에 대한 Thread 초대(V14__thread_invitation.sql `thr_inv`,
 *               이슈 #127).
 *
 *               대상을 subject 문자열로 들고 app_user FK를 걸지 않는 것이 이 엔티티의 존재
 *               이유다 — 그 사람은 아직 app_user 행이 없다. 반면 초대자(createdByUserId)는 지금
 *               로그인해 있는 사람이라 FK가 있다. 이 비대칭이 의도된 것이다.
 *
 *               끝난 초대는 행을 지우지 않고 status·endedAt·endRsn으로 표시한다(ThrMbr과 같은 이유).
 */
@Entity
@Table(name = "thr_inv")
public class ThrInv {

	@Id
	private UUID id;

	@Column(name = "thr_id", nullable = false)
	private UUID thrId;

	/** 초대 대상의 Keycloak subject. app_user 행이 없는 사람이라 내부 id로 가리킬 수 없다. */
	@Column(nullable = false)
	private String subj;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ThrInvStatus status;

	@Column(name = "created_by_user_id", nullable = false)
	private UUID createdByUserId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "end_rsn")
	private String endRsn;

	protected ThrInv() {
	}

	public ThrInv(UUID thrId, String subj, UUID createdByUserId) {
		this.id = UUID.randomUUID();
		this.thrId = thrId;
		this.subj = subj;
		this.status = ThrInvStatus.PENDING;
		this.createdByUserId = createdByUserId;
		this.createdAt = Instant.now();
	}

	/**
	 * 대기 중인 초대를 끝낸다. 수락(ACCEPTED)과 취소(REVOKED) 모두 이 경로를 쓴다 —
	 * DB CHECK가 "PENDING이 아니면 종료 정보가 있어야 한다"를 강제하므로 사유를 함께 받는다.
	 */
	public void end(ThrInvStatus endStatus, String reason) {
		this.status = endStatus;
		this.endedAt = Instant.now();
		this.endRsn = reason;
	}

	public UUID getId() {
		return id;
	}

	public UUID getThrId() {
		return thrId;
	}

	public String getSubj() {
		return subj;
	}

	public ThrInvStatus getStatus() {
		return status;
	}

	public UUID getCreatedByUserId() {
		return createdByUserId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public String getEndRsn() {
		return endRsn;
	}

}
