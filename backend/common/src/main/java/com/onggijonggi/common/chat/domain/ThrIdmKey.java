package com.onggijonggi.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : ThrIdmKey.java
 * Description : 협업방 생성 요청의 idempotency key(V13__thr_idempotency_key.sql `thr_idm_key`,
 *               이슈 #149). 같은 사용자가 같은 key로 다시 요청하면, 서버는 새 thr을 만드는 대신
 *               이 행이 가리키는 thr을 그대로 돌려준다.
 *
 *               title을 함께 저장하는 것은 title 자체가 key는 아니지만, "같은 key인데 다른
 *               title"이 오는 경우(재사용 실수)를 구분하기 위해서다.
 *
 *               (user_id, idm_key) 유니크 제약은 마이그레이션(ux_thr_idm_key_user_key)이 정본이고,
 *               여기 uniqueConstraints는 Flyway를 끄고 Hibernate가 즉석 스키마를 만드는 테스트
 *               환경(application-test.properties)에서도 같은 제약을 재현하기 위함이다.
 *
 *               컬럼명은 idm_key다 — key 단독은 H2(테스트 DB)의 예약어라 즉석 스키마 생성이
 *               깨진다. 필드명은 key로 둔다(자바에는 문제되지 않는다).
 */
@Entity
@Table(name = "thr_idm_key",
		uniqueConstraints = @UniqueConstraint(name = "ux_thr_idm_key_user_key", columnNames = {"user_id", "idm_key"}))
public class ThrIdmKey {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "idm_key", nullable = false)
	private String key;

	@Column(nullable = false)
	private String title;

	@Column(name = "thr_id", nullable = false)
	private UUID thrId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ThrIdmKey() {
	}

	public ThrIdmKey(UUID userId, String key, String title, UUID thrId) {
		this.id = UUID.randomUUID();
		this.userId = userId;
		this.key = key;
		this.title = title;
		this.thrId = thrId;
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

	public String getTitle() {
		return title;
	}

	public UUID getThrId() {
		return thrId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
