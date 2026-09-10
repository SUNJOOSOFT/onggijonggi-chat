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

	OWNER_TRANSFERRED

}
