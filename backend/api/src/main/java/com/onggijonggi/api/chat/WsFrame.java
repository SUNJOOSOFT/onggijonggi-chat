package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Class Name : WsFrame.java
 * Description : WebSocket 메시지 프레임 봉투. GitHub 이슈 #8([협업채팅] 메시지 프레임 프로토콜)의
 *               03·CORE ↔ 01·CLIENT 계약 — type 태그로 채팅 응답과 협업 이벤트를 한 커넥션에
 *               멀티플렉싱한다. chat.answer는 이슈 #49에서 chat.token/chat.done/(제안 단계였던)
 *               chat.citation을 흡수해 확정된 스펙이다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = ChatAnswerFrame.class, name = "chat.answer"),
		@JsonSubTypes.Type(value = PresenceJoinFrame.class, name = "presence.join"),
		@JsonSubTypes.Type(value = PresenceLeaveFrame.class, name = "presence.leave"),
		@JsonSubTypes.Type(value = PresenceSnapshotFrame.class, name = "presence.snapshot"),
		@JsonSubTypes.Type(value = ChatMessageFrame.class, name = "chat.message"),
		@JsonSubTypes.Type(value = ErrorFrame.class, name = "error")
})
public sealed interface WsFrame
		permits ChatAnswerFrame, PresenceJoinFrame, PresenceLeaveFrame, PresenceSnapshotFrame,
		ChatMessageFrame, ErrorFrame {
}
