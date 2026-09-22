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
 * Class Name : Thr.java
 * Description : Thread(V8__thread.sql `thr`). 참가자·사용자 참조는 ChatSess와 같은 이유로 연관관계
 *               매핑 없이 평문 UUID 컬럼으로 둔다.
 *
 *               next_seq는 Message 순서의 정본이고 채번은 UPDATE ... RETURNING으로 원자적으로
 *               올려 받아야 한다 — 그 서비스 로직은 #18 몫이라 여기엔 증가 메서드를 두지 않는다.
 *               setter로 올리면 읽고-쓰는 사이에 다른 트랜잭션이 끼어들 수 있다.
 */
@Entity
@Table(name = "thr")
public class Thr {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ThrKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ThrStatus status;

	/** DIRECT만 값을 갖는다. COLLAB의 소유는 OWNER 역할 참가자로 표현한다. */
	@Column(name = "drc_own_user_id")
	private UUID drcOwnUserId;

	/** 한 번 정해진 Tenant는 바뀌지 않는다. 절체 backfill은 SQL로 채우고 엔티티는 갱신하지 않는다. */
	@Column(name = "tnn_id", updatable = false)
	private UUID tenantId;

	@Column(name = "wrk_node_id")
	private UUID workspaceNodeId;

	@Column(name = "created_user_id", nullable = false)
	private UUID createdUserId;

	@Column(nullable = false)
	private String title;

	@Column(name = "next_seq", nullable = false)
	private long nextSeq;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "locked_at")
	private Instant lockedAt;

	@Column(name = "archived_at")
	private Instant archivedAt;

	protected Thr() {
	}

	private Thr(UUID id, ThrKind kind, UUID createdUserId, String title) {
		this.id = id;
		this.kind = kind;
		this.status = ThrStatus.ACTIVE;
		this.createdUserId = createdUserId;
		this.title = title;
		this.nextSeq = 0L;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	/**
	* 생성자를 노출하지 않고 종류별 팩토리만 두는 것은 drc_own_user_id 대응(kind = DIRECT일 때만
	* 값이 있다)을 호출부가 실수로 어기지 못하게 하기 위함이다.
	*/
	public static Thr collab(UUID createdUserId, String title) {
		return new Thr(UUID.randomUUID(), ThrKind.COLLAB, createdUserId, title);
	}

	/**
	* DIRECT는 기존 chat_sess의 ID와 클라이언트 session ID를 그대로 thr ID로 쓰므로, ID 생성 책임을
	* 호출자에게 둔다. owner는 DIRECT의 소유 컬럼과 최초 생성자에 같은 값으로 기록한다.
	*/
	public static Thr direct(UUID id, UUID ownerId, String title) {
		Thr thread = new Thr(id, ThrKind.DIRECT, ownerId, title);
		thread.drcOwnUserId = ownerId;
		return thread;
	}

	public UUID getId() {
		return id;
	}

	public ThrKind getKind() {
		return kind;
	}

	public ThrStatus getStatus() {
		return status;
	}

	public UUID getDrcOwnUserId() {
		return drcOwnUserId;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public UUID getWorkspaceNodeId() {
		return workspaceNodeId;
	}

	public UUID getCreatedUserId() {
		return createdUserId;
	}

	public String getTitle() {
		return title;
	}

	public long getNextSeq() {
		return nextSeq;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getLockedAt() {
		return lockedAt;
	}

	public Instant getArchivedAt() {
		return archivedAt;
	}

	/** ACTIVE에서만 호출한다(상태 검증은 서비스 몫) — locked_at만 채우고 archived_at은 그대로 둔다. */
	public void lock() {
		this.status = ThrStatus.LOCKED;
		this.lockedAt = Instant.now();
		this.updatedAt = Instant.now();
	}

	/**
	* ACTIVE·LOCKED 둘 다에서 호출할 수 있다(상태 검증은 서비스 몫). LOCKED를 거쳐 온 경우 locked_at은
	* 지우지 않는다 — 언제 잠겼었는지를 잃지 않기 위해서다(V8__thread.sql thr_archived_has_archived_at).
	*/
	public void archive() {
		this.status = ThrStatus.ARCHIVED;
		this.archivedAt = Instant.now();
		this.updatedAt = Instant.now();
	}

	/** DIRECT 제목 수정은 소유권을 검증한 서비스만 호출한다. 유효한 제목은 앞뒤 공백을 제거해 저장한다. */
	public void rename(String title) {
		this.title = title;
		this.updatedAt = Instant.now();
	}


	/**
	* seq를 size개 예약하고 블록의 첫 seq를 돌려준다(이슈 #190). 호출부가 비관적 잠금으로 이 행을
	* 잡은 트랜잭션 안에서만 불러야 한다 — UPDATE ... RETURNING을 쓰지 않는 이유는 H2가 그 문법을
	* 지원하지 않아 통합 테스트가 실제 채번을 한 번도 실행하지 못하기 때문이다.
	*/
	public long reserveSeqBlock(int size) {
		long first = this.nextSeq;
		this.nextSeq += size;
		return first;
	}

}
