package com.onggijonggi.api.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : ThreadApprovalView.java
 * Description : 협업방 승인 요청 건 하나의 API 응답(#407). 요청자와 대상은 Keycloak subject로 가리킨다.
 *               표시 이름은 싣지 않는다 — 화면이 참여자 명단에서 subject로 찾아, 이름이 바뀌면 지난 건도 최신 이름을 따른다.
 * @param status PENDING·APPROVED·REJECTED
 * @param reason 반려 사유. 반려가 아니거나 사유를 적지 않았으면 null이다
 * @param completedAt 처리한 시각. 대기 중이면 null이다
 * @param canDecide 지금 이 요청자가 승인·반려할 수 있는지(지정된 사람이고 대기 중이며 방에 쓸 수 있을 때)
 */
public record ThreadApprovalView(UUID id, UUID messageId, String requesterSubject, String targetSubject, String status,
		String reason, Instant createdAt, Instant completedAt, boolean canDecide) {
}
