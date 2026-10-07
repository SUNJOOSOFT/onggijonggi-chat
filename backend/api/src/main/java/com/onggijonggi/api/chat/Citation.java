package com.onggijonggi.api.chat;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : Citation.java
 * Description : 근거 인용 문서 한 건. ChatAnswerFrame.citations의 원소 타입이다. 프론트엔드
 *               CitationsResponse 타입(frontend/lib/api/chat.ts)의 citation 항목과 필드
 *               구성이 같다.
 */
public record Citation(String docId, String title, String snippet, double score) {

	private static final Logger log = LoggerFactory.getLogger(Citation.class);

	/**
	* Msg.srcJson(이슈 #347)을 역직렬화한다. 실시간 스트리밍 때 저장한 것과 같은 값을 이력 조회·
	* 재연결 양쪽에서 똑같이 복원하기 위한 공용 지점이다. null·빈 문자열이거나 역직렬화가 깨지면
	* 조용히 빈 리스트로 — 과거 메시지 하나의 근거를 못 읽는다고 이력 조회 전체가 실패하면 안 된다.
	*/
	static List<Citation> fromSrcJson(ObjectMapper objectMapper, String srcJson) {
		if (srcJson == null || srcJson.isBlank()) {
			return List.of();
		}
		try {
			return List.of(objectMapper.readValue(srcJson, Citation[].class));
		} catch (RuntimeException e) {
			log.error("저장된 citations 역직렬화 실패", e);
			return List.of();
		}
	}

}
