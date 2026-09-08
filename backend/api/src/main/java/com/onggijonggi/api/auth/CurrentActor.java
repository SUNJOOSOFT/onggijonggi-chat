package com.onggijonggi.api.auth;

import java.util.UUID;

/**
 * Class Name : CurrentActor.java
 * Description : 인증된 요청자 — 외부 IdP subject·표시 이름과 내부 app_user.id를 함께 들고 다닌다.
 *               Controller와 서비스가 JwtAuthenticationToken을 직접 알지 않게 하는 공급자 중립
 *               표현이다. displayName은 DB 컬럼이 아니라 매 요청의 JWT claim에서 읽은 값이라 화면
 *               표시명이 Keycloak에서 바뀌면 다음 요청부터 곧바로 반영된다(이슈 #128).
 *               역할(roles)은 담지 않는다 — 02·EDGE(SecurityConfig)가 필터체인에서 hasRole("USER")를
 *               이미 강제하므로 읽는 쪽이 없다. 필요해지면 그때 필드를 더한다.
 */
public record CurrentActor(UUID userId, String subject, String displayName) {
}
