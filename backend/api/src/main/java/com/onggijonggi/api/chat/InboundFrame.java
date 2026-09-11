package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Class Name : InboundFrame.java
 * Description : 클라이언트 → 서버 프레임 봉투(이슈 #160). {@link WsFrame}(서버 → 클라이언트)과
 *               같은 {@code type} 태그 규약을 쓰되 타입 목록을 따로 든다 — 방향이 다르면 허용
 *               집합도 다르고, 한 봉투에 합치면 클라이언트가 서버 전용 타입을 보낼 수 있는지가
 *               타입만 봐서는 드러나지 않는다.
 *
 *               이 sealed 목록 자체가 화이트리스트다. 여기 없는 type은 역직렬화가 실패해
 *               MALFORMED_REQUEST로 떨어진다 — 다만 서버 전용 타입(chat.answer 등)은 그 전에
 *               CollabWebSocketHandler가 조용히 걸러낸다(이슈 #157). 둘을 가르는 이유는
 *               그쪽 주석에 있다.
 *
 *               프론트 계약은 frontend/lib/transport/frames.ts의 ClientFrame 유니온이 미러링한다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = InboundChatMessage.class, name = "chat.message"),
		@JsonSubTypes.Type(value = InboundChatCancel.class, name = "chat.cancel"),
		@JsonSubTypes.Type(value = InboundRoomSubscribe.class, name = "room.subscribe"),
		@JsonSubTypes.Type(value = InboundRoomUnsubscribe.class, name = "room.unsubscribe")
})
public sealed interface InboundFrame
		permits InboundChatMessage, InboundChatCancel, InboundRoomSubscribe, InboundRoomUnsubscribe {
}
