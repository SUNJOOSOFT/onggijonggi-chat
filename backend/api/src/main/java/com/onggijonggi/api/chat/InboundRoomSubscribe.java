package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundRoomSubscribe.java
 * Description : 이 커넥션으로 그 방의 프레임을 받겠다고 알린다(이슈 #160).
 *
 *               이번 범위는 계약과 파싱까지다. 지금은 경로(/api/ws/{threadId})가 커넥션의 방을
 *               고정하므로, 그 방을 가리키는 구독은 이미 이뤄진 상태라 조용히 넘어가고 다른 방을
 *               가리키면 NOT_SUPPORTED로 돌려준다. 실제 멀티플렉싱은 #161이 붙인다.
 *
 * @param threadId 듣기 시작할 방
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundRoomSubscribe(UUID threadId) implements InboundFrame {
}
