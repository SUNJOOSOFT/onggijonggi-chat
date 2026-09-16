package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.auth.IdentityProviderService;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

/**
 * Class Name : WsSecurityContextSpikeTest.java
 * Description : 이슈 #7 스파이크 — WS 업그레이드 이후 메시지 루프에서 인증된 사용자를 읽을 수 있는지
 *               실측한다. (a) ReactiveSecurityContextHolder를 통한 암묵 전파와 (b)
 *               HandshakeInfo.getPrincipal()을 통한 핸드셰이크 principal 두 경로를 한 번의 왕복으로
 *               같이 재서, 둘 다 되는지/일부만 되는지/둘 다 안 되는지를 실제 값으로 단언한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class,
		WsSecurityContextSpikeTest.WsSpikeConfig.class})
class WsSecurityContextSpikeTest {

	/**
	 * 프로덕션 SecurityConfig(.anyExchange().denyAll())를 고치지 않고 /ws/** 전용 체인을 앞에
	 * 끼운다 — 이 스파이크가 남기는 배선 사실 자체가 이슈 #3의 사전 정보다.
	 */
	@TestConfiguration
	static class WsSpikeConfig {

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		SecurityWebFilterChain wsSpikeChain(ServerHttpSecurity http, IdentityProviderService idp) {
			ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
			converter.setJwtGrantedAuthoritiesConverter(idp.grantedAuthoritiesConverter());
			return http
					.securityMatcher(new PathPatternParserServerWebExchangeMatcher("/ws/**"))
					.csrf(ServerHttpSecurity.CsrfSpec::disable)
					.authorizeExchange(exchange -> exchange.anyExchange().hasRole("USER"))
					.oauth2ResourceServer(oauth2 -> oauth2
							.jwt(jwt -> jwt.jwtDecoder(idp.jwtDecoder()).jwtAuthenticationConverter(converter)))
					.build();
		}

		@Bean
		WebSocketHandler spikeHandler() {
			return session -> {
				// 핸드셰이크 principal은 연결당 1회만 존재하므로 .cache() 없이 메시지마다 zipWith하면 재구독된다.
				Mono<String> fromHandshake = session.getHandshakeInfo().getPrincipal()
						.map(WsSecurityContextSpikeTest::subOf)
						.defaultIfEmpty("EMPTY")
						.cache();

				return session.send(session.receive().flatMap(message ->
						// (a) 메시지 루프 안에서의 암묵 전파(Reactor Context)
						ReactiveSecurityContextHolder.getContext()
								.map(ctx -> subOf(ctx.getAuthentication()))
								.defaultIfEmpty("EMPTY")
								.zipWith(fromHandshake)
								.map(pair -> session.textMessage("ctx=" + pair.getT1() + ";handshake=" + pair.getT2()))));
			};
		}

		@Bean
		HandlerMapping spikeMapping(WebSocketHandler spikeHandler) {
			return new SimpleUrlHandlerMapping(Map.of("/ws/spike", spikeHandler), Ordered.HIGHEST_PRECEDENCE);
		}

	}

	@LocalServerPort
	private int port;

	@Test
	void propagatesAuthenticatedUserIntoWsMessageLoop() {
		String subject = "ws-spike-user";
		String token = TestJwtSupport.signedJwt(subject, List.of("USER"));

		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);

		AtomicReference<String> received = new AtomicReference<>();
		ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

		// execute()는 Mono<Void>라 에코 값을 안 돌려준다 — 핸들러 안에서 직접 꺼내 바깥 참조에 담는다.
		// take(1)이 없으면 서버가 세션을 닫을 때까지 receive()가 안 끝나 타임아웃까지 대기한다.
		client.execute(URI.create("ws://localhost:" + port + "/ws/spike"), headers,
					session -> WsTestExchange.exchange(session, active -> Mono.just(active.textMessage("ping")), 1,
							message -> received.set(message.getPayloadAsText())))
				.block(WsTestTimeouts.BLOCK);

		assertThat(received.get()).isEqualTo("ctx=" + subject + ";handshake=" + subject);
	}

	/** subOf: Authentication·Principal 어느 쪽으로 오든 실제 sub 값을 꺼낸다(캐스트 실패로 죽지 않게). */
	private static String subOf(Object principalOrAuthentication) {
		if (principalOrAuthentication instanceof JwtAuthenticationToken jwtAuthentication) {
			return jwtAuthentication.getToken().getSubject();
		}
		if (principalOrAuthentication instanceof Principal principal) {
			return principal.getName();
		}
		return String.valueOf(principalOrAuthentication);
	}

}
