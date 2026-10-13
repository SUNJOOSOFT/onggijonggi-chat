package com.onggijonggi.api.chat;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : ChatStreamRequest.java
 * Description : LlmChatStreamService.streamChat()의 입력. ThreadMessageDispatcher가 WS로 받은
 *               발화와 DB에서 조회한 문맥을 조립해 이 모양으로 넘긴다(이슈 #164로 REST 경로는
 *               제거됨 — 지금은 이 조립 하나뿐이라 마지막 role은 항상 user다).
 * @param sessionId 대상 Thread id
 * @param modelId 게이트웨이 model_list의 model_name(별칭). 화면에서 고른 값이 그대로 오고,
 *                유효성은 게이트웨이가 판정한다(LlmChatStreamService 참조)
 * @param messages 전체 대화 이력({role, content}만 포함하는 최소 스키마)
 * @param timeZone 턴을 부른 발화자의 브라우저 시간대(IANA 이름). null이거나 틀리면 현재 시각 도구가
 *                 서버 기본값을 쓴다(CurrentTimeTool 참조)
 * @param documentTool 이 턴의 방 문서 검색 도구. 검색할 고정 문서가 없는 방이면 null이고 도구를 붙이지 않는다
 */
public record ChatStreamRequest(UUID sessionId, String modelId, List<ChatMessage> messages, String timeZone,
		ThreadDocumentTool documentTool) {

	/** 방 문서 검색 도구 없이 만든다. */
	public ChatStreamRequest(UUID sessionId, String modelId, List<ChatMessage> messages, String timeZone) {
		this(sessionId, modelId, messages, timeZone, null);
	}
}
