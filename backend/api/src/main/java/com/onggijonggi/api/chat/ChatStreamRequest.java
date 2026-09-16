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
 */
public record ChatStreamRequest(UUID sessionId, String modelId, List<ChatMessage> messages) {
}
