package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundRoomSubscribe.java
 * Description : 이 커넥션으로 그 방의 프레임을 받겠다고 알린다(이슈 #160).
 *
 *               커넥션 하나가 여러 방을 나르므로(이슈 #161) 방마다 이 프레임으로 구독을 건다. 참가자가
 *               아니면 FORBIDDEN을 그 방 threadId로 돌려주고 커넥션은 유지한다. 구독이 걸리면 그 방의
 *               presence.snapshot이 이 커넥션에 가장 먼저 온다 — 클라이언트는 이것을 구독 완료로 읽는다.
 *               이미 구독한 방이면 조용히 넘어간다.
 *
 *               재연결 뒤 구독 복구는 클라이언트 몫이다 — 서버는 끊긴 커넥션이 어느 방에 있었는지
 *               기억하지 않는다. 탭마다 보는 방이 달라 사용자 단위로 기억할 수 없다.
 *
 * @param threadId 듣기 시작할 방
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundRoomSubscribe(UUID threadId) implements InboundFrame {
}
