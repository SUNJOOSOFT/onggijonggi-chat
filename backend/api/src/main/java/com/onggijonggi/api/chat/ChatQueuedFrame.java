package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ChatQueuedFrame.java
 * Description : 아직 시작하지 않은 {@code @AI} 턴의 상태를 방에 알린다(이슈 #160).
 *
 *               방마다 AI 턴은 한 번에 하나라(CollabMessageDispatcher) 앞 턴이 있으면 뒤 턴은
 *               기다린다. 이 신호가 없으면 화면은 "느린 응답"과 "대기 중"을 구분하지 못한다.
 *
 *               queued — 앞 턴이 있어 기다리기 시작했다. 턴이 실제로 시작되면 따로 알리지 않는다 —
 *               같은 turnId를 단 chat.answer가 오는 것이 곧 시작이다.
 *               cancelled — 시작하기 전에 취소됐다. 시작한 턴의 취소는 chat.answer(done)가 알린다.
 *
 *               방 전체에 보낸다. 턴을 부른 발화의 chat.message 에코에도 같은 turnId가 실려 있어,
 *               다른 참여자의 화면도 이 값으로 말풍선을 찾는다.
 *
 * @param turnId 이 턴을 부른 발화에 클라이언트가 실은 턴 식별자
 * @param status 기다리기 시작했는지, 시작 전에 취소됐는지
 */
public record ChatQueuedFrame(UUID threadId, UUID turnId, ChatQueuedStatus status) implements WsFrame {
}
