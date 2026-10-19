package com.onggijonggi.api.chat;

import com.onggijonggi.common.document.ThreadSourcePaths;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : ThreadOutputStorage.java
 * Description : 방 인가가 끝난 출력(생성 문서)만 문서 워커의 읽기 API로 읽어 온다(#414). 서버가 SeaweedFS를 직접 읽지 않고,
 *               브라우저에는 내부 URL·API key·저장소 위치를 노출하지 않는다.
 */
@Component
public class ThreadOutputStorage {
	private static final Logger log = LoggerFactory.getLogger(ThreadOutputStorage.class);
	/** 워커의 출력 크기 상한(max_output_bytes, 25MiB)과 맞춘다. */
	static final int MAX_OUTPUT_BYTES = 26_214_400;
	private final WebClient client;
	private final String apiKey;

	public ThreadOutputStorage(WebClient.Builder builder,
			@Value("${app.document.worker-url:http://localhost:8100}") String workerUrl,
			@Value("${app.document.internal-api-key:}") String apiKey) {
		this.apiKey = apiKey;
		this.client = builder.clone().baseUrl(workerUrl)
				.codecs(config -> config.defaultCodecs().maxInMemorySize(MAX_OUTPUT_BYTES + 1024)).build();
	}

	/** 워커에 파일이 없으면 404, 그 밖의 실패는 저장소 장애(503)로 답한다. */
	public byte[] read(UUID tenant, String fileKey) {
		if (apiKey.isBlank()) throw ThreadDocumentException.storageUnavailable(
				new IllegalStateException("문서 워커 내부 API key(app.document.internal-api-key)가 비어 있다"));
		try {
			byte[] content = client.get()
					.uri(uri -> uri.path("/api/v1/documents/files").queryParam("objectKey", "{key}").build(fileKey))
					.header(ThreadSourcePaths.API_KEY_HEADER, apiKey).header(ThreadSourcePaths.TENANT_HEADER, tenant.toString())
					.retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(60));
			if (content == null) throw new IllegalStateException("출력 응답이 비어 있다");
			return content;
		} catch (RuntimeException error) {
			if (error instanceof WebClientResponseException response) {
				String body = response.getResponseBodyAsString();
				String shown = body.length() > 300 ? body.substring(0, 300) : body;
				if (response.getStatusCode().value() == 404) {
					log.warn("문서 워커에 출력 파일이 없다: {}", shown);
					throw new ResponseStatusException(HttpStatus.NOT_FOUND);
				}
				if (response.getStatusCode().is4xxClientError())
					log.error("문서 워커가 출력 읽기를 거절했다 — 내부 key·tenant 설정을 확인해야 한다: {} {}",
							response.getStatusCode().value(), shown);
			}
			throw ThreadDocumentException.storageUnavailable(error);
		}
	}
}
