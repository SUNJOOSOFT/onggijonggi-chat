package com.onggijonggi.api.auth.keycloak;

/**
 * Class Name : KeycloakPerson.java
 * Description : 권한 관리 화면에 보일 Keycloak 사람 계정 한 명.
 *
 * @param subject Keycloak subject(JWT sub 클레임과 같은 값). 팀·직급 배정이 이 값을 가리킨다
 * @param username 로그인 ID
 * @param name 화면에 보일 이름(성+이름, 없으면 username)
 * @param email 이메일. 없을 수 있다
 * @param enabled Keycloak에서 로그인할 수 있는 계정인지
 */
public record KeycloakPerson(String subject, String username, String name, String email, boolean enabled) {
}
