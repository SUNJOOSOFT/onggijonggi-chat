package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundRoomUnsubscribe.java
 * Description : 이 커넥션으로 그 방의 프레임을 그만 받겠다고 알린다(이슈 #160).
 *
 *               {@link InboundRoomSubscribe}와 범위가 같지만 판정 방향은 반대다. 커넥션이 든 방의
 *               해지는 지금 구조에서 곧 연결 종료와 같은 뜻이라 받아줄 수 없어 NOT_SUPPORTED로
 *               돌려주고(끊고 싶으면 소켓을 닫으면 된다), 듣고 있지도 않은 방의 해지는 할 일이 없어
 *               조용히 넘어간다.
 *
 * @param threadId 그만 들을 방
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundRoomUnsubscribe(UUID threadId) implements InboundFrame {
}
