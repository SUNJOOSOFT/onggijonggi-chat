package com.onggijonggi.api.auth.keycloak;

import java.util.Optional;

/**
 * Class Name : KeycloakTenantUser.java
 * Description : 절체 사전 검증이 읽는 Keycloak 활성 사용자와 그 단일 tenant 속성이다. 속성이 없거나 복수값이면 tenant가 비어 있다.
 */
public record KeycloakTenantUser(String subject, Optional<String> tenant) {
}
