package com.onggijonggi.api.chat;

/**
 * Class Name : InviteeOutsideWorkspaceException.java
 * Description : 초대 대상이 그 방의 워크스페이스를 볼 수 없는 경우. 호출자에게 권한이 없는 403이나 참여자 상태가 바뀐
 *               409와 원인이 달라, 별도 타입으로 둬 GlobalExceptionHandler가 따로 코드를 붙이게 한다.
 */
public class InviteeOutsideWorkspaceException extends RuntimeException {

	public InviteeOutsideWorkspaceException() {
		super("이 방의 워크스페이스를 볼 수 없는 사람은 초대할 수 없습니다.");
	}

}
