package com.onggijonggi.api.chat;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Class Name : ThreadDocumentTool.java
 * Description : AI 턴에 붙이는 방 문서 도구(검색·개정본 만들기). 턴마다 새로 만든다 — 검색할 방·사람과 출처·안내를 남길 턴이 턴마다 다르다.
 *               검색과 결과 처리(출처·안내·모델에 돌려줄 문장)는 ThreadMessageDispatcher가 search로 넘긴다.
 *               검색을 모델이 부를 때만 하므로, 문서가 필요 없는 질문(인사·현재 시각·첨부 요약)에는 출처가 붙지 않는다.
 *               개정본 만들기는 ThreadDocumentRevision이 한다 — 원본 docx 서식을 유지한 채 지정한 문장만 바꿔 방 문서로 등록한다.
 */
public class ThreadDocumentTool {

	static final String NAME = "searchThreadDocuments";
	static final String REVISE_NAME = "reviseThreadDocument";

	/** 바꿀 문장 하나. before는 원문에 정확히 한 번 있는 문장이어야 한다. */
	public record Edit(
			@ToolParam(description = "바꿀 원래 문장. searchThreadDocuments 결과의 원문을 그대로 옮겨 쓴다. 문서에서 한 곳만 가리키도록 충분히 길게 쓴다.") String before,
			@ToolParam(description = "바꾼 뒤 문장. 지우려면 빈 문자열.") String after) { }

	private final Function<String, String> search;
	private final BiFunction<String, List<Edit>, String> revise;

	ThreadDocumentTool(Function<String, String> search, BiFunction<String, List<Edit>, String> revise) {
		this.search = search;
		this.revise = revise;
	}

	@Tool(name = NAME, description = "이 방에 고정된 문서에서 질문과 관련된 부분을 검색해 근거로 돌려준다. "
			+ "고정 문서의 내용이 있어야 답할 수 있는 질문에만 쓴다. 인사, 현재 시각, 첨부 파일 요약처럼 고정 문서가 필요 없는 질문에는 쓰지 않는다.")
	public String searchThreadDocuments(
			@ToolParam(description = "대화를 보지 않아도 뜻이 통하는 검색 문장 한 개. \"그거\" 같은 말은 가리키는 대상으로 바꿔 쓴다.") String query) {
		return search.apply(query);
	}

	@Tool(name = REVISE_NAME, description = "이 방에 고정된 docx 문서의 서식을 그대로 둔 채 지정한 문장만 바꾼 개정본 파일을 만들어 방 문서로 등록한다. "
			+ "개정본 끝에는 신구조문 대비표가 붙는다. 사용자가 문서의 개정본·수정본 파일을 만들어 달라고 할 때만 쓴다. "
			+ "부르기 전에 searchThreadDocuments로 바꿀 조항의 원문을 확인한다. "
			+ "결과가 '개정본을 만들지 못했습니다'로 시작하면 파일은 없다 — 다시 불러 성공하지 않았다면 사용자에게 만들지 못했다고 그 이유와 함께 알린다. "
			+ "파일을 만들었다고 말하는 것은 결과가 '등록했습니다'일 때뿐이고, 파일 이름은 결과에 나온 그대로 쓴다.")
	public String reviseThreadDocument(
			@ToolParam(description = "고칠 고정 문서의 파일 이름. 문서 목록에 나온 이름을 그대로 쓴다.") String fileName,
			@ToolParam(description = "바꿀 문장 목록") List<Edit> edits) {
		return revise.apply(fileName, edits);
	}
}
