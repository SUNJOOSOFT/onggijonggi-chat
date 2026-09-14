package com.onggijonggi.api.auth;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : WsSecurityConfig.java
 * Description : /api/ws 전용 인증 체인. 메인 SecurityConfig.filterChain()은 securityMatcher가 없어
 *               전 경로를 잡으므로(anyExchange().denyAll()), 이 체인이 더 높은 순위로 먼저 매칭돼야
 *               /api/ws 요청이 Authorization 헤더 부재를 이유로 메인 체인에 거부되지 않는다.
 *               이슈 #7 스파이크(WsSecurityContextSpikeTest)가 이 배선으로 인증 컨텍스트가 WS 메시지
 *               루프까지 전파됨을 먼저 확인했다 — 다만 그 스파이크는 Authorization 헤더 조건이었고,
 *               여기서는 서브프로토콜(WsSubProtocolBearerTokenConverter)로 토큰을 받는 점이 다르다.
 *               Origin 검증은 WsOriginWebFilter가 인증보다 먼저 맡는다(이슈 #5) — SecurityConfig의
 *               CORS 설정은 이 체인에 닿지 않으므로 그 필터가 이 경로의 유일한 Origin 검사다.
 *               레이트리밋도 같은 이유로 여기서 다시 건다(이슈 #6) — 메인 체인의 RateLimitWebFilter는
 *               SecurityConfig가 자기 체인에만 꽂고 @Component도 아니라, 이 경로의 핸드셰이크는
 *               한 번도 세어지지 않고 있었다. 연결 내 메시지 빈도 제한은 여기 없다 — 핸들러가
 *               인바운드 프레임을 받아 처리하는 지점이 아직 없어서(#62의 receive()는 종료 감지용이다)
 *               셀 대상이 없다. 그 절반은 #16 방 레지스트리·#17 @AI 분기 뒤로 열어둔 채로 뒀다.
 */
@Configuration
public class WsSecurityConfig {

	private final EdgeAuthenticationEntryPoint authenticationEntryPoint;
	private final EdgeAccessDeniedHandler accessDeniedHandler;
	private final IdentityProviderService identityProviderService;
	private final ObjectMapper objectMapper;
	private final Clock rateLimitClock;

	/** HTTP CORS와 같은 화이트리스트를 쓴다 — 프론트 오리진은 하나뿐이라 값이 두 곳으로 갈리는 편이 더 위험하다. */
	@Value("${app.cors.allowed-origins}")
	private List<String> allowedOrigins;

	/** 윈도우 길이는 HTTP와 공유한다 — 한도만 다르면 되고, 창 길이까지 갈리면 두 정책을 함께 읽기 어려워진다. */
	@Value("${app.ratelimit.window-seconds:60}")
	private long rateLimitWindowSeconds;

	/** 핸드셰이크 전용 한도. HTTP의 app.ratelimit.per-minute과 값을 나눠 갖는 이유는 wsFilterChain() 주석 참조. */
	@Value("${app.ratelimit.ws-handshake-per-minute:30}")
	private int wsHandshakePerMinute;

	public WsSecurityConfig(EdgeAuthenticationEntryPoint authenticationEntryPoint,
			EdgeAccessDeniedHandler accessDeniedHandler, IdentityProviderService identityProviderService,
			ObjectMapper objectMapper, Clock rateLimitClock) {
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.accessDeniedHandler = accessDeniedHandler;
		this.identityProviderService = identityProviderService;
		this.objectMapper = objectMapper;
		this.rateLimitClock = rateLimitClock;
	}

	/**
	* RateLimitWebFilter를 새 인스턴스로 만드는 것이 곧 버킷 분리다 — 카운터가 인스턴스 필드라
	* 메인 체인의 것과 자동으로 갈린다. 우연이 아니라 의도다: 같은 sub가 화면을 쓰면서(HTTP) 소켓을
	* 붙는(WS) 것은 정상인데 한 통에 담으면 서로를 굶긴다. 값도 나눠 갖는다 — 흔한 경우 핸드셰이크는
	* 끊길 때마다 한 번이라 HTTP의 20회는 목적에 안 맞고, 반대로 너무 조이면 재연결이 반복될 때
	* 정상 사용자가 걸린다. 기본값 30은 그 사이를 잡은 값이다: ws-connection.ts(이슈 #4 PR #68) 기준
	* 백오프가 min(2^n x 500ms, 10초)라 끊긴 직후 시도가 0·1·3·7·15·25·35·45·55초에 몰려 첫 1분이
	* 탭당 9회, 그 뒤 정상 상태가 탭당 6회다. 탭 3개면 첫 1분 27회로 30에 거의 닿는다 — 여유가 크지
	* 않다는 걸 알고 고른 값이니, 재연결이 계속 실패하는 상황이 늘면 이 값부터 다시 본다.
	*
	* 다만 한도가 붙잡는 것은 연결을 세우는 "빈도"뿐이고 동시에 열려 있는 연결 "수"가 아니다. 이슈 #62가
	* 들어오면서 CollabWebSocketHandler는 토큰이 만료될 때까지 세션을 유지하므로, 분당 30개씩 열어
	* 토큰 수명 내내 붙들고 있는 것을 이 필터만으로는 막지 못한다 — 동시 연결 수 상한은 별도 방어이고
	* 여기서 얹을 수 있는 것이 아니다(세션을 세려면 레지스트리가 있어야 한다, #16).
	*
	* 초과는 업그레이드 전에 429로 끊는다 — HTTP와 같은 봉투(RATE_LIMITED)·같은 Retry-After가 그대로
	* 나가고, 연결을 세워주지 않으니 서버가 무는 비용도 가장 작다. 다만 브라우저 WebSocket API는
	* 핸드셰이크 응답의 status도 body도 클라이언트에 넘기지 않아(이슈 #4 PR #68 주석), 프론트는 이걸
	* 429로 읽지 못하고 1006으로만 본다. 사유를 전달하려면 연결을 세운 뒤 커스텀 close code로 끊어야
	* 하는데, 그건 공격자에게 연결 비용을 그대로 내주는 거래라 여기서는 택하지 않았다 — 사유 전달이
	* 필요해지면 #62의 4000번대 코드와 함께 다룰 문제다. 지금도 재연결 자체는 백오프로 안전하게 물러난다.
	*/
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	public SecurityWebFilterChain wsFilterChain(ServerHttpSecurity http) {
		return http
				.securityMatcher(new PathPatternParserServerWebExchangeMatcher("/api/ws"))
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				// 토큰을 검증하기 전에 거른다 — Origin이 틀린 요청은 JWT를 파싱해볼 이유가 없다.
				.addFilterBefore(new WsOriginWebFilter(allowedOrigins, objectMapper),
						SecurityWebFiltersOrder.AUTHENTICATION)
				.authorizeExchange(exchange -> exchange.anyExchange().hasRole("USER"))
				.oauth2ResourceServer(oauth2 -> oauth2
						.bearerTokenConverter(new WsSubProtocolBearerTokenConverter())
						.jwt(jwt -> jwt.jwtDecoder(identityProviderService.jwtDecoder())
								.jwtAuthenticationConverter(identityProviderService.jwtAuthenticationConverter()))
						.authenticationEntryPoint(authenticationEntryPoint))
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.addFilterAfter(new RateLimitWebFilter(objectMapper, rateLimitClock, rateLimitWindowSeconds,
						wsHandshakePerMinute),
						SecurityWebFiltersOrder.AUTHORIZATION)
				.build();
	}

}
