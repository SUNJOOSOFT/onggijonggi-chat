package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundRoomUnsubscribe.java
 * Description : 이 커넥션으로 그 방의 프레임을 그만 받겠다고 알린다(이슈 #160).
 *               {@link InboundRoomSubscribe}와 대칭이며 이번 범위도 같다 — 계약과 파싱만이고
 *               실제 동작은 #161이 붙인다.
 *
 *               커넥션이 든 방 하나를 가리키는 해지는 지금 구조에서 곧 연결 종료와 같은 뜻이라
 *               받아주지 않는다 — 끊고 싶으면 소켓을 닫는 것이 이미 그 수단이다.
 *
 * @param threadId 그만 들을 방
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundRoomUnsubscribe(UUID threadId) implements InboundFrame {
}
