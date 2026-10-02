package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Class Name : ThreadSourceStorageTest.java
 * Description : BFF↔문서 워커 원본 계약 — 경로·내부 헤더(key·Tenant·만료·저장 시도)를 워커 README대로 보내는지, 워커 거절과
 *               설정 누락이 모두 저장소 장애(503)로 바뀌고 원인은 cause에만 남는지 확인한다.
 */
class ThreadSourceStorageTest {

	private final List<ClientRequest> requests = new ArrayList<>();
	private final UUID tenant = UUID.randomUUID();
	private final UUID thread = UUID.randomUUID();
	private final UUID document = UUID.randomUUID();
	private final String digest = "a".repeat(64);

	private ThreadSourceStorage storage(HttpStatus status, String apiKey) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			requests.add(request);
			return Mono.just(ClientResponse.create(status).header("Content-Type", "application/json")
					.body("{\"code\":\"INVALID_SOURCE_EXPIRY\",\"message\":\"m\",\"requestId\":\"r\"}").build());
		});
		return new ThreadSourceStorage(builder, "http://worker", apiKey);
	}

	@Test
	void sendsTheInternalHeadersOnTheSourcePath() {
		UUID attempt = UUID.randomUUID();
		Instant expires = Instant.ofEpochSecond(1_800_000_000L);

		storage(HttpStatus.CREATED, "key").save(tenant, thread, document, digest, new byte[] {1}, expires, attempt);

		ClientRequest sent = requests.get(0);
		assertThat(sent.url().toString()).isEqualTo("http://worker/api/v1/thread-sources/" + thread + "/" + document + "/" + digest);
		assertThat(sent.headers().getFirst("X-Internal-Api-Key")).isEqualTo("key");
		assertThat(sent.headers().getFirst("X-Tenant-Id")).isEqualTo(tenant.toString());
		assertThat(sent.headers().getFirst("X-Source-Expires-At")).isEqualTo("1800000000");
		assertThat(sent.headers().getFirst("X-Source-Attempt-Id")).isEqualTo(attempt.toString());
	}

	/** 저장 시도가 없던 기존 원본은 헤더 없이 기존 경로를 가리킨다. */
	@Test
	void legacySourceWithoutAttemptOmitsTheHeader() {
		storage(HttpStatus.NO_CONTENT, "key").delete(tenant, thread, document, digest, null);

		assertThat(requests.get(0).headers().containsHeader("X-Source-Attempt-Id")).isFalse();
	}

	@Test
	void workerRejectionBecomesStorageUnavailableWithTheCauseKept() {
		assertThatThrownBy(() -> storage(HttpStatus.BAD_REQUEST, "key").read(tenant, thread, document, digest, null))
				.isInstanceOfSatisfying(ThreadDocumentException.class, error -> {
					assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(error.getCode()).isEqualTo("DOCUMENT_STORAGE_UNAVAILABLE");
					assertThat(error.getCause()).isInstanceOf(WebClientResponseException.BadRequest.class);
				});
	}

	/** 내부 key가 비면 워커에 요청하지 않고 실패한다 — 빈 key로 보내 401을 받는 것보다 원인이 분명하다. */
	@Test
	void missingInternalKeyFailsWithoutCallingTheWorker() {
		assertThatThrownBy(() -> storage(HttpStatus.OK, "").read(tenant, thread, document, digest, null))
				.isInstanceOf(ThreadDocumentException.class)
				.hasCauseInstanceOf(IllegalStateException.class);
		assertThat(requests).isEmpty();
	}
}
