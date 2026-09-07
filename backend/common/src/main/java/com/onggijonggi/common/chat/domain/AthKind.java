package com.onggijonggi.common.chat.domain;

/**
 * Class Name : AthKind.java
 * Description : msg.ath_kind 값(V11__message.sql). HUMAN만 참여 기록(thr_mbr_id)을 참조한다 —
 *               msg_human_has_participant CHECK가 이 대응을 강제한다. AGENT·SYSTEM은 지금 아무
 *               컬럼도 참조하지 않는다(V11 마이그레이션 주석 참고).
 */
public enum AthKind {
	HUMAN,
	AGENT,
	SYSTEM
}
