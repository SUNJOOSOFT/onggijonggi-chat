package com.onggijonggi.common.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : AppUser.java
 * Description : Keycloak 인증 사용자에 대응하는 로컬 참조 레코드(V1__app_user_and_doc.sql `app_user`,
 *               V9__app_user_status.sql `status`/`inactive_at`). 역할은 저장하지 않는다 — Keycloak이
 *               부여하고 요청 시점에 태그로 변환한다(04·DATA). 소유 대화 정리·참여 회수 순서는
 *               AccountDeactivationService(#132) 몫이고, 여기 상태 전이 메서드는 계정 자체의 필드만
 *               바꾼다.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

	/** 내부 식별자. JIT 프로비저닝 시 서버가 새로 발급(UUID.randomUUID()). */
	@Id
	private UUID id;

	/** Keycloak JWT sub 클레임. unique — 같은 사용자가 두 행으로 중복 생성되지 않게 막는다.
	 * Tombstone 뒤에도 값을 유지해, 같은 외부 주체가 다시 로그인해도 새 사용자로 조용히 만들어지지 않는다. */
	@Column(name = "keycloak_subj", nullable = false, unique = true)
	private String keycloakSubj;

	/** 인가 판정에 쓰지 않는다 — 계정이 살아 있는지는 Keycloak이 정본이고, 이 값은 참조 무결성과
	 * Tombstone 표시용이다. */
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private AppUserStatus status;

	/** INACTIVE일 때만 값이 있다(DB CHECK로도 강제). Tombstone 전환 시각. */
	@Column(name = "inactive_at")
	private Instant inactiveAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AppUser() {
	}

	public AppUser(String keycloakSubj) {
		this.id = UUID.randomUUID();
		this.keycloakSubj = keycloakSubj;
		this.status = AppUserStatus.ACTIVE;
		this.createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getKeycloakSubj() {
		return keycloakSubj;
	}

	public AppUserStatus getStatus() {
		return status;
	}

	public Instant getInactiveAt() {
		return inactiveAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	/** ACTIVE에서만 호출한다(상태 검증은 서비스 몫) — app_user_inactive_at_matches_status CHECK를 만족시킨다. */
	public void deactivate() {
		this.status = AppUserStatus.INACTIVE;
		this.inactiveAt = Instant.now();
	}

	/** INACTIVE에서만 호출한다(상태 검증은 서비스 몫). 참여(thr_mbr)는 복구하지 않는다 — 재초대로 다시 들어온다. */
	public void reactivate() {
		this.status = AppUserStatus.ACTIVE;
		this.inactiveAt = null;
	}

}
