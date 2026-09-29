package com.onggijonggi.api.chat;

import org.springframework.http.HttpStatus;

/**
 * Class Name : MsgFileRejectedException.java
 * Description : 첨부 파일을 받거나 발화에 실을 수 없을 때. 형식·크기·내용 없음처럼 사용자가 고칠 수 있는
 *               이유라 코드와 문구를 그대로 화면에 보인다. REST 업로드는 GlobalExceptionHandler가,
 *               WS 발화는 ThreadWebSocketHandler가 같은 코드·문구로 바꿔 돌려준다.
 */
public class MsgFileRejectedException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public MsgFileRejectedException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

}
