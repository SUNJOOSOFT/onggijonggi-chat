package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.common.document.ThreadSourcePaths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ThreadDocumentCreation.java
 * Description : AI가 부르는 새 문서 만들기(#415). 모델이 넘긴 문서 계획을 워커의 한도로 먼저 검증하고, 방에 문서를 만들 수 있는
 *               사람·상태인지 확인한 뒤, 문서 워커의 생성 API로 파일을 만들어 방의 출력(ThreadOutputService)에 남긴다.
 *               같은 턴에서 같은 계획이 다시 불리면 요청 키가 같아 워커를 부르기 전에 이미 남긴 출력을 돌려주므로, 재시도나 중복
 *               호출에도 파일이 하나만 남는다. 결과는 모델에 돌려줄 문장이다 — 등록에 성공했을 때만 파일을 만들었다고 말하게 하려고
 *               실패 문장은 모두 FAILED로 시작하고, 고장 원인(주소·키)은 넣지 않는다.
 *               워커 파일을 만든 뒤 출력 등록이 실패하면 파일만 저장소에 남는다 — 정리는 하지 않고 모델에 실패를 알린다.
 */
@Component
public class ThreadDocumentCreation {
	private static final Logger log = LoggerFactory.getLogger(ThreadDocumentCreation.class);
	static final String DOCUMENTS_PATH = "/api/v1/documents";
	static final String FAILED = "문서를 만들지 못했습니다(아무 파일도 등록하지 않음). ";
	/** 한 번의 답변에서 서로 다른 계획으로 만들 수 있는 문서 수. */
	static final int MAX_PER_TURN = 3;
	/** 워커의 JSON 요청 크기 한도(document-dsl.md)와 맞춘다. */
	static final int MAX_REQUEST_BYTES = 1_048_576;
	private static final int MAX_FILE_NAME_STEM = 100;

	/** registered는 이 호출 결과로 방에 출력이 남아 있다는 뜻이다(이미 남긴 건을 다시 돌려준 경우 포함). */
	record Result(boolean registered, String message) {
		static Result failed(String reason) {
			return new Result(false, FAILED + reason);
		}
	}

	private final ThreadOutputService outputs;
	private final ObjectMapper json;
	private final WebClient client;
	private final String apiKey;

	public ThreadDocumentCreation(ThreadOutputService outputs, ObjectMapper json, WebClient.Builder builder,
			@Value("${app.document.worker-url:http://localhost:8100}") String workerUrl,
			@Value("${app.document.internal-api-key:}") String apiKey) {
		this.outputs = outputs;
		this.json = json;
		this.apiKey = apiKey;
		this.client = builder.clone().baseUrl(workerUrl).build();
	}

	/**
	 * turnKey는 같은 답변(턴)을 가리키는 값이고, turnKeys는 그 턴에서 이미 만든 계획의 요청 키 모음이다(턴마다 도구가 하나씩
	 * 들고 있다). 호출하는 쪽이 방 접근을 확인하지 않아도 되도록 쓰기 권한은 여기서 확인한다.
	 */
	public Result create(UUID thread, CurrentActor actor, String turnKey, ThreadDocumentPlan plan, Set<UUID> turnKeys) {
		String problem = plan == null ? "문서 계획을 넘기세요." : plan.problem();
		if (problem != null) return Result.failed(problem);
		UUID requestKey = requestKey(turnKey, plan);
		// 개수 확인과 자리 선점을 한 번에 잠가, 다른 계획의 동시 호출이 둘 다 통과해 상한을 넘기지 않게 한다.
		boolean reserved;
		synchronized (turnKeys) {
			reserved = turnKeys.add(requestKey);
			if (reserved && turnKeys.size() > MAX_PER_TURN) {
				turnKeys.remove(requestKey);
				return Result.failed("한 번의 답변에서는 문서를 최대 " + MAX_PER_TURN
						+ "개까지 만들 수 있습니다. 새 파일은 만들지 않았으니 이미 만든 문서만 알리세요.");
			}
		}
		try {
			UUID tenant = outputs.writableTenant(thread, actor);
			Optional<ThreadOutputView> existing = outputs.findByRequestKey(thread, requestKey);
			if (existing.isPresent())
				return new Result(true, "같은 내용의 '" + existing.get().fileName() + "'을 이미 방의 출력에 등록했습니다. "
						+ "새 파일은 만들지 않았습니다. " + guide());
			String fileName = fileName(plan);
			byte[] request = json.writeValueAsBytes(request(plan, fileName));
			if (request.length > MAX_REQUEST_BYTES) {
				forget(turnKeys, requestKey, reserved);
				return Result.failed("문서가 너무 큽니다. 내용을 줄여 다시 부르세요.");
			}
			JsonNode created = post(tenant, requestKey, request);
			String objectKey = created.path("objectKey").asString("");
			String createdName = created.path("fileName").asString(fileName);
			long size = created.path("size").asLong(0);
			ThreadOutputView view = register(thread, requestKey, actor, createdName, plan.normalizedFormat(), size, objectKey);
			return new Result(true, "'" + view.fileName() + "'을 방의 출력에 등록했습니다. " + guide());
		} catch (Rejected rejected) {
			forget(turnKeys, requestKey, reserved);
			log.info("문서 생성 요청이 거절됐다 threadId={} reason={}", thread, rejected.getMessage());
			return Result.failed(rejected.getMessage());
		} catch (ResponseStatusException refused) {
			forget(turnKeys, requestKey, reserved);
			log.warn("문서를 만들 수 없는 방이다 threadId={} status={}", thread, refused.getStatusCode().value());
			return Result.failed(refused.getStatusCode().value() == HttpStatus.CONFLICT.value()
					? "이 방은 지금 문서를 만들 수 없습니다(잠기거나 보관된 방)." : "이 방에서는 문서를 만들 수 없습니다.");
		} catch (RuntimeException error) {
			forget(turnKeys, requestKey, reserved);
			log.error("문서 만들기 실패 threadId={}", thread, error);
			return Result.failed("문서 저장소 문제입니다. 잠시 뒤 다시 요청해 달라고 알리세요.");
		}
	}

	/** 선점했지만 끝내 등록하지 못한 자리를 돌려줘, 실패한 시도가 한 턴의 개수 한도를 깎아 먹지 않게 한다. */
	private static void forget(Set<UUID> turnKeys, UUID requestKey, boolean reserved) {
		if (reserved) synchronized (turnKeys) { turnKeys.remove(requestKey); }
	}

	private static String guide() {
		return "사용자에게 문서 패널의 출력 구역에서 내려받을 수 있다고 알리세요. 파일 이름은 위에 나온 그대로 쓰세요.";
	}

	/** 같은 방·같은 턴의 같은 계획이면 같은 키다. 다른 턴에서 같은 계획을 보내도 키가 달라 서로의 출력을 가로채지 않는다. */
	UUID requestKey(String turnKey, ThreadDocumentPlan plan) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(plan));
			return UUID.nameUUIDFromBytes((turnKey + "|" + HexFormat.of().formatHex(digest)).getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** 제목으로 파일 이름을 만든다. 경로·제어·방향 제어 문자와 앞뒤 점을 없애 워커의 basename 규칙을 지킨다. */
	static String fileName(ThreadDocumentPlan plan) {
		String stem = plan.title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\u061c\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]+", " ")
				.replaceAll("\\s+", " ").strip().replaceAll("^\\.+|\\.+$", "").strip();
		if (stem.length() > MAX_FILE_NAME_STEM) stem = stem.substring(0, MAX_FILE_NAME_STEM).strip();
		if (stem.isEmpty()) stem = "문서";
		return stem + plan.extension();
	}

	static Map<String, Object> request(ThreadDocumentPlan plan, String fileName) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("documentType", "DOCX");
		request.put("outputFormat", plan.normalizedFormat());
		request.put("fileName", fileName);
		request.put("title", plan.title());
		request.put("templateData", Map.of());
		request.put("operations", plan.operations());
		request.put("metadata", Map.of("source", "bff"));
		return request;
	}

	private JsonNode post(UUID tenant, UUID requestKey, byte[] request) {
		if (apiKey.isBlank()) throw new IllegalStateException("문서 워커 내부 API key(app.document.internal-api-key)가 비어 있다");
		try {
			String body = client.post().uri(DOCUMENTS_PATH)
					.header(ThreadSourcePaths.API_KEY_HEADER, apiKey).header(ThreadSourcePaths.TENANT_HEADER, tenant.toString())
					.header("X-Request-Id", requestKey.toString())
					.contentType(MediaType.APPLICATION_JSON).bodyValue(request)
					.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(60));
			if (body == null) throw new IllegalStateException("문서 생성 응답이 비어 있다");
			return json.readTree(body);
		} catch (WebClientResponseException response) {
			if (response.getStatusCode().value() == 422 || response.getStatusCode().value() == 400) throw rejected(response);
			throw response;
		}
	}

	/** 다른 요청이 같은 키로 먼저 남겼으면(동시 호출) 그 건을 돌려준다. 이쪽 파일은 저장소에만 남는다. */
	private ThreadOutputView register(UUID thread, UUID requestKey, CurrentActor actor, String fileName, String kind,
			long size, String objectKey) {
		try {
			return outputs.register(thread, requestKey, actor.userId(), fileName, kind, size, objectKey);
		} catch (IllegalStateException conflict) {
			return outputs.findByRequestKey(thread, requestKey).orElseThrow(() -> conflict);
		}
	}

	/** 워커가 거절한 계획(형식·한도)을 모델이 고쳐 다시 부를 수 있는 문장으로 바꾼다. 상세는 워커의 검증 문구다. */
	private Rejected rejected(WebClientResponseException response) {
		String detail = "";
		try {
			detail = json.readTree(response.getResponseBodyAsString()).path("message").asString("");
		} catch (RuntimeException unreadable) {
			// 본문이 JSON이 아니면 상세 없이 알린다.
		}
		if (detail.length() > 200) detail = detail.substring(0, 200);
		return new Rejected("문서 계획이 문서 서비스의 검증을 통과하지 못했습니다" + (detail.isBlank() ? "" : "(" + detail + ")")
				+ ". 내용을 고치거나 줄여 다시 부르세요.");
	}

	private static final class Rejected extends RuntimeException {
		Rejected(String message) {
			super(message, null, false, false);
		}
	}
}
