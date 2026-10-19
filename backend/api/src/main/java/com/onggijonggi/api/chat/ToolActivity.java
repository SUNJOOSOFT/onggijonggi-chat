package com.onggijonggi.api.chat;

import com.onggijonggi.api.web.WebTools;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ToolActivity.java
 * Description : 도구 이름·인자를 방에 알릴 활동(종류 코드·대상)으로 바꾼다(이슈 #397). 대상은 인자에서 그 도구의
 *               키 하나만 꺼낸다 — 개정본의 바꿀 문장처럼 다른 인자는 싣지 않는다. 현재 시각처럼 사용자에게
 *               의미 있는 작업이 아닌 도구는 알리지 않는다(empty). 문구는 화면이 종류 코드로 고른다.
 *               대상은 방 참여자 모두에게 보이므로 읽을 페이지 주소는 쿼리·조각·사용자 정보를 빼고 보낸다.
 */
record ToolActivity(String kind, String target) {

	/** 대상이 이보다 많은 글자면 잘라서 보낸다. */
	static final int MAX_TARGET_LENGTH = 100;

	static Optional<ToolActivity> of(String toolName, String input, ObjectMapper json) {
		return switch (toolName) {
			// 도구 이름은 각 도구의 @Tool(name) 상수다 — 이름을 바꾸면 여기도 함께 바뀌고, 매핑이 빠진 도구는
			// ToolActivityTest가 등록된 도구 전체를 훑어 잡는다.
			case CurrentTimeTool.NAME -> Optional.empty();
			case WebTools.SEARCH_NAME -> Optional.of(new ToolActivity("WEB_SEARCH", shorten(argument(input, "query", json))));
			case WebTools.FETCH_NAME ->
				Optional.of(new ToolActivity("WEB_FETCH", shorten(withoutQuery(argument(input, "url", json)))));
			case ThreadDocumentTool.NAME ->
				Optional.of(new ToolActivity("DOCUMENT_SEARCH", shorten(argument(input, "query", json))));
			case ThreadDocumentTool.REVISE_NAME ->
				Optional.of(new ToolActivity("DOCUMENT_REVISE", shorten(argument(input, "fileName", json))));
			case ThreadDocumentCreateTool.NAME ->
				Optional.of(new ToolActivity("DOCUMENT_CREATE", shorten(argument(input, "title", json))));
			default -> Optional.of(new ToolActivity("TOOL", null));
		};
	}

	/** 인자 JSON에서 그 키의 문자열 하나. 모델이 만든 값이라 줄바꿈·제어 문자가 섞일 수 있어 한 줄로 바꾼다. */
	private static String argument(String input, String key, ObjectMapper json) {
		try {
			JsonNode value = json.readTree(input).get(key);
			if (value == null || !value.isString()) {
				return null;
			}
			String text = value.asString().replaceAll("\\p{Cntrl}+", " ").strip();
			return text.isEmpty() ? null : text;
		} catch (RuntimeException unreadable) {
			return null;
		}
	}

	/**
	 * 읽을 페이지 주소에서 쿼리·조각·사용자 정보를 뺀 scheme://host[:port]/path. 쿼리에는 검색어·토큰 같은 값이 실릴 수
	 * 있다. 주소로 읽히지 않으면 싣지 않는다.
	 */
	private static String withoutQuery(String url) {
		if (url == null) {
			return null;
		}
		try {
			URI uri = new URI(url);
			if (uri.getScheme() == null || uri.getHost() == null) {
				return null;
			}
			String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
			String path = uri.getRawPath() == null ? "" : uri.getRawPath();
			return uri.getScheme() + "://" + uri.getHost() + port + path;
		} catch (URISyntaxException unreadable) {
			return null;
		}
	}

	/** 글자(코드 포인트) 단위로 자른다 — UTF-16 단위로 자르면 이모지 같은 글자가 반으로 갈린다. */
	private static String shorten(String text) {
		if (text == null || text.codePointCount(0, text.length()) <= MAX_TARGET_LENGTH) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, MAX_TARGET_LENGTH)) + "…";
	}
}
