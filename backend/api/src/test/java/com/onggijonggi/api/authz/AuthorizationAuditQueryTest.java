package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.common.authz.AuthorizationActorKind;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : AuthorizationAuditQueryTest.java
 * Description : 감사 조회 필터 해석(#259)을 검증한다 — 기본 limit, 값 해석, 틀린 UUID·시각·enum·limit·역전된 기간은 400.
 */
class AuthorizationAuditQueryTest {

	@Test
	void defaultsAndParsesEveryFilter() {
		UUID actor = UUID.randomUUID();
		AuthorizationAuditQuery query = AuthorizationAuditQuery.parse("2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z",
				"MEMBER_ASSIGNED", "MEMBER", "USER", actor.toString(), "import:abc", null, null);

		assertThat(query.limit()).isEqualTo(AuthorizationAuditQuery.DEFAULT_LIMIT);
		assertThat(query.from()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
		assertThat(query.eventKind()).isEqualTo(AuthorizationAuditEventKind.MEMBER_ASSIGNED);
		assertThat(query.actorKind()).isEqualTo(AuthorizationActorKind.USER);
		assertThat(query.actorUserId()).isEqualTo(actor);
		assertThat(query.requestId()).isEqualTo("import:abc");
		assertThat(query.cursor()).isNull();
	}

	@Test
	void invalidValuesAreBadRequests() {
		assertBadRequest(() -> parseLimit("0"));
		assertBadRequest(() -> parseLimit("101"));
		assertBadRequest(() -> parseLimit("many"));
		assertBadRequest(() -> AuthorizationAuditQuery.parse("yesterday", null, null, null, null, null, null, null, null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse(null, null, "NOT_AN_EVENT", null, null, null, null, null, null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse(null, null, null, "NOPE", null, null, null, null, null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse(null, null, null, null, "ROBOT", null, null, null, null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse(null, null, null, null, null, "not-a-uuid", null, null, null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse(null, null, null, null, null, null, " ", null, null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse(null, null, null, null, null, null, null, null, "garbage"));
		// PostgreSQL에 바인딩하면 실패하는 시각과 컬럼보다 긴 requestId는 500이 아니라 400이다.
		assertBadRequest(() -> AuthorizationAuditQuery.parse("+300000-01-01T00:00:00Z", null, null, null, null, null, null, null,
				null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse(null, "-0001-01-01T00:00:00Z", null, null, null, null, null, null,
				null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse(null, null, null, null, null, null, "r".repeat(256), null, null));
		assertThat(AuthorizationAuditQuery.parse(null, null, null, null, null, null, "r".repeat(255), null, null).requestId())
				.hasSize(255);
		// 같은 시각도 역전으로 본다 — 기간은 from <= created_at < to라 비어 버린다.
		assertBadRequest(() -> AuthorizationAuditQuery.parse("2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z", null, null, null, null,
				null, null, null));
		assertBadRequest(() -> AuthorizationAuditQuery.parse("2026-09-03T00:00:00Z", "2026-09-02T00:00:00Z", null, null, null, null,
				null, null, null));
	}

	private static AuthorizationAuditQuery parseLimit(String limit) {
		return AuthorizationAuditQuery.parse(null, null, null, null, null, null, null, limit, null);
	}

	private static void assertBadRequest(Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
	}
}
