package com.onggijonggi.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Class Name : ApiApplication.java
 * Description : 단일 실행 애플리케이션(Spring Boot WebFlux) 진입점. 채팅 BFF 계층과 범용 계층이
 *               한 프로세스 위에 함께 구성된다. 02·EDGE(Spring Security/Keycloak)도 여기 얹힌다.
 *
 *               스캔 범위를 com.onggijonggi으로 넓힌다 — common 모듈의 클래스는
 *               com.onggijonggi.common.* 에 있어 기본 스캔(선언 패키지 하위)에 걸리지 않는다.
 *               셋 다 필요하다: 빈은 scanBasePackages, Entity는 EntityScan, Repository는
 *               EnableJpaRepositories가 각각 따로 찾는다.
 *
 *               EnableScheduling은 RiskCheckBatchService(#28)의 주기 스캔에 필요하다.
 */
@SpringBootApplication(scanBasePackages = "com.onggijonggi")
@EntityScan("com.onggijonggi")
@EnableJpaRepositories("com.onggijonggi")
@EnableScheduling
public class ApiApplication {

	/** main: 스프링 부트 애플리케이션을 구동한다. */
	public static void main(String[] args) {
		SpringApplication.run(ApiApplication.class, args);
	}

}
