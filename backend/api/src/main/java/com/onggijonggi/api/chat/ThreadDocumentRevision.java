package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.common.document.ThreadSourcePaths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ThreadDocumentRevision.java
 * Description : AI가 부르는 개정본 만들기. 방의 고정 docx 원본을 문서 워커로 보내 지정한 문장만 바꾸고(서식 유지, 끝에 신구조문 대비표),
 *               결과를 요청한 사람 이름으로 방 문서에 등록한다(고정하지 않음 — 답변 검색에 섞이지 않게).
 *               방 접근·쓰기 권한은 ThreadDocumentService의 원본 읽기·등록과 같다. 결과는 모델에 돌려줄 문장이다 —
 *               원문을 찾지 못하면 모델이 원문을 확인해 다시 부를 수 있게 이유를 알려 준다. 고장 원인(주소·키)은 넣지 않는다.
 */
@Component
public class ThreadDocumentRevision {
	private static final Logger log = LoggerFactory.getLogger(ThreadDocumentRevision.class);
	static final String REVISIONS_PATH = "/api/v1/documents/revisions";
	static final String SUFFIX = "_개정안.docx";

	private final ThreadDocumentService documents;
	private final ObjectMapper json;
	private final WebClient client;
	private final String apiKey;

	public ThreadDocumentRevision(ThreadDocumentService documents, ObjectMapper json, WebClient.Builder builder,
			@Value("${app.document.worker-url:http://localhost:8100}") String workerUrl,
			@Value("${app.document.internal-api-key:}") String apiKey) {
		this.documents = documents;
		this.json = json;
		this.apiKey = apiKey;
		this.client = builder.clone().baseUrl(workerUrl)
				.codecs(config -> config.defaultCodecs().maxInMemorySize(ThreadDocumentService.MAX_FILE_BYTES * 3)).build();
	}

	public String revise(UUID thread, CurrentActor actor, String fileName, List<ThreadDocumentTool.Edit> edits) {
		if (edits == null || edits.isEmpty()) return "바꿀 문장을 하나 이상 넘기세요.";
		if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".docx"))
			return "docx 문서만 서식을 유지한 채 고칠 수 있습니다.";
		try {
			ThreadDocumentScope scope = documents.searchScope(thread, actor);
			List<ThreadDocumentScope.Target> targets = scope.targets().stream().filter(target -> target.fileName().equals(fileName)).toList();
			if (targets.isEmpty()) return "이 방에 고정된 문서 중 '" + fileName + "'이 없습니다. 문서 목록의 파일 이름을 그대로 쓰세요.";
			if (targets.size() > 1) return "이 방에 '" + fileName + "'이 여러 개 고정되어 있어 어느 문서를 고칠지 정할 수 없습니다. 사용자에게 하나만 고정해 달라고 하세요.";
			byte[] original = documents.original(thread, targets.get(0).document(), actor).bytes();
			byte[] revised = request(scope.tenant(), original, edits);
			String name = fileName.substring(0, fileName.length() - ".docx".length()) + SUFFIX;
			documents.upload(thread, UUID.randomUUID(), actor, name, revised);
			return "'" + fileName + "'에서 " + edits.size() + "곳을 고친 '" + name + "'을 방 문서에 등록했습니다. 끝에 신구조문 대비표를 붙였습니다. "
					+ "고정하지 않아 답변 검색에는 쓰이지 않습니다. 사용자에게 방 문서 패널에서 내려받을 수 있다고 알리세요.";
		} catch (Rejected rejected) {
			return rejected.getMessage();
		} catch (ResponseStatusException refused) {
			log.warn("개정본을 등록하지 못했다 threadId={} status={}", thread, refused.getStatusCode().value());
			return "이 방에는 지금 문서를 등록할 수 없어 개정본을 만들지 못했습니다.";
		} catch (RuntimeException error) {
			log.error("개정본 만들기 실패 threadId={}", thread, error);
			return "문서 저장소 문제로 개정본을 만들지 못했습니다. 잠시 뒤 다시 요청해 달라고 알리세요.";
		}
	}

	private byte[] request(UUID tenant, byte[] original, List<ThreadDocumentTool.Edit> edits) {
		if (apiKey.isBlank()) throw new IllegalStateException("문서 워커 내부 API key(app.document.internal-api-key)가 비어 있다");
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new ByteArrayResource(original) {
			@Override public String getFilename() { return "source.docx"; }
		});
		body.add("edits", json.writeValueAsString(edits));
		try {
			byte[] revised = client.post().uri(REVISIONS_PATH)
					.header(ThreadSourcePaths.API_KEY_HEADER, apiKey).header(ThreadSourcePaths.TENANT_HEADER, tenant.toString())
					.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(body)
					.retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(60));
			if (revised == null) throw new IllegalStateException("개정본 응답이 비어 있다");
			return revised;
		} catch (WebClientResponseException response) {
			if (response.getStatusCode().value() == 422 || response.getStatusCode().value() == 400) throw rejected(response);
			throw response;
		}
	}

	/** 워커가 거절한 수정(원문 없음·여러 곳·형식 오류)을 모델이 고쳐 다시 부를 수 있는 문장으로 바꾼다. */
	private Rejected rejected(WebClientResponseException response) {
		JsonNode error = json.readTree(response.getResponseBodyAsString());
		String code = error.path("code").asString("");
		String detail = error.path("message").asString("");
		return new Rejected(switch (code) {
			case "EDIT_NOT_FOUND" -> "원래 문장을 문서에서 찾지 못했습니다(" + detail + "). searchThreadDocuments로 원문을 확인해 "
					+ "한 문단 안의 문장을 그대로 옮겨 다시 부르세요.";
			case "EDIT_AMBIGUOUS" -> "원래 문장이 문서에 여러 번 나옵니다(" + detail + "). 한 곳만 가리키도록 앞뒤 글을 더 넣어 다시 부르세요.";
			case "UNPROCESSABLE_DOCUMENT" -> "원본을 docx로 읽을 수 없어 고치지 못했습니다.";
			default -> "수정 요청 형식이 맞지 않습니다(" + detail + ").";
		});
	}

	private static final class Rejected extends RuntimeException {
		Rejected(String message) {
			super(message, null, false, false);
		}
	}
}
