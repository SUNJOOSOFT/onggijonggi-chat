package com.onggijonggi.common.chat.domain;

/**
 * Class Name : MsgStatus.java
 * Description : msg.status 값(V11__message.sql). PENDING은 AGENT 행만 시작할 수 있다
 *               (msg_pending_only_for_agent CHECK). completed_at의 유무가 PENDING 여부와
 *               정확히 반대로 맞아야 한다(msg_completed_at_matches_status CHECK).
 */
public enum MsgStatus {
	PENDING,
	COMPLETE,
	DENIED,
	FAILED,
	CANCELLED
}
