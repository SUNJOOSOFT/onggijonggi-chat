package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ParticipantChangedFrame.java
 * Description : 초대·제거·소유권 위임(#20)이 성공했을 때 그 방 구독자 전원에게 보내는 프레임(이슈
 *               #129). presence(#25·#26)는 "지금 접속해 있는가"를 다루고 이 프레임은 "이 방의
 *               참여자인가"가 바뀌었음을 다룬다 — 접속 중이 아닌 사람이 초대되는 경우처럼
 *               presence로는 표현되지 않는 변화도 있어 별도 타입으로 둔다.
 *
 *               제거당한 본인에게도 보낸다(연결을 끊기 전에) — 그래야 본인 화면도 다른 사람이
 *               바꾼 걸 폴링 없이 알 수 있다. WS 세션을 강제로 끊는 것은 #20에서 범위 밖으로 둔
 *               항목이라 여기서도 다루지 않는다.
 *
 *               대상이 지금 접속 중이 아닐 수 있어(초대 시나리오) presence처럼 연결이 들고 있는
 *               JWT claim을 재사용할 수 없다 — displayName은 KeycloakAdminClient로 조회한다
 *               (ParticipantView와 같은 패턴).
 * @param action 무엇이 바뀌었는지. 01·CLIENT는 지금 이 값으로 분기하지 않고 명단 재조회
 *               트리거로만 쓴다
 * @param subject 변경 대상의 Keycloak subject — INVITED/REMOVED는 그 사람, OWNER_TRANSFERRED는
 *               새 OWNER
 */
public record ParticipantChangedFrame(UUID sessionId, ParticipantChangeAction action, String subject,
		String displayName) implements WsFrame {
}
