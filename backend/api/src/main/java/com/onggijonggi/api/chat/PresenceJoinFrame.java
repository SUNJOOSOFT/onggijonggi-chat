package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : PresenceJoinFrame.java
 * Description : 참여자가 방에 입장했음을 알리는 협업 이벤트 프레임(이슈 #25). 대칭 이벤트는
 *               {@link PresenceLeaveFrame}이다. 입장 본인에게는 보내지 않는다 — 이미 방에 있던
 *               참여자에게만 나가므로, 방의 첫 입장자는 이 프레임을 발생시키지 않는다.
 *
 *               사람을 subject와 표시 이름으로 가리킨다(이슈 #130). 내부 app_user.id를 싣던
 *               이전 계약은 참여자 관리 API와 식별자 체계가 어긋났다.
 */
public record PresenceJoinFrame(UUID sessionId, String subject, String displayName) implements WsFrame {
}
