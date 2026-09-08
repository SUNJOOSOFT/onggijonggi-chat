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

	private Thr(ThrKind kind, UUID createdUserId, String title) {
		this.id = UUID.randomUUID();
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
		return new Thr(ThrKind.COLLAB, createdUserId, title);
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

}
