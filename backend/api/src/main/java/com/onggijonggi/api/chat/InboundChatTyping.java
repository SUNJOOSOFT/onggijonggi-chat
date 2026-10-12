package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundChatTyping.java
 * Description : 협업방에서 지금 입력 중인지 알리는 클라이언트 프레임. 서버는 저장하지 않고 같은 방의
 *               다른 사람에게 {@link ChatTypingFrame}으로만 전한다. 누가 입력 중인지는 이 프레임이 아니라
 *               커넥션의 사용자로 정한다 — 남의 이름으로 "입력 중"을 띄울 수 없게.
 *
 *               클라이언트는 입력하는 동안 몇 초에 한 번 active=true를 다시 보내고, 보내거나 입력창을
 *               비우면 active=false를 보낸다. 받는 쪽은 갱신이 끊기면 스스로 지운다 — false가 유실돼도
 *               "입력 중"이 남지 않게.
 *
 * @param threadId 입력 중인 방
 * @param active 입력 중이면 true, 멈췄으면 false
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundChatTyping(UUID threadId, boolean active) implements InboundFrame {
}
