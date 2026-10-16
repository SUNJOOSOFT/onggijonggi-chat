package com.onggijonggi.api.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Class Name : WebSources.java
 * Description : 한 AI 턴의 웹 출처 모음(이슈 #401). 웹 도구가 검색·읽기에 성공한 것만 넣고, 턴이 끝나면 답변 근거로 꺼낸다.
 *               허락 목록처럼 턴마다 새로 만들어 toolContext로 넘긴다(WebTools.context()) — 다른 턴과 섞이지 않는다.
 *               같은 주소는 허락 목록과 같은 정규화(WebTools.key())로 하나로 합치고, 읽은 페이지 쪽을 남긴다.
 *               검색 결과는 제공자 요약만, 읽은 페이지는 제목·주소만 남긴다 — 본문 앞부분은 메뉴 글자인 경우가 많다.
 *               출처는 방 참여자 모두에게 보이고 메시지에 저장되므로 넣을 때 다듬는다 — 주소의 사용자 정보·조각을 빼고
 *               (쿼리는 링크가 열리도록 남긴다), 제목·요약·검색어의 제어 문자를 빼고 글자 단위로 자른다. 방향 바꾸기
 *               문자처럼 보이는 모양만 바꾸는 글자는 화면이 뺀다(lib/chat/visible-text.ts) — 진행 표시(#397)와 같은 분담이다.
 *               도구는 boundedElastic에서 돌고 모음은 턴 끝에 다른 스레드가 읽어 메서드를 동기화한다.
 */
public class WebSources {

	/** 답변 하나에 남기는 웹 출처 수 상한 — 검색 4번 분량. 넘치면 읽은 페이지를 먼저 남긴다. */
	static final int MAX = 20;
	static final int MAX_SNIPPET = 300;
	static final int MAX_TITLE = 200;
	/** 이보다 긴 주소는 출처로 남기지 않는다 — 잘라 쓰면 다른 주소가 된다. */
	static final int MAX_URL = 2000;
	/** 제어 문자(C0·C1)와 줄·문단 구분 문자 — 한 줄로 보여야 하는 제목·요약·검색어에서 공백으로 바꾼다. */
	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u2028\\u2029]+");

	/**
	 * 웹 출처 하나.
	 * @param url 출처 주소(사용자 정보·조각 제외). 읽은 페이지는 리다이렉트를 따라간 최종 주소다
	 * @param query 이 주소를 처음 찾은 검색어. 검색 없이 사용자가 준 주소를 읽었으면 null이다
	 * @param read 웹 읽기로 본문을 실제로 읽었는지
	 */
	public record Source(String url, String title, String snippet, String query, boolean read) { }

	private final Map<String, Source> sources = new LinkedHashMap<>();

	/** 검색에 성공한 결과를 넣는다. 이미 있는 주소는 그대로 둔다 — 처음 찾은 검색어를 남긴다. */
	synchronized void addSearch(String query, List<WebSearchProvider.Result> results) {
		String cleanQuery = clean(query, MAX_TITLE);
		for (WebSearchProvider.Result result : results) {
			String url = shown(result.url());
			String key = url == null ? null : WebTools.key(url);
			if (key == null) continue;
			sources.putIfAbsent(key, new Source(url, clean(result.title(), MAX_TITLE),
					clean(result.snippet(), MAX_SNIPPET), cleanQuery, false));
		}
	}

	/**
	 * 읽기에 성공한 페이지를 넣는다. requestedKey는 읽기를 요청한 주소의 정규화 값이다 — 검색 결과와 합치는 기준이다.
	 * 검색 결과에 있던 주소면 요약·검색어를 이어받고, 제목은 페이지 제목이 있을 때만 바꾼다. 리다이렉트로 도착한 주소가
	 * 다른 검색 결과로 이미 있으면 그 항목도 이 항목에 합친다 — 같은 주소가 두 번 남지 않게.
	 */
	synchronized void addPage(String requestedKey, String finalUrl, String title) {
		String url = shown(finalUrl);
		if (url == null) return;
		String pageTitle = clean(title, MAX_TITLE);
		Source found = sources.get(requestedKey);
		String finalKey = WebTools.key(url);
		Source landed = finalKey == null || finalKey.equals(requestedKey) ? null : sources.remove(finalKey);
		Source earlier = found != null ? found : landed;
		if (earlier == null) {
			sources.put(requestedKey, new Source(url, pageTitle, "", null, true));
			return;
		}
		sources.put(requestedKey, new Source(url, pageTitle.isBlank() ? earlier.title() : pageTitle,
				earlier.snippet(), earlier.query(), true));
	}

	/** 모은 출처 수 — 상한으로 덜어 내기 전. 운영 로그가 남긴 수와 나란히 찍어 상한에 걸렸는지 본다. */
	public synchronized int collected() {
		return sources.size();
	}

	/** 남길 출처를 넣은 순서대로 돌려준다. 상한을 넘으면 읽은 페이지를 먼저 고르고 남는 자리를 검색 결과로 채운다. */
	public synchronized List<Source> sources() {
		List<Source> all = new ArrayList<>(sources.values());
		if (all.size() <= MAX) return List.copyOf(all);
		int readRoom = MAX;
		int unreadRoom = Math.max(0, MAX - (int) all.stream().filter(Source::read).count());
		List<Source> kept = new ArrayList<>();
		for (Source source : all) {
			if (source.read() && readRoom > 0) {
				kept.add(source);
				readRoom--;
			} else if (!source.read() && unreadRoom > 0) {
				kept.add(source);
				unreadRoom--;
			}
		}
		return List.copyOf(kept);
	}

	/**
	 * 방에 보여도 되는 주소 — 사용자 정보(user:pw@)와 조각(#...)을 뺀다. 쿼리는 링크가 그 페이지로 열리도록 남긴다.
	 * http·https가 아니거나, 해석할 수 없거나, 너무 길면 null이다.
	 */
	static String shown(String url) {
		if (url == null) return null;
		URI uri;
		try {
			uri = new URI(url.strip());
		} catch (URISyntaxException malformed) {
			return null;
		}
		String scheme = uri.getScheme();
		if (scheme == null || uri.getRawAuthority() == null || uri.getHost() == null) return null;
		String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
		String shown = scheme + "://" + uri.getHost() + port + path + query;
		return shown.length() <= MAX_URL && WebTools.key(shown) != null ? shown : null;
	}

	/** 제어 문자(줄바꿈 포함)·줄 구분 문자를 공백으로 바꾸고, 글자(코드 포인트) 단위로 자른다 — UTF-16 단위로 자르면 이모지가 갈린다. */
	private static String clean(String text, int max) {
		if (text == null) return "";
		String flat = CONTROL.matcher(text).replaceAll(" ").strip();
		if (flat.codePointCount(0, flat.length()) <= max) return flat;
		return flat.substring(0, flat.offsetByCodePoints(0, max)) + "…";
	}
}
