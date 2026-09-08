package com.onggijonggi.api.auth;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Class Name : CurrentActorProvider.java
 * Description : SecurityContext의 JWT를 CurrentActor로 해석하는 유일한 자리. 이전에는 ChatController와
 *               PersistingChatStreamService가 같은 체인(컨텍스트 조회 → JwtAuthenticationToken 캐스팅 →
 *               sub 추출 → app_user 해석)을 각자 반복했다. 인터페이스로 두지 않는다 — 구현이 하나뿐이고
 *               IdP 중립성은 IdentityProviderService가 이미 담당한다.
 */
@Component
public class CurrentActorProvider {

	private final UserIdentityService userIdentityService;

	public CurrentActorProvider(UserIdentityService userIdentityService) {
		this.userIdentityService = userIdentityService;
	}

	/**
	* currentActor: 인증된 요청자를 해석한다. app_user 행이 없으면 그 자리에서 만든다(JIT 프로비저닝).
	*/
	public Mono<CurrentActor> currentActor() {
		return ReactiveSecurityContextHolder.getContext()
				.map(SecurityContext::getAuthentication)
				.cast(JwtAuthenticationToken.class)
				.map(JwtAuthenticationToken::getToken)
				.flatMap(token -> userIdentityService.resolveOrProvision(token.getSubject())
						.map(userId -> new CurrentActor(userId, token.getSubject(), displayName(token))));
	}

	/**
	* 표시명은 DB 컬럼이 아니라 요청마다 JWT에서 새로 읽는다(이슈 #128). preferred_username이 없는
	* 토큰(예: 매퍼 설정을 안 한 다른 클라이언트)에 대비해 name, 그마저 없으면 subject로 물러난다 —
	* 화면에 빈 이름이 뜨는 것보다 subject라도 보이는 쪽이 낫다.
	*/
	private String displayName(Jwt token) {
		String preferredUsername = token.getClaimAsString("preferred_username");
		if (preferredUsername != null) {
			return preferredUsername;
		}
		String name = token.getClaimAsString("name");
		return name != null ? name : token.getSubject();
	}

}
