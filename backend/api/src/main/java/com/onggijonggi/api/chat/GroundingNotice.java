package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Class Name : GroundingNotice.java
 * Description : 방 문서 검색(#344) 결과가 FOUND가 아닐 때 ChatAnswerFrame에 함께 보내는 안내
 *               (이슈 #353). 답변 본문에 문구를 끼워 넣지 않는 이유는, 그러면 그 문구가 대화
 *               이력·다음 턴의 LLM 문맥에 그대로 남기 때문이다 — ErrorFrame의 code처럼 화면이
 *               해석해서 그릴 기계가 읽는 값으로 분리한다. 프론트
 *               WsFrame(frontend/lib/transport/frames.ts)의 groundingNotice와 JSON 표현이
 *               정확히 같아야 한다.
 */
public enum GroundingNotice {

	@JsonProperty("no_evidence") NO_EVIDENCE,
	@JsonProperty("unavailable") UNAVAILABLE

}
