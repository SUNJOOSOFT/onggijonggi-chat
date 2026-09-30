package com.onggijonggi.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : GlobalExceptionHandlerTest.java
 * Description : 새 참여자 상태 충돌 경로(이슈 #137)만 다룬다. ResponseStatusException 계열은
 *               ThreadParticipantControllerTest 등의 통합 테스트가 이미 응답 코드까지 검증한다.
 */
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	/**
	* ThrMbr.ver 같은 낙관적 잠금 필드가 읽은 뒤 다른 트랜잭션에 덮어써졌을 때 handleStatusException의
	* 409(PARTICIPANT_STATE_CONFLICT)와 같은 코드·문구로 응답해야, 클라이언트가 참여자 상태 충돌을
	* 한 가지 코드로 다루면 된다.
	*/
	@Test
	void mapsOptimisticLockingFailureToParticipantStateConflict() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/api/x"));

		var response = handler.handleOptimisticLockingFailure(
				new OptimisticLockingFailureException("stale row"), exchange);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().error().code()).isEqualTo("PARTICIPANT_STATE_CONFLICT");
	}

	/** 새 대화를 놓을 Tenant를 정할 수 없을 때(503)와 워크스페이스 누락(400)이 화면이 문구를 고를 수 있는 코드로 나간다. */
	@Test
	void mapsBadRequestAndServiceUnavailableToTheirOwnCodes() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/x"));

		var badRequest = handler.handleStatusException(new ResponseStatusException(HttpStatus.BAD_REQUEST), exchange);
		var unavailable = handler.handleStatusException(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE), exchange);

		assertThat(badRequest.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
		assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(unavailable.getBody().error().code()).isEqualTo("SERVICE_UNAVAILABLE");
	}
}
