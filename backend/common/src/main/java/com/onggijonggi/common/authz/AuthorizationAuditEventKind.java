package com.onggijonggi.common.authz;

/**
 * Class Name : AuthorizationAuditEventKind.java
 * Description : Authorization change events permitted by authz_adt.
 */
public enum AuthorizationAuditEventKind {
	TENANT_CREATED,
	TENANT_RENAMED,
	TENANT_DEACTIVATED,
	TENANT_REACTIVATED,
	ORG_UNIT_CREATED,
	ORG_UNIT_RENAMED,
	ORG_UNIT_DEACTIVATED,
	ORG_UNIT_REACTIVATED,
	NODE_CREATED,
	NODE_RENAMED,
	NODE_REPARENTED,
	NODE_DEACTIVATED,
	NODE_REACTIVATED,
	POLICY_ADDED,
	POLICY_REMOVED,
	POLICY_REPLACED,
	THREAD_MOVED,
	OWNER_TRANSFERRED,
	TENANT_DRIFT_DETECTED,
	// 사람의 팀·직급 배정 변경. 배정이 DB에서 Casbin(속성 파일)으로 옮겨가 더는 만들지 않는다. 이미 쌓인 기록을 읽으려고 남긴다.
	MEMBER_ASSIGNED,
	MEMBER_CHANGED,
	MEMBER_UNASSIGNED
}
