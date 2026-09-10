package com.onggijonggi.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : ThrMbr.java
 * Description : Thread 참가자(V10__thread_participant.sql `thr_mbr`). PK가 (thr_id, user_id)가 아니라
 *               대리키라, 같은 사람이 나갔다 재초대되면 과거 행을 남긴 채 새 행이 생긴다. 중복 참가는
 *               활성 행에 대한 부분 유니크 인덱스가 막는다.
 *
 *               끝난 참가는 행을 지우지 않고 status·ended_at·end_rsn으로 표시한다 — 지우면 그 사람의
 *               과거 메시지가 가리킬 참여 기록이 사라진다.
 *
 *               ver은 낙관적 잠금 전용이다(이슈 #137). OWNER의 강퇴(remove)와 대상의 자진 탈퇴
 *               (leaveSelf)가 같은 행을 동시에 읽고 각자 end()로 덮어쓸 수 있는데, 이 컬럼이 없으면
 *               DB CHECK는 위반되지 않고 양쪽 다 성공해 최종 status·end_rsn이 나중에 커밋한 쪽으로
 *               비결정적으로 남는다 — 두 번째 save()가 ObjectOptimisticLockingFailureException으로
 *               실패하게 해 그 경합을 드러낸다.
 */
@Entity
@Table(name = "thr_mbr")
public class ThrMbr {

	@Id
	private UUID id;

	@Column(name = "thr_id", nullable = false)
	private UUID thrId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ThrMbrRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ThrMbrStatus status;

	@Column(name = "created_by_user_id", nullable = false)
	private UUID createdByUserId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "end_rsn")
	private String endRsn;

	@Version
	@Column(name = "ver", nullable = false)
	private long ver;

	protected ThrMbr() {
	}

	/** createdByUserId는 이 참가를 만든 사람이다 — 스스로 방을 만들면 자기 자신이 된다. */
	public ThrMbr(UUID thrId, UUID userId, ThrMbrRole role, UUID createdByUserId) {
		this.id = UUID.randomUUID();
		this.thrId = thrId;
		this.userId = userId;
		this.role = role;
		this.status = ThrMbrStatus.ACTIVE;
		this.createdByUserId = createdByUserId;
		this.createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getThrId() {
		return thrId;
	}

	public UUID getUserId() {
		return userId;
	}

	public ThrMbrRole getRole() {
		return role;
	}

	public ThrMbrStatus getStatus() {
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

	public long getVer() {
		return ver;
	}

	/** ended_at·end_rsn을 함께 채우는 것은 "언제 끝났는지는 있는데 왜인지는 없는" 행을 CHECK가 막기 때문이다. */
	public void end(ThrMbrStatus endStatus, String reason) {
		this.status = endStatus;
		this.endedAt = Instant.now();
		this.endRsn = reason;
	}

}
