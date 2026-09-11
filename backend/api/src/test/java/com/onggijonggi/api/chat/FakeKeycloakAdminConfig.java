package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Class Name : FakeKeycloakAdminConfig.java
 * Description : KeycloakAdminClient를 실 Keycloak 없이 동작하는 빈으로 교체한다(이슈 #128·#127).
 *               표시 이름은 subject를 그대로 돌려주고, 실존 검증은 "ghost-" 접두어만 없는 계정으로 본다. 실제 구현은 client_credentials 토큰을 받으러 네트워크를
 *               타는데, RestTestClient.bindToServer()로 띄우는 통합 테스트에는 실 Keycloak이 없다.
 */
@TestConfiguration
class FakeKeycloakAdminConfig {

	@Bean
	@Primary
	KeycloakAdminClient fakeKeycloakAdminClient() {
		return new KeycloakAdminClient(WebClient.builder(), "http://unused", "app-realm", "ogjg-client",
				"unused", Duration.ZERO) {
			@Override
			public Mono<Optional<String>> displayName(String subject) {
				return Mono.just(Optional.of(subject));
			}

			/**
			 * 실존 검증(이슈 #127). "ghost-"로 시작하는 subject만 Keycloak에 없는 것으로 본다 —
			 * 테스트가 "계정은 있는데 아직 로그인 전"과 "계정 자체가 없음"을 구분해야 하기 때문이다.
			 */
			@Override
			public Mono<Boolean> exists(String subject) {
				return Mono.just(!subject.startsWith("ghost-"));
			}
		};
	}

}
