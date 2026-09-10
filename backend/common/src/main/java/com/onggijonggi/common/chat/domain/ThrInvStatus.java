package com.onggijonggi.common.chat.domain;

/**
 * Class Name : ThrInvStatus.java
 * Description : thr_inv.status 값(이슈 #127). 초대를 끝낼 때 행을 지우지 않고 이 값으로 표시해,
 *               누가 언제 왜 초대됐고 그 초대가 어떻게 끝났는지를 남긴다 — ThrMbrStatus와 같은 방식이다.
 *
 *               PENDING은 활성도 종료도 아닌 대기 상태다. thr_mbr이 이 상태를 담지 못해
 *               (CHECK가 "ACTIVE가 아니면 끝난 것"이라는 이분법이다) 별도 테이블을 둔 이유이기도 하다.
 */
public enum ThrInvStatus {

	/** 초대했고 대상이 아직 첫 로그인을 하지 않았다. */
	PENDING,

	/** 대상이 로그인해 실제 참가(thr_mbr)로 전환됐다. */
	ACCEPTED,

	/** 초대가 거둬졌다 — 초대자 퇴사 등. */
	REVOKED

}
