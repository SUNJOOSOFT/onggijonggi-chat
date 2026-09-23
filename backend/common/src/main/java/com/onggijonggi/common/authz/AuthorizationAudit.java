package com.onggijonggi.common.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Class Name : AuthorizationAudit.java
 * Description : authz_adt에 남는 append-only 권한 변경 감사 한 행이다. 대상은 FK가 아니라 trg_kind·trg_ref
 *               snapshot으로 보관한다(대상이 사라져도 기록은 남아야 한다). jsonb 컬럼 네 개는 문자열(JSON 본문)로
 *               다루되 JSON 타입으로 바인딩한다 — 그러지 않으면 PostgreSQL이 varchar를 jsonb에 넣지 못한다.
 *               공개 생성자는 bootstrap·reconcile이 남기는 SYSTEM 행위자 기록용이다. 사람의 팀·직급 배정 변경은
 *               member(...)로 남긴다 — 데모 배정 화면은 USER, CSV 임포트는 SYSTEM 행위자다.
 */
@Entity
@Table(name = "authz_adt")
public class AuthorizationAudit {

	@Id
	private UUID id;

	@Column(name = "tnn_id", nullable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "act_kind", nullable = false)
	private AuthorizationActorKind actorKind;

	@Column(name = "act_user_id")
	private UUID actorUserId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "act_role_json", nullable = false)
	private String actorRoleJson;

	@Enumerated(EnumType.STRING)
	@Column(name = "evt_kind", nullable = false)
	private AuthorizationAuditEventKind eventKind;

	@Enumerated(EnumType.STRING)
	@Column(name = "trg_kind", nullable = false)
	private AuthorizationAuditTargetKind targetKind;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "trg_ref", nullable = false)
	private String targetRef;

	@Column(name = "wrk_node_id")
	private UUID workspaceNodeId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "bfr_json")
	private String beforeJson;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "aft_json")
	private String afterJson;

	@Column(name = "req_id")
	private String requestId;

	@Column(name = "trc_id")
	private String traceId;

	@Column(name = "dpl_id")
	private String deploymentId;

	@Column(name = "cnf_fgpt")
	private String configurationFingerprint;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AuthorizationAudit() {
	}

	/**
	 * SYSTEM 행위자(bootstrap·reconcile) 기록. 한 실행의 행은 같은 requestId로 묶는다.
	 *
	 * @param workspaceNodeId 조회용 Workspace. WORKSPACE·POLICY 행에서만 채우고 TENANT·ORG_UNIT·drift 행은 null
	 * @param deploymentId    reconcile이 켜진 실행에서만 채운다(꺼진 실행이 남기면 같은 배포 ID의 이후 reconcile이 막힌다)
	 */
	public AuthorizationAudit(UUID tenantId, AuthorizationAuditEventKind eventKind,
			AuthorizationAuditTargetKind targetKind, String targetRefJson, UUID workspaceNodeId,
			String beforeJson, String afterJson, String requestId, String deploymentId,
			String configurationFingerprint) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.actorKind = AuthorizationActorKind.SYSTEM;
		this.actorRoleJson = "[]";
		this.eventKind = eventKind;
		this.targetKind = targetKind;
		this.targetRef = targetRefJson;
		this.workspaceNodeId = workspaceNodeId;
		this.beforeJson = beforeJson;
		this.afterJson = afterJson;
		this.requestId = requestId;
		this.deploymentId = deploymentId;
		this.configurationFingerprint = configurationFingerprint;
		this.createdAt = Instant.now();
	}

	/**
	 * 사람의 팀·직급 배정(org_unit_mbr) 변경 기록. actorUserId가 null이면 SYSTEM(CSV 임포트), 아니면 그 app_user가 행위자다.
	 * 배정은 workspace 하나에 묶이지 않아 wrk_node_id는 비운다.
	 */
	public static AuthorizationAudit member(UUID tenantId, UUID actorUserId, AuthorizationAuditEventKind eventKind,
			String targetRefJson, String beforeJson, String afterJson, String requestId) {
		AuthorizationAudit audit = new AuthorizationAudit(tenantId, eventKind, AuthorizationAuditTargetKind.MEMBER, targetRefJson,
				null, beforeJson, afterJson, requestId, null, null);
		if (actorUserId != null) {
			audit.actorKind = AuthorizationActorKind.USER;
			audit.actorUserId = actorUserId;
		}
		return audit;
	}

	public UUID getId() { return id; }
	public UUID getTenantId() { return tenantId; }
	public AuthorizationActorKind getActorKind() { return actorKind; }
	public UUID getActorUserId() { return actorUserId; }
	public String getActorRoleJson() { return actorRoleJson; }
	public AuthorizationAuditEventKind getEventKind() { return eventKind; }
	public AuthorizationAuditTargetKind getTargetKind() { return targetKind; }
	public String getTargetRef() { return targetRef; }
	public UUID getWorkspaceNodeId() { return workspaceNodeId; }
	public String getBeforeJson() { return beforeJson; }
	public String getAfterJson() { return afterJson; }
	public String getRequestId() { return requestId; }
	public String getDeploymentId() { return deploymentId; }
	public String getConfigurationFingerprint() { return configurationFingerprint; }
	public Instant getCreatedAt() { return createdAt; }
}
