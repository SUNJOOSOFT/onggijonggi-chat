package com.onggijonggi.api.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : ThreadDocumentException.java
 * Description : 방 문서 요청이 문서 고유의 이유로 거부된 경우. 참여자 상태 충돌(409)·서버 설정 문제(503)와 원인이
 *               달라 별도 code를 붙인다 — 같은 code면 화면이 "참여자 정보가 바뀌었다"처럼 잘못 안내한다.
 *               상태 코드는 ResponseStatusException 그대로라 호출부·테스트는 상태만 보면 된다.
 */
public class ThreadDocumentException extends ResponseStatusException {

	private final String code;

	private ThreadDocumentException(HttpStatus status, String code, String reason, Throwable cause) {
		super(status, reason, cause);
		this.code = code;
	}

	/** 방이 잠겼거나, 문서가 아직 업로드 중이거나, 이미 그 상태인 등 지금 상태로는 바꿀 수 없다. 다시 조회하면 달라진다. */
	public static ThreadDocumentException conflict() {
		return new ThreadDocumentException(HttpStatus.CONFLICT, "DOCUMENT_STATE_CONFLICT", "문서 상태가 바뀌어 요청을 처리할 수 없습니다.", null);
	}

	/** 원본 저장소(문서 워커)에 저장·조회·삭제하지 못했다. 잠시 뒤 재시도로 풀릴 수 있다. */
	public static ThreadDocumentException storageUnavailable(Throwable cause) {
		return new ThreadDocumentException(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_STORAGE_UNAVAILABLE", "문서 저장소를 사용할 수 없습니다.", cause);
	}

	/** 첨부와 같은 형식 제한이라 첨부의 code·문구를 그대로 쓴다. */
	public static ThreadDocumentException unsupportedFile() {
		return new ThreadDocumentException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "지원하지 않는 파일 형식입니다.", null);
	}

	public static ThreadDocumentException tooLarge() {
		return new ThreadDocumentException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "파일이 너무 큽니다.", null);
	}

	public String getCode() {
		return code;
	}
}
