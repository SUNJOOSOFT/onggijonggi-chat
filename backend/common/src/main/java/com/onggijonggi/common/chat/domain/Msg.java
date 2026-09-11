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
 * Class Name : Msg.java
 * Description : Message(V11__message.sql `msg`). 생성자를 노출하지 않고 종류별 팩토리만 두는 것은
 *               ath_kind별 컬럼 대응(msg_human_has_participant·msg_pending_only_for_agent·
 *               msg_completed_at_matches_status CHECK)을 호출부가 실수로 어기지 못하게 하기
 *               위함이다.
 *
 *               완료된 행은 DB 트리거가 UPDATE 자체를 막으므로, complete()·fail()은 PENDING인
 *               동안 정확히 한 번만 호출돼야 한다 — 호출부(MsgPersistenceService)가 보장한다.
 */
@Entity
@Table(name = "msg")
public class Msg {

	@Id
	private UUID id;

	@Column(name = "thr_id", nullable = false)
	private UUID thrId;

	@Column(nullable = false)
	private long seq;

	@Column(name = "rpl_msg_id")
	private UUID rplMsgId;

	@Enumerated(EnumType.STRING)
	@Column(name = "ath_kind", nullable = false)
	private AthKind athKind;

	@Column(name = "thr_mbr_id")
	private UUID thrMbrId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MsgStatus status;

	@Column(nullable = false)
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected Msg() {
	}

	/**
	* id를 밖에서 받는다 — 방송 프레임이 저장보다 먼저 나가야 해서 호출부가 id를 미리 알아야
	* 하는 경로가 있다(이슈 #190). id는 DB가 아니라 앱이 만드는 값이라 가능한 일이다.
	*/
	private Msg(UUID id, UUID thrId, long seq, AthKind athKind, UUID thrMbrId, MsgStatus status,
			String content) {
		this.id = id;
		this.thrId = thrId;
		this.seq = seq;
		this.athKind = athKind;
		this.thrMbrId = thrMbrId;
		this.status = status;
		this.content = content;
		this.createdAt = Instant.now();
		if (status != MsgStatus.PENDING) {
			this.completedAt = this.createdAt;
		}
	}

	/** HUMAN 메시지는 쓰이는 순간 이미 완료된 메시지다 — thrMbrId는 작성 시점의 참여 기록을 가리킨다. */
	public static Msg human(UUID id, UUID thrId, long seq, UUID thrMbrId, String content) {
		return new Msg(id, thrId, seq, AthKind.HUMAN, thrMbrId, MsgStatus.COMPLETE, content);
	}

	/** AGENT만 PENDING으로 시작할 수 있다 — 스트리밍이 끝나면 complete()/fail()로 전이한다. */
	/** 이 id가 곧 턴 식별자다 — AI 턴 하나는 PENDING 행 하나와 1:1이다(D5, 이슈 #190). */
	public static Msg pendingAgent(UUID id, UUID thrId, long seq) {
		return new Msg(id, thrId, seq, AthKind.AGENT, null, MsgStatus.PENDING, "");
	}

	/** SYSTEM 메시지는 HUMAN과 같이 쓰이는 순간 이미 완료된 메시지다 — 작성자가 없어 thrMbrId는 두지 않는다. */
	public static Msg system(UUID thrId, long seq, String content) {
		return new Msg(UUID.randomUUID(), thrId, seq, AthKind.SYSTEM, null, MsgStatus.COMPLETE, content);
	}

	public void complete(String content) {
		this.content = content;
		this.status = MsgStatus.COMPLETE;
		this.completedAt = Instant.now();
	}

	public void fail(MsgStatus terminalStatus) {
		this.status = terminalStatus;
		this.completedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getThrId() {
		return thrId;
	}

	public long getSeq() {
		return seq;
	}

	public UUID getRplMsgId() {
		return rplMsgId;
	}

	public AthKind getAthKind() {
		return athKind;
	}

	public UUID getThrMbrId() {
		return thrMbrId;
	}

	public MsgStatus getStatus() {
		return status;
	}

	public String getContent() {
		return content;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

}
