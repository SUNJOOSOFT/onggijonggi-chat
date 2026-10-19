package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Class Name : ThreadDocumentCreateTool.java
 * Description : AI 턴에 붙이는 새 문서 만들기 도구(#415). 턴마다 새로 만든다 — 만들 방·요청한 사람과 같은 턴에서 이미 만든 계획이
 *               턴마다 다르다. 방에 고정된 문서가 없어도 쓸 수 있어야 해서 방 문서 도구(ThreadDocumentTool)와 따로 둔다.
 *               실제 검증·생성·등록은 ThreadDocumentCreation이 한다.
 */
public class ThreadDocumentCreateTool {

	static final String NAME = "createThreadDocument";

	private final ThreadDocumentCreation creation;
	private final UUID thread;
	private final CurrentActor actor;
	private final String turnKey;
	/** 이 턴에서 만든 계획의 요청 키. 같은 계획의 재호출과 턴당 문서 수 제한을 판단하는 데 쓴다. */
	private final Set<UUID> created = new LinkedHashSet<>();

	ThreadDocumentCreateTool(ThreadDocumentCreation creation, UUID thread, CurrentActor actor, String turnKey) {
		this.creation = creation;
		this.thread = thread;
		this.actor = actor;
		this.turnKey = turnKey;
	}

	@Tool(name = NAME, description = "사용자가 처음부터 새 문서(보고서·정리본·회의록 등)를 만들어 달라고 할 때 DOCX 또는 Markdown 파일을 만들어 "
			+ "방의 출력에 등록한다. 이 방에 고정된 문서를 고치거나 개정본을 만들어 달라는 요청에는 쓰지 않는다 — 그때는 reviseThreadDocument를 쓴다. "
			+ "대화에서 논의한 내용을 정리해 제목과 본문 요소(heading, paragraph, bullet_list, numbered_list, table, page_break)로 나눠 넘긴다. "
			+ "같은 문서를 다시 만들려고 거듭 부르지 않는다. "
			+ "결과가 '문서를 만들지 못했습니다'로 시작하면 파일은 없다 — 이유를 보고 고쳐 다시 부르거나, 안 되면 사용자에게 만들지 못했다고 그 이유와 함께 알린다. "
			+ "파일을 만들었다고 말하는 것은 결과가 '등록했습니다'를 담을 때뿐이고, 파일 이름은 결과에 나온 그대로 쓴다.")
	public String createThreadDocument(
			@ToolParam(description = "문서 제목. 파일 이름도 이 제목으로 정해진다.") String title,
			@ToolParam(description = "파일 형식. DOCX 또는 MD(Markdown) 중 하나. 사용자가 정하지 않았으면 DOCX.") String format,
			@ToolParam(description = "본문 요소를 위에서 아래 순서대로 나열한 목록.") List<ThreadDocumentPlan.Block> blocks) {
		return creation.create(thread, actor, turnKey, new ThreadDocumentPlan(title, format, blocks), created).message();
	}
}
