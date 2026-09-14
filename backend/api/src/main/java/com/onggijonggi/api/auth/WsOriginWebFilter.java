package com.onggijonggi.api.auth;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : WsOriginWebFilter.java
 * Description : WS 핸드셰이크의 Origin을 화이트리스트와 대조해 Cross-Site WebSocket Hijacking을
 *               막는다(이슈 #5). SecurityConfig의 CORS 설정은 이 경로에 닿지 않는다 — WsSecurityConfig가
 *               /api/ws를 별도 체인으로 먼저 채가고 그 체인에는 .cors()가 없으며, 설령 있어도 WS
 *               Upgrade는 프리플라이트도 단순요청도 아니라 CorsWebFilter가 판단할 대상이 아니다.
 *               그래서 이 필터가 이 경로의 최초이자 유일한 Origin 검사다.
 *               RateLimitWebFilter와 같은 이유로 @Component를 붙이지 않는다 — 전역 WebFilter로도
 *               등록되면 /api/ws 밖의 요청까지 이 규칙에 걸린다.
 *               Origin 검증은 인가라기보다 CSRF 계열이지만, 이 필터도 WsSecurityConfig가 직접
 *               만들어 꽂는 구현 세부사항이라 RateLimitWebFilter와 같이 api/auth에 둔다(이슈 #106).
 *
 *               Origin 헤더가 아예 없으면 통과시킨다. 이 검사가 실제로 방어하는 대상은 브라우저에서
 *               실행되는 남의 사이트 스크립트 하나뿐인데, 브라우저는 Origin을 반드시 붙이고 위조하지도
 *               못한다. 반대로 브라우저가 아닌 클라이언트는 Origin을 원하는 값으로 지어낼 수 있어,
 *               헤더가 없다는 이유로 거부해봐야 공격자는 화이트리스트 값을 붙이면 그만이고 헤더를
 *               안 붙이는 정직한 클라이언트만 막힌다. HTTP 경로도 이미 같은 규칙으로 돈다 — Spring은
 *               Origin 없는 요청을 CORS 요청으로 분류하지 않아 CorsWebFilter가 그냥 통과시킨다.
 */
final class WsOriginWebFilter implements WebFilter {

	private final List<String> allowedOrigins;
	private final ObjectMapper objectMapper;

	WsOriginWebFilter(List<String> allowedOrigins, ObjectMapper objectMapper) {
		this.allowedOrigins = List.copyOf(allowedOrigins);
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String origin = exchange.getRequest().getHeaders().getOrigin();
		if (origin == null || allowedOrigins.contains(origin)) {
			return chain.filter(exchange);
		}
		return EdgeErrorResponseWriter.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
				"허용되지 않은 Origin입니다.", objectMapper);
	}

}
