package com.onggijonggi.api.authz;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : CutoverValidationResult.java
 * Description : 단일 Tenant 절체의 읽기 전용 사전 검증 결과. 실패 항목이 하나라도 있으면 이관하지 않는다.
 */
public record CutoverValidationResult(UUID tenantId, List<Failure> failures) {

	public boolean ready() {
		return failures.isEmpty();
	}

	public record Failure(String code, UUID threadId, String subject) {
	}
}
