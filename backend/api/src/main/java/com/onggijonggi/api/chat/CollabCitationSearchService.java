package com.onggijonggi.api.chat;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Class Name : CollabCitationSearchService.java
 * Description : 협업방 @AI 답변의 근거 인용을 만든다. 실제 문서 검색(RAG)은 아직 없어(로드맵
 *               v0.4 Enterprise RAG) 프론트 목업(app/(chat)/api/chat/citations/route.ts)과
 *               같은 고정 응답을 낸다 — 이슈 #163은 이 자리를 REST에서 WS chat.answer
 *               프레임으로 옮기는 것만 다루고, 실제 검색 구현은 범위 밖이다.
 */
@Component
public class CollabCitationSearchService {

	private static final Pattern RESTRICTED_KEYWORDS = Pattern.compile("기밀|임원|대외비");

	/** 이름과 달리 실제로 검색하지 않는다 — 클래스 설명대로 고정 응답이다. */
	CitationSearchResult search(String query) {
		List<Citation> citations = List.of(
				new Citation("doc-001", "사내 규정집 3장 — 계약 관리",
						"\"" + query + "\" 관련 조항: 계약 체결은 담당 부서장의 승인을 거쳐...", 0.91),
				new Citation("doc-014", "표준 계약서 템플릿 v2",
						"위약금 조항은 제12조에 따라 계약금의 10% 를 상한으로...", 0.82));
		boolean restrictedResultsOmitted = RESTRICTED_KEYWORDS.matcher(query).find();
		return new CitationSearchResult(citations, restrictedResultsOmitted);
	}

	/** citations·restrictedResultsOmitted는 ChatAnswerFrame과 마찬가지로 서로 독립이다 —
	 * citations가 비어도 restrictedResultsOmitted는 true일 수 있다. */
	record CitationSearchResult(List<Citation> citations, boolean restrictedResultsOmitted) {
	}

}
