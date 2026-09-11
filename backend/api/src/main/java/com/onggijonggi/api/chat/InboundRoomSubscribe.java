package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Class Name : InboundRoomSubscribe.java
 * Description : 이 커넥션으로 그 방의 프레임을 받겠다고 알린다(이슈 #160).
 *
 *               계약과 파싱만 이번 범위다. 지금은 경로(/api/ws/{threadId})가 커넥션의 방을
 *               고정하므로 이 커넥션이 이미 든 방을 가리키는 구독은 할 일이 없고, 다른 방을
 *               가리키면 아직 받아줄 수 없어 FORBIDDEN으로 돌려준다 — 실제 멀티플렉싱은
 *               #161이 붙인다. 계약을 먼저 여는 이유는 그 이슈가 프레임 계약과 배선을 한꺼번에
 *               바꾸지 않게 하려는 것이다(계약은 서버 InboundFrame과 프론트 frames.ts를 동시에
 *               고쳐야 하는 작업이라 따로 떼는 편이 리뷰도 되돌리기도 싸다).
 *
 * @param threadId 듣기 시작할 방
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundRoomSubscribe(UUID threadId) implements InboundFrame {
}
