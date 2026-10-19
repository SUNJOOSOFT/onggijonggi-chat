package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Class Name : ThreadOutputStorageTest.java
 * Description : BFF↔문서 워커 출력 읽기 계약 — 경로·objectKey 인코딩·내부 헤더를 보내는지, 워커의 404는 404로, 그 밖의 실패와
 *               설정 누락은 저장소 장애(503)로 바뀌는지 확인한다.
 */
class ThreadOutputStorageTest {
	private final List<ClientRequest> requests = new ArrayList<>();
	private final UUID tenant = UUID.randomUUID();
	private final String fileKey = "documents/" + tenant + "/2026/10/19/01ARZ3NDEKTSV4RRFFQ69G5FAV/주간 보고서.docx";

	private ThreadOutputStorage storage(HttpStatus status, String apiKey) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			requests.add(request);
			return Mono.just(ClientResponse.create(status).header("Content-Type", "application/octet-stream")
					.body("{\"code\":\"FILE_NOT_FOUND\",\"message\":\"m\",\"requestId\":\"r\"}").build());
		});
		return new ThreadOutputStorage(builder, "http://worker", apiKey);
	}

	@Test
	void readsTheFileFromTheWorkerReadApiWithTheInternalHeaders() {
		byte[] content = storage(HttpStatus.OK, "key").read(tenant, fileKey);

		ClientRequest sent = requests.get(0);
		assertThat(sent.url().getRawPath()).isEqualTo("/api/v1/documents/files");
		assertThat(sent.url().getQuery()).isEqualTo("objectKey=" + fileKey);
		assertThat(sent.url().getRawQuery()).doesNotContain(" ");
		assertThat(sent.headers().getFirst("X-Internal-Api-Key")).isEqualTo("key");
		assertThat(sent.headers().getFirst("X-Tenant-Id")).isEqualTo(tenant.toString());
		assertThat(content).isNotEmpty();
	}

	@Test
	void aMissingFileIsNotFound() {
		assertThatThrownBy(() -> storage(HttpStatus.NOT_FOUND, "key").read(tenant, fileKey))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void otherWorkerFailuresBecomeStorageUnavailableWithTheCauseKept() {
		for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.BAD_REQUEST, HttpStatus.BAD_GATEWAY})
			assertThatThrownBy(() -> storage(status, "key").read(tenant, fileKey))
					.isInstanceOfSatisfying(ThreadDocumentException.class, error -> {
						assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
						assertThat(error.getCode()).isEqualTo("DOCUMENT_STORAGE_UNAVAILABLE");
						assertThat(error.getCause()).isNotNull();
					});
	}

	@Test
	void missingInternalKeyFailsWithoutCallingTheWorker() {
		assertThatThrownBy(() -> storage(HttpStatus.OK, "").read(tenant, fileKey))
				.isInstanceOf(ThreadDocumentException.class).hasCauseInstanceOf(IllegalStateException.class);
		assertThat(requests).isEmpty();
	}
}
