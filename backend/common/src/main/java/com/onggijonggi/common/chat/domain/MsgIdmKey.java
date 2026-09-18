package com.onggijonggi.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : MsgIdmKey.java
 * Description : DIRECT 발화 저장 요청의 idempotency key(V20260918020045611__msg_idempotency_key.sql
 *               `msg_idm_key`, 이슈 #233). 같은 사용자가 같은 key로 다시 요청하면, 서버는 새
 *               HUMAN·PENDING AGENT를 만드는 대신 이 행이 가리키는 값을 그대로 돌려준다.
 *
 *               COLLAB 방 생성의 {@link ThrIdmKey}(#149)와 같은 성격이지만 재사용할 수 없다 —
 *               그쪽은 replay 결과로 thr_id 하나만 돌려주면 되지만, 이쪽은 사람 발화·예약된
 *               AGENT 응답 두 메시지의 id·seq를 통째로 복원해야 한다.
 *
 *               content를 함께 저장하는 것은 {@link ThrIdmKey#getTitle()}과 같은 이유다 —
 *               content 자체가 key는 아니지만, "같은 key인데 다른 content"가 오는 경우(재사용
 *               실수)를 구분하기 위해서다.
 *
 *               (user_id, idm_key) 유니크 제약은 마이그레이션(ux_msg_idm_key_user_key)이
 *               정본이고, 여기 uniqueConstraints는 Flyway를 끄고 Hibernate가 즉석 스키마를
 *               만드는 테스트 환경에서도 같은 제약을 재현하기 위함이다.
 *
 *               컬럼명은 idm_key다 — key 단독은 H2(테스트 DB)의 예약어라 즉석 스키마 생성이
 *               깨진다. 필드명은 key로 둔다(자바에는 문제되지 않는다).
 */
@Entity
@Table(name = "msg_idm_key",
		uniqueConstraints = @UniqueConstraint(name = "ux_msg_idm_key_user_key", columnNames = {"user_id", "idm_key"}))
public class MsgIdmKey {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "idm_key", nullable = false)
	private String key;

	@Column(nullable = false)
	private String content;

	@Column(name = "thr_id", nullable = false)
	private UUID thrId;

	@Column(name = "hmn_msg_id", nullable = false)
	private UUID humanMsgId;

	@Column(name = "hmn_seq", nullable = false)
	private long humanSeq;

	@Column(name = "agn_msg_id", nullable = false)
	private UUID agentMsgId;

	@Column(name = "agn_seq", nullable = false)
	private long agentSeq;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MsgIdmKey() {
	}

	public MsgIdmKey(UUID userId, String key, String content, UUID thrId, UUID humanMsgId, long humanSeq,
			UUID agentMsgId, long agentSeq) {
		this.id = UUID.randomUUID();
		this.userId = userId;
		this.key = key;
		this.content = content;
		this.thrId = thrId;
		this.humanMsgId = humanMsgId;
		this.humanSeq = humanSeq;
		this.agentMsgId = agentMsgId;
		this.agentSeq = agentSeq;
		this.createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getKey() {
		return key;
	}

	public String getContent() {
		return content;
	}

	public UUID getThrId() {
		return thrId;
	}

	public UUID getHumanMsgId() {
		return humanMsgId;
	}

	public long getHumanSeq() {
		return humanSeq;
	}

	public UUID getAgentMsgId() {
		return agentMsgId;
	}

	public long getAgentSeq() {
		return agentSeq;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
