package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ChatMessageFrame.java
 * Description : 참여자 간 일반 대화 메시지 프레임. AI 호출 여부에 따른 라우팅 정책(단순
 *               브로드캐스트 vs LLM 파이프라인 호출)은 GitHub 이슈 #13(협업 채팅방 코어)에서
 *               결정 중이니 확정 여부는 그쪽을 확인한다.
 *
 *               작성자를 subject로 가리키고 표시 이름을 함께 싣는다(이슈 #130). 이전에는 내부
 *               app_user.id를 실었는데, 그 값은 참여자 관리 API가 쓰는 subject와 맞물리지 않아
 *               클라이언트가 "이 사람이 명단의 누구인지" 맞춰볼 수 없었다.
 *
 *               msgId·seq는 방송 직전에 확정한 값이고 이력(MsgItem)의 같은 필드와 짝이다
 *               (이슈 #190). 클라이언트는 이 둘로 REST 이력과 실시간 프레임을 이어붙이고,
 *               재접속 시 마지막 seq 이후만 따라잡는다. seq는 순서와 커서로만 쓰고 연속성은
 *               가정하지 않는다 — 블록 예약이 구멍을 남긴다.
 *
 *               clientMsgId·turnId는 보낸 사람이 발화에 실은 값을 그대로 돌려준 것이다(이슈 #160).
 *               보낸 사람의 화면은 먼저 그려둔 말풍선을 clientMsgId로 찾아 서버 msgId로 바꾸고, 방의
 *               모든 화면은 turnId로 이 발화와 그 턴의 chat.answer·chat.queued를 잇는다. 저장하지
 *               않으므로 이력(MsgItem)에는 없다.
 *
 * @param msgId 이 메시지의 id. 저장되는 msg 행의 id와 같은 값이다
 * @param clientMsgId 보낸 사람이 실은 임시 메시지 id. 없었으면 null
 * @param turnId 보낸 사람이 실은 턴 식별자. 없었으면 null
 * @param seq 방 안에서의 순서. 방 워커가 직렬로 꺼내므로 방송 순서와 일치한다
 * @param from 작성자의 Keycloak subject
 * @param fromDisplayName 작성자의 표시 이름
 */
public record ChatMessageFrame(UUID threadId, UUID msgId, UUID clientMsgId, UUID turnId, long seq, String from,
		String fromDisplayName, String content) implements WsFrame {
}
