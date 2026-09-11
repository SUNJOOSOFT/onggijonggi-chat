package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundChatCancel.java
 * Description : 진행 중이거나 대기 중인 {@code @AI} 턴을 멈춘다(이슈 #160).
 *
 *               가리키는 값이 AGENT 메시지 id가 아니라 그 턴을 부른 <b>사람 메시지</b>의 id다.
 *               AGENT id·seq는 턴이 실제로 시작될 때 확정되므로(CollabMessageDispatcher.ActiveTurn),
 *               큐에서 차례를 기다리는 턴에는 아직 없다 — 그 값으로만 취소를 받으면 정작 기다리는
 *               동안에는 취소할 수 없다. 사람 메시지 id는 발화가 방송되는 순간
 *               (ChatMessageFrame.msgId) 보낸 사람에게도 그대로 돌아오므로 언제든 지목할 수 있다.
 *
 *               HTTP에서는 연결을 끊는 것이 곧 취소였지만 WS는 커넥션이 유지된다 — 이 프레임이
 *               없으면 화면의 중단 버튼이 표시만 멈추고 서버는 계속 생성한다.
 *
 * @param requestMsgId 취소할 턴을 부른 사람 메시지의 id
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundChatCancel(UUID requestMsgId) implements InboundFrame {
}
