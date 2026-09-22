package com.onggijonggi.common.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : OrgUnitMember.java
 * Description : 사람 한 명의 팀(org-unit)·직급 배정. 사람은 Keycloak subject로 가리키고 FK가 없다 — 로그인 전
 *               사람도 배정해야 해서다. Casbin ABAC 판정 때 이 행의 팀·서열을 속성으로 넘긴다.
 */
@Entity
@Table(name = "org_unit_mbr")
public class OrgUnitMember {

	@Id
	private UUID id;
	@Column(name = "tnn_id", nullable = false)
	private UUID tenantId;
	@Column(name = "org_unit_id", nullable = false)
	private UUID orgUnitId;
	@Column(name = "subj", nullable = false, updatable = false)
	private String subject;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Rank rank;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected OrgUnitMember() {
	}

	public OrgUnitMember(UUID tenantId, UUID orgUnitId, String subject, Rank rank) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.orgUnitId = orgUnitId;
		this.subject = subject;
		this.rank = rank;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() { return id; }
	public UUID getTenantId() { return tenantId; }
	public UUID getOrgUnitId() { return orgUnitId; }
	public String getSubject() { return subject; }
	public Rank getRank() { return rank; }
}
