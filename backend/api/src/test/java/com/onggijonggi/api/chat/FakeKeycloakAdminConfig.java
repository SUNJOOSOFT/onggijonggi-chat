package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Class Name : FakeKeycloakAdminConfig.java
 * Description : KeycloakAdminClient를 실 Keycloak 없이 subject를 그대로 표시 이름으로 돌려주는
 *               빈으로 교체한다(이슈 #128). 실제 구현은 client_credentials 토큰을 받으러 네트워크를
 *               타는데, RestTestClient.bindToServer()로 띄우는 통합 테스트에는 실 Keycloak이 없다.
 */
@TestConfiguration
class FakeKeycloakAdminConfig {

	@Bean
	@Primary
	KeycloakAdminClient fakeKeycloakAdminClient() {
		return new KeycloakAdminClient(WebClient.builder(), "http://unused", "app-realm", "ogjg-client", "unused") {
			@Override
			public Mono<Optional<String>> displayName(String subject) {
				return Mono.just(Optional.of(subject));
			}
		};
	}

}
