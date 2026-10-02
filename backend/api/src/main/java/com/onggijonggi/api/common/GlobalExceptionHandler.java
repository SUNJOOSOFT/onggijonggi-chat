package com.onggijonggi.api.common;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminUnavailableException;
import com.onggijonggi.api.authz.RbacStateConflictException;
import com.onggijonggi.api.chat.IdempotencyKeyConflictException;
import com.onggijonggi.api.chat.InviteeOutsideWorkspaceException;
import com.onggijonggi.api.chat.MsgFileRejectedException;
import com.onggijonggi.api.chat.ThreadDocumentException;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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
		} else if (status == HttpStatus.BAD_REQUEST) {
			// 워크스페이스 필수 등 서비스가 던지는 입력 오류. 화면이 "입력을 확인하라"고 안내할 수 있게 검증 오류와 같은 코드를 쓴다.
			code = "VALIDATION_ERROR";
			message = "요청 내용을 확인해 주세요.";
		} else if (status == HttpStatus.SERVICE_UNAVAILABLE) {
			// 새 대화를 놓을 고객사(Tenant)를 정할 수 없는 등 서버 설정이 준비되지 않은 경우. 재시도로 풀리지 않는다.
			code = "SERVICE_UNAVAILABLE";
			message = "서비스를 사용할 수 없습니다.";
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

	/** 초대 대상이 그 방의 워크스페이스를 볼 수 없는 경우. 다시 시도해도 안 되지만 호출자의 권한 문제(403)는
	 * 아니라서, 참여자 상태 충돌과 같은 409에 별도 코드를 붙여 화면이 이유를 알려 줄 수 있게 한다. */
	@ExceptionHandler(InviteeOutsideWorkspaceException.class)
	public ResponseEntity<ErrorResponse> handleInviteeOutsideWorkspace(InviteeOutsideWorkspaceException ex,
			ServerWebExchange exchange) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("INVITEE_NO_WORKSPACE_ACCESS", ex.getMessage(), traceId(exchange)));
	}

	/** 첨부 파일을 받을 수 없는 경우 — 형식·크기·내용 없음. 사용자가 고칠 수 있는 이유라 문구를 그대로 보인다. */
	@ExceptionHandler(MsgFileRejectedException.class)
	public ResponseEntity<ErrorResponse> handleMsgFileRejected(MsgFileRejectedException ex, ServerWebExchange exchange) {
		return ResponseEntity.status(ex.getStatus())
				.body(ErrorResponse.of(ex.getCode(), ex.getMessage(), traceId(exchange)));
	}

	/** 방 문서(#338)가 문서 고유의 이유로 거부된 경우. 원본 저장소 장애(503)는 사용자에게 재시도만 안내하고 원인은
	 * 서버에만 남는다 — 이 핸들러가 아니면 503이 로그 없이 사라진다. */
	@ExceptionHandler(ThreadDocumentException.class)
	public ResponseEntity<ErrorResponse> handleThreadDocument(ThreadDocumentException ex, ServerWebExchange exchange) {
		if (ex.getStatusCode().is5xxServerError())
			log.warn("문서 원본 저장소 요청 실패(traceId={})", traceId(exchange), ex.getCause());
		return ResponseEntity.status(ex.getStatusCode())
				.body(ErrorResponse.of(ex.getCode(), ex.getReason(), traceId(exchange)));
	}

	/** 코덱 상한에서 끊긴 본문 — 아니면 처리되지 않은 예외(500)가 된다. 멀티파트는 파서의 파트 크기 상한
	 * (spring.webflux.multipart.max-disk-usage-per-part)이고, 첨부·방 문서 모두 10MiB 상한이라 컨트롤러가 내는 크기 초과와
	 * 같은 code로 답한다. 그 밖의 경우는 서버가 바깥 응답(모델 목록 등)을 읽다 상한에 걸린 것일 수 있어 요청자 잘못(413)으로
	 * 답하지 않고 처리되지 않은 예외(500)와 같게 둔다. */
	@ExceptionHandler(DataBufferLimitException.class)
	public ResponseEntity<ErrorResponse> handleBodyLimit(DataBufferLimitException ex, ServerWebExchange exchange) {
		if (!MediaType.MULTIPART_FORM_DATA.isCompatibleWith(exchange.getRequest().getHeaders().getContentType()))
			return handleUnexpected(ex, exchange);
		return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
				.body(ErrorResponse.of("FILE_TOO_LARGE", "파일이 너무 큽니다.", traceId(exchange)));
	}

	/** Workspace·부여·org-unit 관리(#260)가 현재 권한 구성 상태 때문에 거부된 경우(선언 리소스, 마지막 ADMIN, 남은 방 등).
	 * 참여자 상태 충돌과 원인이 달라 별도 코드를 붙인다 — 같은 코드면 화면이 "참여자 정보가 바뀌었다"고 잘못 안내한다. */
	@ExceptionHandler(RbacStateConflictException.class)
	public ResponseEntity<ErrorResponse> handleRbacStateConflict(RbacStateConflictException ex, ServerWebExchange exchange) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("RBAC_STATE_CONFLICT", "권한 구성 상태 때문에 요청을 처리할 수 없습니다.", traceId(exchange)));
	}

	/**
	* BFF 전용 Keycloak 관리 클라이언트로 관리 조회를 할 수 없는 설정 문제(#326) — 토큰 발급 거부, Admin API 403.
	* 다시 시도해도 풀리지 않아 일반 오류("잠시 후 다시")와 다른 코드를 붙이고, 문구는 CLIENT가 고른다. 운영자가 고쳐야
	* 하는 서버 설정이라 요지를 warn으로 남긴다(비밀값·Keycloak 응답 본문 없음).
	*/
	@ExceptionHandler(KeycloakAdminUnavailableException.class)
	public ResponseEntity<ErrorResponse> handleKeycloakAdminUnavailable(KeycloakAdminUnavailableException ex,
			ServerWebExchange exchange) {
		log.warn("Keycloak 관리 조회를 할 수 없다(traceId={}): {}", traceId(exchange), ex.summary());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(ErrorResponse.of("KEYCLOAK_ADMIN_UNAVAILABLE", "사용자 정보를 불러올 수 없습니다.", traceId(exchange)));
	}

	/** ThrMbr.ver(이슈 #137) 같은 낙관적 잠금 필드가 읽은 뒤 다른 트랜잭션에 덮어써졌을 때. 같은
	 * 참여자 상태 충돌이라 코드·문구를 handleStatusException의 409와 맞춘다 — 클라이언트가 다시
	 * 명단을 읽고 재시도하면 되는 문제다. */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException ex,
			ServerWebExchange exchange) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("PARTICIPANT_STATE_CONFLICT", "참여자 상태가 바뀌어 요청을 처리할 수 없습니다.",
						traceId(exchange)));
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
