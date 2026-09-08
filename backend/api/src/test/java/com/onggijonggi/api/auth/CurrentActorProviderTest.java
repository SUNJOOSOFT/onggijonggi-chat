package com.onggijonggi.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Class Name : CurrentActorProviderTest.java
 * Description : 표시 이름을 JWT claim에서 뽑는 우선순위(preferred_username → name → subject)만
 *               검증한다(이슈 #128). userId 해석은 UserIdentityServiceTest가 이미 다루므로 여기선
 *               Mockito로 고정값을 돌려준다.
 */
@ExtendWith(MockitoExtension.class)
class CurrentActorProviderTest {

	@Mock
	private UserIdentityService userIdentityService;

	private CurrentActor resolve(Jwt jwt) {
		UUID userId = UUID.randomUUID();
		when(userIdentityService.resolveOrProvision(any())).thenReturn(Mono.just(userId));
		CurrentActorProvider provider = new CurrentActorProvider(userIdentityService);

		CurrentActor[] captured = new CurrentActor[1];
		StepVerifier.create(provider.currentActor()
						.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication(jwt))))
				.assertNext(actor -> captured[0] = actor)
				.verifyComplete();
		return captured[0];
	}

	private JwtAuthenticationToken authentication(Jwt jwt) {
		return new JwtAuthenticationToken(jwt);
	}

	private Jwt jwtWithClaims(Map<String, Object> claims) {
		return Jwt.withTokenValue("test-token")
				.header("alg", "none")
				.subject("sub-1")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300))
				.claims(target -> target.putAll(claims))
				.build();
	}

	@Test
	void prefersPreferredUsernameClaim() {
		Jwt jwt = jwtWithClaims(Map.of("preferred_username", "sujin", "name", "김수진"));

		CurrentActor actor = resolve(jwt);

		assertThatDisplayNameIs(actor, "sujin");
	}

	/** Keycloak이 아닌 IdP 등 preferred_username 매퍼가 없는 토큰을 대비한 대체 경로. */
	@Test
	void fallsBackToNameWhenPreferredUsernameMissing() {
		Jwt jwt = jwtWithClaims(Map.of("name", "김수진"));

		CurrentActor actor = resolve(jwt);

		assertThatDisplayNameIs(actor, "김수진");
	}

	/** 표시 이름 claim이 하나도 없어도 화면에 빈 이름 대신 subject라도 보여준다. */
	@Test
	void fallsBackToSubjectWhenNoDisplayNameClaimExists() {
		Jwt jwt = jwtWithClaims(Map.of());

		CurrentActor actor = resolve(jwt);

		assertThatDisplayNameIs(actor, "sub-1");
	}

	private void assertThatDisplayNameIs(CurrentActor actor, String expected) {
		assertThat(actor.displayName()).isEqualTo(expected);
	}

}
