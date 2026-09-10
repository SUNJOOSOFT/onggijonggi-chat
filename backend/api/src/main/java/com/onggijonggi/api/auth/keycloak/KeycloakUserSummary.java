package com.onggijonggi.api.auth.keycloak;

/**
 * Class Name : KeycloakUserSummary.java
 * Description : Keycloak 계정 검색 결과 한 건(이슈 #172).
 *
 *               subject는 화면이 그대로 초대 API에 넘기는 값이고, 사람에게는 displayName만
 *               보인다 — 초대창이 UUID를 손으로 받던 것을 없애려는 것이 이 검색의 목적이다.
 *
 *               표시 이름 규약은 KeycloakAdminClient.displayName()과 같다(username =
 *               OIDC preferred_username). app_user에 이름 컬럼을 두지 않기로 해서, 화면에
 *               보일 이름의 출처는 Keycloak 하나뿐이다.
 * @param subject 계정의 Keycloak subject(JWT sub 클레임과 같은 값)
 * @param displayName 화면에 보일 이름
 */
public record KeycloakUserSummary(String subject, String displayName) {
}
