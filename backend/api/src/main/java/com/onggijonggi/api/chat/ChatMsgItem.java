package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : ChatMsgItem.java
 * Description : GET /api/chat/sessions/{sessionId}/messages 응답 항목.
 * @param id 메시지 id
 * @param role user/assistant/system (클라이언트 계약과 동일한 소문자 값)
 * @param content 메시지 본문
 * @param createdAt 메시지 생성 시각
 */
public record ChatMsgItem(
		UUID id,
		String role,
		String content,
		Instant createdAt
) {

	static ChatMsgItem from(Msg msg) {
		return new ChatMsgItem(msg.getId(), switch (msg.getAthKind()) {
			case HUMAN -> "user";
			case AGENT -> "assistant";
			case SYSTEM -> "system";
		}, msg.getContent(), msg.getCreatedAt());
	}

}
