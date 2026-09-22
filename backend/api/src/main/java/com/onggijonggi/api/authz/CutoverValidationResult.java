package com.onggijonggi.api.authz;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : CutoverValidationResult.java
 * Description : 절체(기존 Thread를 Tenant·Workspace에 귀속시키는 일회성 이관) 전에 확인하는 사전 검증 결과다.
 *               <ul>
 *               <li>invalidTenantSubjects — Keycloak에서 활성인데 tenant 속성이 없거나 단일 slug가 아닌 계정.
 *                   기본값을 추측하지 않고 불일치로 보고한다.</li>
 *               <li>ownerMismatches — DIRECT owner와 COLLAB ACTIVE OWNER의 현재 Tenant가 staging의 Thread Tenant와 다르다.
 *                   OWNER를 자동으로 회수하면 방에 주인이 없어지므로 **절체를 중단**하는 항목이다.</li>
 *               <li>participantRevocationCandidates — OWNER가 아닌 ACTIVE 참여자의 불일치. 중단하지 않고, 배포 2 backfill이
 *                   `TENANT_MISMATCH`로 회수할 예정인 목록이다.</li>
 *               <li>unmappedThreadIds — staging(`stg_thr_tnn`)에 매핑이 없는 Thread. 배포 2 migration은 이런 Thread가
 *                   남아 있으면 실패해 기동이 멈춘다.</li>
 *               </ul>
 *               Keycloak에서 비활성이거나 없는 계정은 대조·기록·회수 대상이 아니라 어느 목록에도 나오지 않는다.
 */
public record CutoverValidationResult(List<String> invalidTenantSubjects,
		List<OwnerTenantMismatch> ownerMismatches,
		List<ParticipantTenantMismatch> participantRevocationCandidates,
		List<UUID> unmappedThreadIds) {

	/** currentTenantKey가 null이면 그 계정의 Keycloak tenant 속성이 없거나 slug가 아니다. */
	public record OwnerTenantMismatch(UUID threadId, UUID ownerUserId, String stagedTenantKey,
			String currentTenantKey) {
	}

	public record ParticipantTenantMismatch(UUID threadId, UUID userId, String stagedTenantKey,
			String currentTenantKey) {
	}
}
