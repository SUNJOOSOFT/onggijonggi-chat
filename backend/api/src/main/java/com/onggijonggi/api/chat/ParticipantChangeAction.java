package com.onggijonggi.api.chat;

/**
 * Class Name : ParticipantChangeAction.java
 * Description : {@link ParticipantChangedFrame}이 나르는 변경 종류(이슈 #129). 01·CLIENT는
 *               지금은 이 값으로 분기하지 않고 프레임 수신을 참여자 명단 재조회 트리거로만 쓴다
 *               — 값을 셋으로 나눈 것은 나중에 액션별 시스템 메시지가 필요해졌을 때(#111과
 *               별개) 프레임 타입 자체를 새로 추가하지 않아도 되게 하기 위해서다.
 */
public enum ParticipantChangeAction {

	INVITED,

	REMOVED,

	OWNER_TRANSFERRED,

	/**
	 * 아직 로그인한 적 없는 사람에게 대기 초대가 생겼다(이슈 #172). INVITED와 나눈 것은 그
	 * 사람이 아직 참가자가 아니기 때문이다 — 명단에는 "초대 대기"로 뜨고 메시지를 보낼 수도
	 * 받을 수도 없다. 지금은 01·CLIENT가 값으로 분기하지 않아 둘을 합쳐도 동작은 같지만,
	 * 액션별 시스템 메시지를 붙이는 순간 "참가했다"와 "불렀을 뿐이다"는 다른 문장이 된다.
	 */
	INVITE_PENDING,

	/** 대기 초대가 거둬졌다(이슈 #172). 참가한 적이 없으므로 REMOVED와 같은 값을 쓰지 않는다. */
	INVITE_REVOKED

}
