package com.onggijonggi.api.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Class Name : JwtDisplayNames.java
 * Description : JWT claim에서 화면 표시 이름을 읽는다(이슈 #128). 표시명은 DB 컬럼이 아니라 매
 *               요청의 토큰에서 새로 읽으므로, Keycloak에서 이름이 바뀌면 다음 요청부터 곧바로
 *               반영된다.
 *
 *               HTTP 경로({@link CurrentActorProvider})와 WebSocket 핸드셰이크(CollabWebSocketHandler)가
 *               같은 규칙을 써야 해서 여기로 모았다 — 두 경로가 다른 이름을 내면 같은 사람이
 *               화면에서 둘로 보인다.
 */
public final class JwtDisplayNames {

	private JwtDisplayNames() {
	}

	/**
	 * preferred_username이 없는 토큰(예: 매퍼 설정을 안 한 다른 클라이언트)에 대비해 name,
	 * 그마저 없으면 subject로 물러난다 — 화면에 빈 이름이 뜨는 것보다 subject라도 보이는 쪽이 낫다.
	 */
	public static String of(Jwt token) {
		String preferredUsername = token.getClaimAsString("preferred_username");
		if (preferredUsername != null) {
			return preferredUsername;
		}
		String name = token.getClaimAsString("name");
		return name != null ? name : token.getSubject();
	}

}
