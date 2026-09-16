package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Thr;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : ChatSessSummary.java
 * Description : GET /api/chat/sessions 응답 항목 — 메시지 본문은 뺀 세션 메타데이터만.
 * @param id 세션 id
 * @param title 세션 제목
 * @param createdAt 세션 생성 시각
 * @param updatedAt 세션 갱신 시각
 */
public record ChatSessSummary(
		UUID id,
		String title,
		Instant createdAt,
		Instant updatedAt
) {

	static ChatSessSummary from(Thr thread) {
		return new ChatSessSummary(thread.getId(), thread.getTitle(), thread.getCreatedAt(), thread.getUpdatedAt());
	}

}
