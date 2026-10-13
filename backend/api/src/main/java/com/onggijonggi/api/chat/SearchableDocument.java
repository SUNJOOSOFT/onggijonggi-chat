package com.onggijonggi.api.chat;

import java.util.List;

/**
 * Class Name : SearchableDocument.java
 * Description : 답변 턴에 알리는 검색할 수 있는 고정 문서 하나. 모델이 방 문서 검색 도구를 부를지 판단할 때 파일 이름만으로는
 *               내용을 알기 어려워, 현재 회차의 태그(#362)를 함께 싣는다. 태그가 없거나(태깅 꺼짐·대기·실패) 미분류면 category는 null이다.
 */
public record SearchableDocument(String fileName, String category, List<String> keywords, String summary) {

	public static SearchableDocument untagged(String fileName) {
		return new SearchableDocument(fileName, null, List.of(), null);
	}
}
