package com.onggijonggi.api.authz;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Class Name : CasbinProperties.java
 * Description : 03·CORE casbin-server(gRPC) 접속 설정. address는 casbin 프로필(application-casbin.properties)이
 *               채운다. 비어 있으면 판정을 묻지 못해 거부한다(권한 판정이 꺼져 있으면 쓰이지 않는다).
 */
@Component
@ConfigurationProperties(prefix = "app.casbin")
public class CasbinProperties {

	/** host:port. compose에서는 casbin:50051이다. */
	private String address = "";
	/** 판정·규칙 적재 호출 한 번의 시간 상한. 넘기면 거부한다. */
	private Duration deadline = Duration.ofSeconds(2);

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public Duration getDeadline() {
		return deadline;
	}

	public void setDeadline(Duration deadline) {
		this.deadline = deadline;
	}
}
