package com.onggijonggi.api.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Class Name : ThreadSourceStorage.java
 * Description : 방 인가가 끝난 원본만 내부 워커로 전달한다. 브라우저에는 내부 URL·API key를 노출하지 않는다.
 */
@Component
public class ThreadSourceStorage {
	private static final Logger log = LoggerFactory.getLogger(ThreadSourceStorage.class);
	private final WebClient client;
	private final String apiKey;

	public ThreadSourceStorage(WebClient.Builder builder,
			@Value("${app.document.worker-url:http://localhost:8100}") String workerUrl,
			@Value("${app.document.internal-api-key:}") String apiKey) {
		this.apiKey = apiKey;
		this.client = builder.clone().baseUrl(workerUrl)
				.codecs(config -> config.defaultCodecs().maxInMemorySize(ThreadDocumentService.MAX_FILE_BYTES + 1024)).build();
	}

	private String path(UUID thread, UUID document, String digest) {
		if (apiKey.isBlank()) throw new IllegalStateException("문서 워커 내부 API key(app.document.internal-api-key)가 비어 있다");
		return "/api/v1/thread-sources/" + thread + "/" + document + "/" + digest;
	}

	public void save(UUID tenant, UUID thread, UUID document, String digest, byte[] content, Instant expiresAt, UUID attempt) {
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(content) {
			@Override public String getFilename() { return "source"; }
		});
		try {
			client.put().uri(path(thread, document, digest))
					.header("X-Internal-Api-Key", apiKey).header("X-Tenant-Id", tenant.toString())
					.header("X-Source-Expires-At", Long.toString(expiresAt.getEpochSecond()))
					.headers(headers -> { if (attempt != null) headers.set("X-Source-Attempt-Id", attempt.toString()); })
					.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(body)
					.retrieve().toBodilessEntity().block(Duration.ofSeconds(60));
		} catch (RuntimeException error) { throw unavailable(error); }
	}

	public byte[] read(UUID tenant, UUID thread, UUID document, String digest, UUID attempt) {
		try {
			byte[] content = client.get().uri(path(thread, document, digest))
					.headers(headers -> { if (attempt != null) headers.set("X-Source-Attempt-Id", attempt.toString()); })
					.header("X-Internal-Api-Key", apiKey).header("X-Tenant-Id", tenant.toString())
					.retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(60));
			if (content == null) throw new IllegalStateException("원본 응답이 비어 있다");
			return content;
		} catch (RuntimeException error) { throw unavailable(error); }
	}

	public void delete(UUID tenant, UUID thread, UUID document, String digest, UUID attempt) {
		try {
			client.delete().uri(path(thread, document, digest))
					.headers(headers -> { if (attempt != null) headers.set("X-Source-Attempt-Id", attempt.toString()); })
					.header("X-Internal-Api-Key", apiKey).header("X-Tenant-Id", tenant.toString())
					.retrieve().toBodilessEntity().block(Duration.ofSeconds(60));
		} catch (RuntimeException error) { throw unavailable(error); }
	}

	/**
	 * 사용자에게는 모두 저장소 장애(503)로 답하지만, 워커의 4xx는 재시도로 풀리지 않는 설정·데이터 문제다(내부 key 불일치,
	 * 시계 차이로 만료 거부, 워커 크기 상한이 BFF보다 낮음, 등록된 원본 유실). 그래서 상태와 워커의 오류 본문(code·message·
	 * requestId, 비밀값 없음)을 error로 남긴다. 네트워크·5xx는 일시 장애라 GlobalExceptionHandler의 warn으로 충분하다.
	 */
	private ThreadDocumentException unavailable(RuntimeException error) {
		if (error instanceof WebClientResponseException response && response.getStatusCode().is4xxClientError()) {
			String body = response.getResponseBodyAsString();
			// 404는 읽는 사이 문서가 삭제·정리된 정상 경합에서도 난다(호출부 재검증이 404로 답한다). 그래서 warn에 둔다.
			if (response.getStatusCode().value() == 404) log.warn("문서 워커에 원본이 없다: {}", body.length() > 300 ? body.substring(0, 300) : body);
			else log.error("문서 워커가 요청을 거절했다 — 내부 key·시계·크기 설정이나 원본 유실을 확인해야 한다: {} {} {}",
					response.getRequest() == null ? "" : response.getRequest().getMethod(), response.getStatusCode().value(),
					body.length() > 300 ? body.substring(0, 300) : body);
		}
		return ThreadDocumentException.storageUnavailable(error);
	}
}
