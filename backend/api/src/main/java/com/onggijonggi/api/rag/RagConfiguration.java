package com.onggijonggi.api.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : RagConfiguration.java
 * Description : 방 문서 검색 설정(app.rag.*)을 레코드로 바인딩하고, 검색 전용 스케줄러를 둔다. 검색은 다시 쓰기·임베딩·ES를 블로킹으로
 *               기다리므로 BFF 공용 boundedElastic에서 돌면 ES·임베딩이 느려질 때 채팅·로그인의 DB 호출까지 밀린다(격벽).
 */
@Configuration
@EnableConfigurationProperties({RagProperties.class, RagTagProperties.class})
class RagConfiguration {

	@Bean(destroyMethod = "dispose")
	Scheduler ragSearchScheduler(RagProperties properties) {
		return Schedulers.newBoundedElastic(properties.search().threads(), properties.search().queue(), "rag-search");
	}
}
