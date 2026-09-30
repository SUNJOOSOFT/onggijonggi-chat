package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.AuthorizationActorKind;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditTargetKind;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : AuthorizationAuditQuery.java
 * Description : 감사 조회의 선택 필터·페이지 크기·커서(#259). 요청 문자열을 여기서 해석해 틀린 값은 모두 400으로 돌린다.
 *               기간은 `from <= created_at < to`이고 둘 다 있으면 from < to여야 한다. 조회 범위는 담지 않는다 —
 *               경로와 권한 판정이 정한다. 시각은 0001~9999년, requestId는 컬럼(req_id varchar(255)) 길이 안이어야 한다 —
 *               Instant는 이 밖의 값도 받지만 PostgreSQL에 바인딩하는 순간 실패해 500이 된다.
 */
public record AuthorizationAuditQuery(Instant from, Instant to, AuthorizationAuditEventKind eventKind,
		AuthorizationAuditTargetKind targetKind, AuthorizationActorKind actorKind, UUID actorUserId, String requestId,
		int limit, AuthorizationAuditCursor cursor) {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 100;
	public static final int MAX_REQUEST_ID_LENGTH = 255;
	private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
	private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

	public static AuthorizationAuditQuery parse(String from, String to, String eventKind, String targetKind, String actorKind,
			String actorUserId, String requestId, String limit, String cursor) {
		Instant parsedFrom = optional(from, AuthorizationAuditQuery::instant, "from");
		Instant parsedTo = optional(to, AuthorizationAuditQuery::instant, "to");
		if (parsedFrom != null && parsedTo != null && !parsedFrom.isBefore(parsedTo)) throw badRequest("from은 to보다 앞이어야 한다");
		if (requestId != null && requestId.isBlank()) throw badRequest("requestId가 비어 있다");
		if (requestId != null && requestId.length() > MAX_REQUEST_ID_LENGTH) throw badRequest("requestId가 너무 길다");
		int parsedLimit = limit == null ? DEFAULT_LIMIT : optional(limit, Integer::parseInt, "limit");
		if (parsedLimit < 1 || parsedLimit > MAX_LIMIT) throw badRequest("limit은 1~" + MAX_LIMIT + "이다");
		return new AuthorizationAuditQuery(parsedFrom, parsedTo,
				optional(eventKind, AuthorizationAuditEventKind::valueOf, "eventKind"),
				optional(targetKind, AuthorizationAuditTargetKind::valueOf, "targetKind"),
				optional(actorKind, AuthorizationActorKind::valueOf, "actorKind"),
				optional(actorUserId, UUID::fromString, "actorUserId"),
				requestId, parsedLimit,
				cursor == null ? null : AuthorizationAuditCursor.decode(cursor));
	}

	/** 0001~9999년 밖이면 예외다(optional이 400으로 바꾼다). 커서의 시각도 같은 범위를 쓴다. */
	static Instant instant(String value) {
		Instant parsed = Instant.parse(value);
		if (parsed.isBefore(EARLIEST) || parsed.isAfter(LATEST)) throw new IllegalArgumentException("범위 밖 시각");
		return parsed;
	}

	private static <T> T optional(String value, Function<String, T> parser, String name) {
		if (value == null) return null;
		try {
			return parser.apply(value);
		} catch (RuntimeException invalid) {
			throw badRequest("잘못된 " + name + " 값이다");
		}
	}

	private static ResponseStatusException badRequest(String reason) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
	}
}
