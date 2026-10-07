package com.onggijonggi.api.web;

import java.util.List;

/**
 * Class Name : WebSearchProvider.java
 * Description : 웹 검색 공급자. 설정(app.web.search.provider)으로 구현을 고른다(WebConfiguration).
 *               검색할 수 없으면(차단·장애·응답 이상) WebToolException을 던진다 — 결과 0건과 구분한다.
 */
interface WebSearchProvider {

	/** 검색 결과 하나. */
	record Result(String title, String url, String snippet) { }

	List<Result> search(String query, int limit);
}
