package com.onggijonggi.api.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : ThreadApprovalException.java
 * Description : 승인 요청이 승인 고유의 이유로 거절된 경우(#423). 방이 잠겼거나 같은 id가 다른 내용으로 온 충돌(409)과 원인이
 *               달라 별도 code를 붙인다 — 같은 code면 화면이 "요청 상태가 바뀌었다"고만 안내해 사용자가 무엇을 해야 할지 모른다.
 *               상태 코드는 ResponseStatusException 그대로라 호출부·테스트는 상태만 보면 된다.
 */
public class ThreadApprovalException extends ResponseStatusException {

	private final String code;

	private ThreadApprovalException(HttpStatus status, String code, String reason) {
		super(status, reason);
		this.code = code;
	}

	/** 같은 원글에 처리 전(PENDING)인 요청이 이미 있다. 그 요청이 승인이나 반려로 끝나면 다시 요청할 수 있다. */
	public static ThreadApprovalException alreadyPending() {
		return new ThreadApprovalException(HttpStatus.CONFLICT, "APPROVAL_ALREADY_PENDING", "이 글에는 이미 처리 대기 중인 승인 요청이 있습니다.");
	}

	public String getCode() {
		return code;
	}
}
