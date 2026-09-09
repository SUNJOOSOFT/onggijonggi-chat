package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : PresenceLeaveFrame.java
 * Description : 참여자가 방에서 나갔음을 알리는 협업 이벤트 프레임(이슈 #25). {@link PresenceJoinFrame}과
 *               대칭이며, 나가는 본인에게는 보내지 않는다 — 받을 커넥션이 이미 닫히는 중이다.
 *               마지막 참여자가 나가면 방 자체가 사라지므로 이 프레임도 발생하지 않는다.
 *
 *               사람을 가리키는 방식은 {@link PresenceJoinFrame}과 같다(이슈 #130).
 */
public record PresenceLeaveFrame(UUID sessionId, String subject, String displayName) implements WsFrame {
}
