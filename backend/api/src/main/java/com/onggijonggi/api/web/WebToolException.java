package com.onggijonggi.api.web;

/**
 * Class Name : WebToolException.java
 * Description : 웹 검색·읽기를 끝내지 못했다. 메시지는 모델에 그대로 돌려주므로 내부 주소·응답 본문을 넣지 않는다.
 */
class WebToolException extends RuntimeException {

	WebToolException(String message) {
		super(message);
	}

	WebToolException(String message, Throwable cause) {
		super(message, cause);
	}
}
