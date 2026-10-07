package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * Class Name : InboundChatMessage.java
 * Description : 참여자가 올려보내는 발화(이슈 #160에서 model·clientMsgId·turnId를 더했다).
 *
 *               식별자 둘은 클라이언트가 보내기 전에 만들고, 서버는 저장하지 않은 채 되돌려준다.
 *               - clientMsgId: 이 메시지의 임시 id. chat.message 에코에 돌려준다 — 먼저 그린 말풍선을
 *                 서버 메시지로 바꿀 때의 매칭 키다. 서버는 이 값을 해석하지 않고 그대로 돌려주기만
 *                 하는 불투명 문자열로 다룬다(이슈 #224) — {@code useChat}이 만드는 값이 nanoid라
 *                 UUID 형식이 아니다. turnId처럼 서버가 실제로 파싱·비교하는 값이 아니라는 뜻이다.
 *               - turnId: 이 발화가 부를 수 있는 AI 턴의 id. 서버가 턴을 만들 때만 쓰고(협업방은
 *                 {@code @AI} 멘션이 있을 때), 에코와 그 턴의 chat.answer·chat.queued에 돌려준다.
 *                 클라이언트는 에코가 오기 전에도 이 값으로 취소할 수 있다. 턴을 만들지 않는 발화에도
 *                 실어 보낸다 — 턴을 만들지는 서버가 정하므로 클라이언트가 미리 가를 필요가 없다.
 *                 클라이언트가 만든 값이라 서버는 "이 커넥션이 보낸 발화"의 턴에서만 인정한다.
 *               둘 다 생략할 수 있다. 생략하면 에코에 null이 실리고, turnId가 없는 턴은 취소할 수 없다.
 *
 *               model은 이 발화가 턴을 부를 때 쓸 게이트웨이 모델 별칭이다. 비우면 서버 기본값
 *               (app.thread.ai.model)을 쓴다. 서버가 미리 검증하지 않는다 — 어떤 별칭이 살아 있는지
 *               아는 곳은 게이트웨이뿐이고, 없는 별칭은 게이트웨이가 거절해 MODEL_UNAVAILABLE로 흐른다.
 *
 *               threadId는 커넥션이 여러 방을 나르게 되면서 더했다(이슈 #161) — 경로가 방을 고정하던
 *               때는 필요 없었다. 이 커넥션이 구독한 방이어야 한다.
 *
 *               attachmentIds는 POST /api/attachments로 미리 올린 첨부다. 본인이 올렸고 아직 다른
 *               발화에 실리지 않은 것만 받는다. 첨부가 있으면 content가 비어도 된다.
 *
 *               timeZone은 보낸 사람 브라우저의 시간대(IANA 이름, 예: Asia/Seoul)다. 이 발화가 부른 턴의
 *               현재 시각 도구가 쓴다. 서버가 미리 거르지 않는다 — 없거나 해석할 수 없으면 도구가 서버
 *               기본값을 쓴다(CurrentTimeTool).
 *
 * @param threadId 발화할 방
 * @param content 발화 원문
 * @param model 이 턴에 쓸 게이트웨이 모델 별칭. null이면 서버 기본값
 * @param clientMsgId 클라이언트가 만든 임시 메시지 id. 서버는 형식을 가리지 않는다(이슈 #224)
 * @param turnId 클라이언트가 만든 턴 식별자
 * @param attachmentIds 이 발화에 실을 첨부 id. 없으면 null이나 빈 배열
 * @param timeZone 보낸 사람 브라우저의 시간대. null이면 서버 기본값
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundChatMessage(UUID threadId, String content, String model, String clientMsgId, UUID turnId,
		List<UUID> attachmentIds, String timeZone)
		implements InboundFrame {
}
