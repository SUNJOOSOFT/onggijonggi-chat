package com.onggijonggi.api.authz;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : AuthorizationAuditCursor.java
 * Description : 감사 조회의 다음 페이지 위치(#259). 정렬 키 `(created_at, id)`만 담은 불투명 문자열이다.
 *               시각은 Instant ISO 문자열로 적어 PostgreSQL의 마이크로초까지 잃지 않는다. 조회 범위(Tenant·Workspace)는
 *               담지 않는다 — 범위는 매 요청 경로와 권한 판정으로 다시 정하므로 커서만으로 다른 범위에 닿을 수 없다.
 *               변조된 커서도 400이다(시각이 0001~9999년 밖이면 DB 바인딩 전에 막는다).
 */
public record AuthorizationAuditCursor(Instant createdAt, UUID id) {

	private static final String SEPARATOR = "|";

	public String encode() {
		String raw = createdAt + SEPARATOR + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	/** 형식이 틀리면 400이다. */
	public static AuthorizationAuditCursor decode(String value) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			int separator = raw.indexOf(SEPARATOR);
			if (separator < 0) throw new IllegalArgumentException("구분자 없음");
			return new AuthorizationAuditCursor(AuthorizationAuditQuery.instant(raw.substring(0, separator)),
					UUID.fromString(raw.substring(separator + 1)));
		} catch (RuntimeException invalid) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 커서다");
		}
	}
}
