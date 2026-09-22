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
	MEMBER
}
