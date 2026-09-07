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
 * @param createdAt 메시지 생성 시각
 * @param completedAt 완료 시각(PENDING이면 null)
 */
public record MsgItem(
		UUID id,
		long seq,
		String athKind,
		String status,
		String content,
		Instant createdAt,
		Instant completedAt
) {

	static MsgItem from(Msg msg) {
		return new MsgItem(msg.getId(), msg.getSeq(), msg.getAthKind().name(), msg.getStatus().name(),
				msg.getContent(), msg.getCreatedAt(), msg.getCompletedAt());
	}

}
