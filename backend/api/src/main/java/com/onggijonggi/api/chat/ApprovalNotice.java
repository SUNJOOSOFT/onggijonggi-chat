package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ApprovalNotice.java
 * Description : 협업방 대화 흐름에 남는 승인 요청·처리 줄의 내용(#407). SYSTEM 메시지의 pyl_json에 담긴다.
 *               표시 이름은 담지 않는다 — 입퇴장 줄(MembershipNotice)처럼 조회할 때 subject로 다시 찾아, 이름이 바뀌면
 *               지난 줄도 최신 이름을 따른다. 줄은 방 전체가 보므로 요청한 메시지의 내용은 담지 않는다.
 * @param event 요청이 생겼는지, 승인·반려로 처리됐는지
 * @param approvalId 승인 요청 건(thr_apv)의 id
 * @param requesterSubject 요청한 사람의 Keycloak subject
 * @param targetSubject 승인권자로 지정된 사람의 Keycloak subject
 */
public record ApprovalNotice(Event event, UUID approvalId, String requesterSubject, String targetSubject) {

	private static final Logger log = LoggerFactory.getLogger(ApprovalNotice.class);

	public enum Event {
		REQUESTED, APPROVED, REJECTED
	}

	String toPylJson(ObjectMapper objectMapper) {
		return objectMapper.writeValueAsString(new Payload(this));
	}

	/** 승인 줄이 아닌 pyl_json(입퇴장 줄, 역할별 에이전트 표시 등)이나 비어 있으면 null이다. */
	static ApprovalNotice fromPylJson(ObjectMapper objectMapper, String pylJson) {
		if (pylJson == null || pylJson.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(pylJson, Payload.class).approval();
		} catch (RuntimeException e) {
			log.error("저장된 승인 줄 역직렬화 실패", e);
			return null;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Payload(ApprovalNotice approval) {
	}

}
