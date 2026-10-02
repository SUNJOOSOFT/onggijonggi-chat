package com.onggijonggi.common.authz;

/**
 * Class Name : AuthorizationAuditTargetKind.java
 * Description : Target categories retained by an append-only authorization audit row.
 */
public enum AuthorizationAuditTargetKind {
	TENANT,
	ORG_UNIT,
	WORKSPACE,
	POLICY,
	THREAD,
	// 더는 만들지 않는다(MEMBER_* 이벤트와 같다). 이미 쌓인 기록을 읽으려고 남긴다.
	MEMBER
}
