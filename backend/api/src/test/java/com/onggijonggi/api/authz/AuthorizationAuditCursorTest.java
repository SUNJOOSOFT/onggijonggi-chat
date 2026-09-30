package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : AuthorizationAuditCursorTest.java
 * Description : 감사 조회 커서(#259)가 마이크로초 시각과 id를 잃지 않고 왕복하고, 형식이 틀린 커서는 400인지 검증한다.
 */
class AuthorizationAuditCursorTest {

	@Test
	void roundTripsMicrosecondTimestampsAndIds() {
		AuthorizationAuditCursor cursor = new AuthorizationAuditCursor(Instant.parse("2026-09-29T01:02:03.123456Z"), UUID.randomUUID());

		AuthorizationAuditCursor decoded = AuthorizationAuditCursor.decode(cursor.encode());

		assertThat(decoded).isEqualTo(cursor);
		assertThat(cursor.encode()).doesNotContain("=", "+", "/");
	}

	@Test
	void malformedCursorsAreBadRequests() {
		String noSeparator = Base64.getUrlEncoder().encodeToString("2026-09-29T01:02:03Z".getBytes(StandardCharsets.UTF_8));
		String badUuid = Base64.getUrlEncoder().encodeToString("2026-09-29T01:02:03Z|nope".getBytes(StandardCharsets.UTF_8));
		String badTime = Base64.getUrlEncoder().encodeToString(("yesterday|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));

		String outOfRange = Base64.getUrlEncoder().encodeToString(("+300000-01-01T00:00:00Z|" + UUID.randomUUID())
				.getBytes(StandardCharsets.UTF_8));

		for (String value : new String[] { "!!not-base64!!", noSeparator, badUuid, badTime, outOfRange, "" }) {
			assertThatThrownBy(() -> AuthorizationAuditCursor.decode(value))
					.isInstanceOfSatisfying(ResponseStatusException.class,
							error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		}
	}
}
