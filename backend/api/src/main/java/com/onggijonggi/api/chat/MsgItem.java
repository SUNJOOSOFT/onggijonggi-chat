package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : MsgItem.java
 * Description : GET /api/collab/threads/{threadId}/messages 응답 항목.
 * @param id 메시지 id
 * @param seq 스레드 내 순서(정본, created_at 아님)
 * @param athKind HUMAN/AGENT/SYSTEM
 * @param status PENDING/COMPLETE/DENIED/FAILED/CANCELLED
 * @param content 메시지 본문(PENDING이면 빈 문자열)
 * @param authorSubject HUMAN 메시지 작성자의 Keycloak subject. WS 프레임의 from과 같은 값이라,
 *        이력과 실시간이 같은 사람을 같은 값으로 가리킨다(이슈 #190). AGENT·SYSTEM은 null이다.
 * @param authorDisplayName HUMAN 메시지 작성자의 표시 이름. DB에 스냅샷을 남기지 않고 요청마다
 *        Keycloak을 다시 조회하므로, 이름이 바뀌면 과거 메시지도 최신 이름을 따라간다(이슈 #147).
 *        AGENT·SYSTEM은 작성자가 없어 null이다.
 * @param createdAt 메시지 생성 시각
 * @param completedAt 완료 시각(PENDING이면 null)
 * @param attachments HUMAN 메시지에 실린 첨부. 없으면 빈 배열이다. WS chat.message의 같은 필드와 짝이다
 * @param citations 방 문서 검색으로 찾은 근거(이슈 #347). 근거 없이 답했거나 HUMAN·SYSTEM 메시지면
 *        빈 배열이다 — 실시간 스트리밍 DONE 프레임과 같은 값을 이력 조회에서도 복원한다
 */
public record MsgItem(
		UUID id,
		long seq,
		String athKind,
		String status,
		String content,
		String authorSubject,
		String authorDisplayName,
		Instant createdAt,
		Instant completedAt,
		List<MsgFileView> attachments,
		List<Citation> citations
) {

	static MsgItem from(Msg msg, String authorSubject, String authorDisplayName, List<MsgFileView> attachments,
			ObjectMapper objectMapper) {
		return new MsgItem(msg.getId(), msg.getSeq(), msg.getAthKind().name(), msg.getStatus().name(),
				msg.getContent(), authorSubject, authorDisplayName, msg.getCreatedAt(), msg.getCompletedAt(),
				attachments, Citation.fromSrcJson(objectMapper, msg.getSrcJson()));
	}

}
