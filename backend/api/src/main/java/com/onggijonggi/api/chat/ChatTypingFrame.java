package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ChatTypingFrame.java
 * Description : 같은 방의 누군가가 입력 중이거나 입력을 멈췄음을 알리는 협업 이벤트 프레임. 클라이언트의
 *               {@link InboundChatTyping}을 받아 서버가 사람을 채워 방송한다. 저장하지 않고, 입력한 본인
 *               (같은 subject의 다른 탭 포함)에게는 보내지 않는다.
 *
 *               사람을 가리키는 방식은 {@link PresenceJoinFrame}과 같다(이슈 #130).
 */
public record ChatTypingFrame(UUID threadId, String subject, String displayName, boolean active) implements WsFrame {
}
