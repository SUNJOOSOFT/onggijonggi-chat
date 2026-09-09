package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : MsgItem.java
 * Description : GET /api/collab/threads/{threadId}/messages 응답 항목.
 * @param id 메시지 id
 * @param seq 스레드 내 순서(정본, created_at 아님)
 * @param athKind HUMAN/AGENT/SYSTEM
 * @param status PENDING/COMPLETE/DENIED/FAILED/CANCELLED
 * @param content 메시지 본문(PENDING이면 빈 문자열)
 * @param authorDisplayName HUMAN 메시지 작성자의 표시 이름. DB에 스냅샷을 남기지 않고 요청마다
 *        Keycloak을 다시 조회하므로, 이름이 바뀌면 과거 메시지도 최신 이름을 따라간다(이슈 #147).
 *        AGENT·SYSTEM은 작성자가 없어 null이다.
 * @param createdAt 메시지 생성 시각
 * @param completedAt 완료 시각(PENDING이면 null)
 */
public record MsgItem(
		UUID id,
		long seq,
		String athKind,
		String status,
		String content,
		String authorDisplayName,
		Instant createdAt,
		Instant completedAt
) {

	static MsgItem from(Msg msg, String authorDisplayName) {
		return new MsgItem(msg.getId(), msg.getSeq(), msg.getAthKind().name(), msg.getStatus().name(),
				msg.getContent(), authorDisplayName, msg.getCreatedAt(), msg.getCompletedAt());
	}

}
