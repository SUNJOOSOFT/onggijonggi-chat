package com.onggijonggi.api.chat;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : PresenceSnapshotFrame.java
 * Description : 방에 붙는 순간 그 연결 하나에만 내려보내는 참여자 명단(이슈 #26). 입퇴장
 *               이벤트({@link PresenceJoinFrame}·{@link PresenceLeaveFrame})가 "방금 일어난 일"을
 *               나른다면 이 프레임은 "지금 상태"를 나른다.
 *
 *               본인을 포함한다. 자기 입장은 자기가 받지 않는 설계라 본인을 빼면 클라이언트가
 *               스스로를 목록에 넣을 방법이 없다.
 *
 *               입장 이벤트를 되풀이하는 방식 대신 타입을 따로 둔 이유는 #111이 입퇴장을 대화
 *               흐름의 시스템 메시지로 그리기 때문이다. 명단 재생과 실제 입장이 같은 타입이면
 *               방에 들어갈 때마다 이미 있던 사람들이 방금 들어온 것처럼 보인다.
 */
public record PresenceSnapshotFrame(UUID sessionId, List<PresenceParticipant> participants) implements WsFrame {
}
