package com.onggijonggi.api.auth;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
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
						.map(userId -> new CurrentActor(userId, token.getSubject(), JwtDisplayNames.of(token))));
	}

}
