package com.onggijonggi.api.chat;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

/**
 * Class Name : WsHandlerMappingConfig.java
 * Description : /api/ws를 CollabWebSocketHandler로 라우팅한다. 경로는 WsSecurityConfig의
 *               securityMatcher("/api/ws")와 반드시 맞춰야 한다.
 *
 *               경로에 방이 없다(이슈 #161) — 커넥션 하나가 여러 방을 나르고, 어느 방인지는 프레임의
 *               threadId가 가른다. 옛 경로 /api/ws/{threadId}는 병행하지 않고 바로 걷었다.
 */
@Configuration
public class WsHandlerMappingConfig {

	@Bean
	public HandlerMapping collabWebSocketMapping(CollabWebSocketHandler collabWebSocketHandler) {
		return new SimpleUrlHandlerMapping(Map.of("/api/ws", collabWebSocketHandler),
				Ordered.HIGHEST_PRECEDENCE);
	}

}
