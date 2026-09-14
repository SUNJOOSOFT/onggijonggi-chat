package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundChatCancel.java
 * Description : 진행 중이거나 기다리는 {@code @AI} 턴을 멈춘다(이슈 #160).
 *
 *               HTTP에서는 연결을 끊는 것이 곧 취소였지만 WS는 커넥션이 유지된다 — 이 프레임이
 *               없으면 화면의 중단 버튼이 표시만 멈추고 서버는 계속 생성한다.
 *
 *               지목은 그 턴을 부른 발화의 turnId로 한다. 서버가 만든 id(사람 메시지 msgId, AGENT
 *               msgId)는 에코나 첫 답변 프레임이 와야 알 수 있어, 그 전에 누른 중단을 받을 수 없기
 *               때문이다. threadId는 커넥션이 여러 방을 나르게 되면(#161) 어느 방의 턴인지 가르는 값이다.
 *
 *               멈출 턴이 없으면(이미 끝났거나, 이 커넥션이 부른 턴이 아니거나) 조용히 넘어간다. 스트림이
 *               막 끝난 직후의 취소는 흔한 경합이라 오류로 돌려주면 화면이 이유 없이 시끄러워진다.
 *
 * @param threadId 멈출 턴이 있는 방
 * @param turnId 멈출 턴을 부른 발화의 turnId
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundChatCancel(UUID threadId, UUID turnId) implements InboundFrame {
}
