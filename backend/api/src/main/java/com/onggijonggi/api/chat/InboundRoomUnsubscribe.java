package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundRoomUnsubscribe.java
 * Description : 이 커넥션으로 그 방의 프레임을 그만 받겠다고 알린다(이슈 #160).
 *
 *               그 방에서만 빠지고 커넥션은 유지한다(이슈 #161). 이 커넥션이 그 방의 마지막 연결이었으면
 *               소켓이 끊길 때와 똑같이 방이 닫힌다(흐르던 AI 턴 취소). 듣고 있지도 않은 방의 해지는
 *               할 일이 없어 조용히 넘어간다.
 *
 * @param threadId 그만 들을 방
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundRoomUnsubscribe(UUID threadId) implements InboundFrame {
}
