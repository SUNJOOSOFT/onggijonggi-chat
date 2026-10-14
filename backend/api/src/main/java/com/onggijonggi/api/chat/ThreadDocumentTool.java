package com.onggijonggi.api.chat;

import java.util.function.Function;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Class Name : ThreadDocumentTool.java
 * Description : AI 턴에 붙이는 방 문서 검색 도구. 턴마다 새로 만든다 — 검색할 방·사람과 출처·안내를 남길 턴이 턴마다 다르다.
 *               검색과 결과 처리(출처·안내·모델에 돌려줄 문장)는 ThreadMessageDispatcher가 search로 넘긴다.
 *               검색을 모델이 부를 때만 하므로, 문서가 필요 없는 질문(인사·현재 시각·첨부 요약)에는 출처가 붙지 않는다.
 */
public class ThreadDocumentTool {

	static final String NAME = "searchThreadDocuments";

	private final Function<String, String> search;

	ThreadDocumentTool(Function<String, String> search) {
		this.search = search;
	}

	@Tool(name = NAME, description = "이 방에 고정된 문서에서 질문과 관련된 부분을 검색해 근거로 돌려준다. "
			+ "고정 문서의 내용이 있어야 답할 수 있는 질문에만 쓴다. 인사, 현재 시각, 첨부 파일 요약처럼 고정 문서가 필요 없는 질문에는 쓰지 않는다.")
	public String searchThreadDocuments(
			@ToolParam(description = "대화를 보지 않아도 뜻이 통하는 검색 문장 한 개. \"그거\" 같은 말은 가리키는 대상으로 바꿔 쓴다.") String query) {
		return search.apply(query);
	}
}
