package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : MembershipNotice.java
 * Description : 협업방 대화 흐름에 남는 입장·퇴장 줄의 내용. SYSTEM 메시지의 pyl_json에 담긴다.
 *               접속(presence)이 아니라 참가 기록(thr_mbr)이 기준이다 — 방 목록과 방을 오가는 것은
 *               입퇴장이 아니고, 참가자가 되거나 참가가 끝난 순간만 한 번씩 남는다.
 *
 *               표시 이름은 담지 않는다. 사람 메시지처럼 조회할 때 subject로 다시 찾아, 이름이 바뀌면
 *               지난 줄도 최신 이름을 따른다(이슈 #147과 같은 이유).
 * @param event 입장인지 퇴장인지
 * @param subject 그 사람의 Keycloak subject
 */
public record MembershipNotice(Event event, String subject) {

	private static final Logger log = LoggerFactory.getLogger(MembershipNotice.class);

	public enum Event {
		JOINED, LEFT
	}

	String toPylJson(ObjectMapper objectMapper) {
		return objectMapper.writeValueAsString(new Payload(this));
	}

	/** 입퇴장 줄이 아닌 pyl_json(역할별 에이전트 표시 등)이나 비어 있으면 null이다. */
	static MembershipNotice fromPylJson(ObjectMapper objectMapper, String pylJson) {
		if (pylJson == null || pylJson.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(pylJson, Payload.class).membership();
		} catch (RuntimeException e) {
			log.error("저장된 입퇴장 줄 역직렬화 실패", e);
			return null;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Payload(MembershipNotice membership) {
	}

}
