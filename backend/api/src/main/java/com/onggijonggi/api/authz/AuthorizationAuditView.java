package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.AuthorizationActorKind;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditTargetKind;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Class Name : AuthorizationAuditView.java
 * Description : 감사 조회 응답의 한 행(#259). authz_adt 원본을 그대로 옮긴다. jsonb 네 필드는 문자열로 이중 직렬화하지 않고
 *               JSON 값으로 내보낸다 — jsonb는 키 순서를 보존하지 않으므로 받는 쪽도 순서에 기대지 않는다.
 *               선택 필드의 null은 그대로 둔다.
 */
public record AuthorizationAuditView(UUID id, UUID tenantId, AuthorizationActorKind actorKind, UUID actorUserId,
		JsonNode actorRoleJson, AuthorizationAuditEventKind eventKind, AuthorizationAuditTargetKind targetKind,
		JsonNode targetRef, UUID workspaceNodeId, JsonNode beforeJson, JsonNode afterJson, String requestId, String traceId,
		String deploymentId, String configurationFingerprint, Instant createdAt) {
}
