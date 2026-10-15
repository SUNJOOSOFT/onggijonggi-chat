package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : RoomSubscribedFrame.java
 * Description : presence가 없는 방(DIRECT)의 구독이 걸렸다는 답(이슈 #382). 그 구독에게만 맨 앞에
 *               한 번 보낸다.
 *
 *               COLLAB 방은 {@link PresenceSnapshotFrame}이 같은 역할을 하지만 DIRECT에는 스냅샷이
 *               없어(이슈 #162) 클라이언트가 구독 완료를 알 길이 없었다. 선제 토큰 갱신은 새 소켓의
 *               구독이 다 걸린 뒤에야 옛 소켓을 닫고 새 소켓으로 갈아타므로, 답이 없으면 갈아타기가
 *               끝나지 않는다.
 */
public record RoomSubscribedFrame(UUID threadId) implements WsFrame {
}
