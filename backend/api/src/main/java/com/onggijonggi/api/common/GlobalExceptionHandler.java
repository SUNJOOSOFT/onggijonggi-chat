package com.onggijonggi.api.common;

import com.onggijonggi.api.chat.IdempotencyKeyConflictException;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Class Name : GlobalExceptionHandler.java
 * Description : 요청 검증·본문 파싱 실패, 상태 예외(404 등), 그 외 처리되지 않은 예외를 공통 에러
 *               봉투 형식으로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(WebExchangeBindException.class)
	public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.orElse("요청 형식이 올바르지 않습니다.");
		return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", message, traceId(exchange)));
	}

	@ExceptionHandler(ServerWebInputException.class)
	public ResponseEntity<ErrorResponse> handleMalformed(ServerWebInputException ex, ServerWebExchange exchange) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("MALFORMED_REQUEST", "요청 본문을 읽을 수 없습니다.", traceId(exchange)));
	}

	/**
	* WebFlux가 라우팅 매칭 실패로 던지는 경우뿐 아니라, ChatController의 세션 소유권 검증처럼
	* 컨트롤러가 직접 던지는 경우도 여기로 온다(메서드 이름이 시사하는 것보다 넓다).
	*
	* 상태코드마다 다른 코드를 주는 것은, 클라이언트가 "다시 시도하면 되는 문제"와 "권한이 없어
	* 영영 안 되는 문제"를 가려야 하기 때문이다. 409를 CONFLICT가 아니라 참여자 상태 이름으로
	* 좁혀 두는 것은 지금 그 상태를 던지는 곳이 스레드 참여자 관리(이슈 #20)뿐이라서다 — 다른
	* 409가 생기면 이 분기를 그때 쪼갠다.
	*/
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> handleStatusException(ResponseStatusException ex,
			ServerWebExchange exchange) {
		HttpStatusCode status = ex.getStatusCode();
		String code = "REQUEST_ERROR";
		String message = "요청을 처리할 수 없습니다.";
		if (status == HttpStatus.NOT_FOUND) {
			code = "NOT_FOUND";
			message = "요청한 리소스를 찾을 수 없습니다.";
		} else if (status == HttpStatus.FORBIDDEN) {
			code = "FORBIDDEN";
			message = "이 작업을 수행할 권한이 없습니다.";
		} else if (status == HttpStatus.CONFLICT) {
			code = "PARTICIPANT_STATE_CONFLICT";
			message = "참여자 상태가 바뀌어 요청을 처리할 수 없습니다.";
		}
		return ResponseEntity.status(status).body(ErrorResponse.of(code, message, traceId(exchange)));
	}

	/** 같은 idempotency key에 이전과 다른 요청 내용이 온 경우(이슈 #149) — 참여자 상태 충돌(409)과는
	 * 원인이 달라 별도 타입·코드로 구분한다. */
	@ExceptionHandler(IdempotencyKeyConflictException.class)
	public ResponseEntity<ErrorResponse> handleIdempotencyKeyConflict(IdempotencyKeyConflictException ex,
			ServerWebExchange exchange) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("IDEMPOTENCY_KEY_CONFLICT", ex.getMessage(), traceId(exchange)));
	}

	/**
	* 게이트웨이(LiteLLM)가 모델 호출을 거절한 경우. 키 미설정·잘못된 키·해당 모델 미제공이 모두
	* 여기로 오는데 BFF는 셋을 구분할 근거가 없다 — 어떤 키가 채워졌는지 아는 곳이 게이트웨이뿐이라
	* 화면 목록에는 키 없는 모델도 뜬다. 그래서 원인을 단정하지 않고 코드 하나로 넘기고, 사용자에게
	* 보일 문구는 CLIENT(lib/api/errors.ts)가 고른다.
	*
	* 502를 쓴다 — 401은 CLIENT의 authFetch가 세션 만료로 보고 재로그인을 시도해버린다.
	*/
	@ExceptionHandler(OpenAIServiceException.class)
	public ResponseEntity<ErrorResponse> handleModelUnavailable(OpenAIServiceException ex,
			ServerWebExchange exchange) {
		log.error("게이트웨이가 모델 호출을 거절했습니다(traceId={})", traceId(exchange), ex);
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(ErrorResponse.of("MODEL_UNAVAILABLE", "모델을 호출할 수 없습니다.", traceId(exchange)));
	}

	/** 예외 상세·스택트레이스는 서버 로그에만 남기고 응답에는 고정 안전 문구만 포함한다. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, ServerWebExchange exchange) {
		log.error("처리되지 않은 예외 발생(traceId={})", traceId(exchange), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of("INTERNAL_ERROR", "서버 오류가 발생했습니다.", traceId(exchange)));
	}

	private String traceId(ServerWebExchange exchange) {
		return exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTR);
	}

}
