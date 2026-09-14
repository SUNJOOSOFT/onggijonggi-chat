package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Class Name : WsFrame.java
 * Description : WebSocket 메시지 프레임 봉투. GitHub 이슈 #8([협업채팅] 메시지 프레임 프로토콜)의
 *               03·CORE ↔ 01·CLIENT 계약 — type 태그로 채팅 응답과 협업 이벤트를 한 커넥션에
 *               멀티플렉싱한다. chat.answer는 이슈 #49에서 chat.token/chat.done/(제안 단계였던)
 *               chat.citation을 흡수해 확정된 스펙이다.
 *
 *               모든 프레임의 threadId는 그 프레임이 속한 방이다. null이면 특정 방이 아니라 커넥션
 *               전체에 대한 것이다(연결 수립 실패 등). 이슈 #160 전에는 이름이 sessionId였는데, 1:1
 *               세션을 떠올리게 해 방을 가르는 키라는 실체와 어긋나서 바꿨다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = ChatAnswerFrame.class, name = "chat.answer"),
		@JsonSubTypes.Type(value = PresenceJoinFrame.class, name = "presence.join"),
		@JsonSubTypes.Type(value = PresenceLeaveFrame.class, name = "presence.leave"),
		@JsonSubTypes.Type(value = PresenceSnapshotFrame.class, name = "presence.snapshot"),
		@JsonSubTypes.Type(value = ChatMessageFrame.class, name = "chat.message"),
		@JsonSubTypes.Type(value = SystemNoticeFrame.class, name = "system.notice"),
		@JsonSubTypes.Type(value = ErrorFrame.class, name = "error"),
		@JsonSubTypes.Type(value = ParticipantChangedFrame.class, name = "participant.changed")
})
public sealed interface WsFrame
		permits ChatAnswerFrame, PresenceJoinFrame, PresenceLeaveFrame, PresenceSnapshotFrame,
		ChatMessageFrame, SystemNoticeFrame, ErrorFrame, ParticipantChangedFrame {
}
