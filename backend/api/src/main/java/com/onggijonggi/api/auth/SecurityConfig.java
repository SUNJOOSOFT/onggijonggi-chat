package com.onggijonggi.api.auth;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : SecurityConfig.java
 * Description : 필터체인 CORS → JWT → RBAC → RateLimit 조립.
 *               감사 로그는 아직 구현하지 않았다. CORS가 최상단이라 프리플라이트(OPTIONS)는
 *               CorsWebFilter가 JWT 검증 전에 응답하고, authorizeExchange의 OPTIONS permitAll()은
 *               방어적 이중 안전장치다.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

	private final EdgeAuthenticationEntryPoint authenticationEntryPoint;
	private final EdgeAccessDeniedHandler accessDeniedHandler;
	private final ObjectMapper objectMapper;
	private final IdentityProviderService identityProviderService;
	private final Clock rateLimitClock;

	@Value("${app.cors.allowed-origins}")
	private List<String> allowedOrigins;

	@Value("${app.ratelimit.window-seconds:60}")
	private long rateLimitWindowSeconds;

	@Value("${app.ratelimit.per-minute:20}")
	private int rateLimitPerMinute;

	public SecurityConfig(EdgeAuthenticationEntryPoint authenticationEntryPoint,
			EdgeAccessDeniedHandler accessDeniedHandler, ObjectMapper objectMapper,
			IdentityProviderService identityProviderService, Clock rateLimitClock) {
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.accessDeniedHandler = accessDeniedHandler;
		this.objectMapper = objectMapper;
		this.identityProviderService = identityProviderService;
		this.rateLimitClock = rateLimitClock;
	}

	/**
	* CSRF·formLogin·httpBasic을 비활성화한다 — Bearer 토큰 전용 무상태 API라 세션·폼 로그인을 전제로
	* 한 보호 장치가 필요 없다. 아래 DSL 호출 순서는 실행 순서가 아니다(실행 순서는 클래스 설명 참조).
	*/
	@Bean
	public SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.authorizeExchange(exchange -> exchange
						.pathMatchers(HttpMethod.OPTIONS).permitAll()
						.pathMatchers("/actuator/health").permitAll()
						.pathMatchers("/api/**").hasRole("USER")
						.anyExchange().denyAll())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtDecoder(identityProviderService.jwtDecoder())
								.jwtAuthenticationConverter(identityProviderService.jwtAuthenticationConverter()))
						.authenticationEntryPoint(authenticationEntryPoint))
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.addFilterAfter(rateLimitWebFilter(), SecurityWebFiltersOrder.AUTHORIZATION)
				.build();
	}

	private RateLimitWebFilter rateLimitWebFilter() {
		return new RateLimitWebFilter(objectMapper, rateLimitClock, rateLimitWindowSeconds, rateLimitPerMinute);
	}

	/**
	* CORS는 브라우저만 강제한다 — 서버 대 서버 테스트에는 프리플라이트가 없어, 여기 빠진 메서드나
	* 헤더는 테스트를 통과하고 화면에서만 막힌다. 컨트롤러에 새 메서드나 커스텀 요청 헤더를 더할
	* 때 이 목록도 함께 늘려야 한다.
	*/
	private CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		// PUT은 소유권 위임(#20)·잠금·보관(#131)이 쓴다.
		configuration.setAllowedMethods(List.of("POST", "GET", "PUT", "DELETE", "PATCH", "OPTIONS"));
		// Idempotency-Key는 협업방 생성 재시도(#149)가 실어 보낸다.
		configuration.setAllowedHeaders(
				List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
		configuration.setAllowCredentials(false);
		configuration.setExposedHeaders(List.of("X-Trace-Id", "Retry-After"));
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

}
