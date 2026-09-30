package com.onggijonggi.api.authz;

import java.util.List;

/**
 * Class Name : AuthorizationAuditPage.java
 * Description : 감사 조회 한 페이지(#259). 마지막 페이지면 nextCursor가 null이다. 새로 쌓인 행은 커서 없이 첫 페이지부터 다시 본다.
 */
public record AuthorizationAuditPage(List<AuthorizationAuditView> items, String nextCursor) {
}
