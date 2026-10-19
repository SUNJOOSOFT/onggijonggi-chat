package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.chat.ThreadDocumentPlan.Block;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Class Name : ThreadDocumentPlanTest.java
 * Description : 새 문서 계획이 워커의 한도(요소 수·글자 수·표 크기·목록 항목)와 허용 형식 안인지 먼저 거르는지, 워커가 받는
 *               operations에는 요소 종류마다 해당 필드만 싣는지 확인한다(#415).
 */
class ThreadDocumentPlanTest {

	private static Block paragraph(String text) {
		return new Block("paragraph", null, text, null, null, null);
	}

	private static ThreadDocumentPlan plan(Block... blocks) {
		return new ThreadDocumentPlan("주간 보고", "DOCX", List.of(blocks));
	}

	@Test
	void aPlanWithinTheLimitsHasNoProblem() {
		ThreadDocumentPlan plan = plan(new Block("heading", 1, "개요", null, null, null), paragraph("본문"),
				new Block("bullet_list", null, null, List.of("가", "나"), null, null),
				new Block("numbered_list", null, null, List.of("하나"), null, null),
				new Block("table", null, null, null, List.of("항목", "상태"), List.of(List.of("API", "완료"))),
				new Block("page_break", null, null, null, null, null));
		assertThat(plan.problem()).isNull();
	}

	@Test
	void onlyDocxAndMarkdownAreAcceptedAndTheCaseDoesNotMatter() {
		assertThat(new ThreadDocumentPlan("제목", "md", List.of(paragraph("a"))).problem()).isNull();
		assertThat(new ThreadDocumentPlan("제목", "PDF", List.of(paragraph("a"))).problem()).contains("DOCX 또는 MD");
		assertThat(new ThreadDocumentPlan("제목", null, List.of(paragraph("a"))).problem()).contains("DOCX 또는 MD");
		assertThat(new ThreadDocumentPlan("제목", " md ", List.of(paragraph("a"))).extension()).isEqualTo(".md");
		assertThat(new ThreadDocumentPlan("제목", "DOCX", List.of(paragraph("a"))).extension()).isEqualTo(".docx");
	}

	@Test
	void titleAndBlocksAreRequired() {
		assertThat(new ThreadDocumentPlan(" ", "DOCX", List.of(paragraph("a"))).problem()).contains("제목");
		assertThat(new ThreadDocumentPlan("제목", "DOCX", List.of()).problem()).contains("본문 요소");
		assertThat(new ThreadDocumentPlan("제목", "DOCX", null).problem()).contains("본문 요소");
	}

	@Test
	void tooManyBlocksAreRefusedAtTheWorkerLimit() {
		List<Block> atLimit = Collections.nCopies(ThreadDocumentPlan.MAX_BLOCKS, paragraph("a"));
		assertThat(new ThreadDocumentPlan("제목", "DOCX", atLimit).problem()).isNull();
		List<Block> over = Collections.nCopies(ThreadDocumentPlan.MAX_BLOCKS + 1, paragraph("a"));
		assertThat(new ThreadDocumentPlan("제목", "DOCX", over).problem()).contains("500개");
	}

	@Test
	void textLengthAndEmptyTextAreRefusedAndTheBlockIsNamed() {
		assertThat(plan(paragraph("가".repeat(ThreadDocumentPlan.MAX_TEXT_LENGTH))).problem()).isNull();
		assertThat(plan(paragraph("a"), paragraph("가".repeat(ThreadDocumentPlan.MAX_TEXT_LENGTH + 1))).problem())
				.startsWith("2번째 요소").contains("10000자");
		assertThat(plan(paragraph("")).problem()).contains("빈 글");
		assertThat(plan(paragraph(null)).problem()).contains("빈 글");
	}

	@Test
	void headingLevelAndUnknownTypesAreRefused() {
		assertThat(plan(new Block("heading", 7, "x", null, null, null)).problem()).contains("level");
		assertThat(plan(new Block("heading", null, "x", null, null, null)).problem()).contains("level");
		assertThat(plan(new Block("image", null, null, null, null, null)).problem()).contains("알 수 없는 요소 종류");
		assertThat(plan(new Block(null, null, null, null, null, null)).problem()).contains("type");
	}

	@Test
	void listItemCountIsBounded() {
		assertThat(plan(new Block("bullet_list", null, null, List.of(), null, null)).problem()).contains("목록 항목");
		assertThat(plan(new Block("bullet_list", null, null, null, null, null)).problem()).contains("목록 항목");
		assertThat(plan(new Block("numbered_list", null, null,
				Collections.nCopies(ThreadDocumentPlan.MAX_LIST_ITEMS + 1, "a"), null, null)).problem()).contains("목록 항목");
		assertThat(plan(new Block("numbered_list", null, null,
				Collections.nCopies(ThreadDocumentPlan.MAX_LIST_ITEMS, "a"), null, null)).problem()).isNull();
	}

	@Test
	void tableSizeAndRowShapeAreChecked() {
		assertThat(plan(new Block("table", null, null, null, List.of(), List.of())).problem()).contains("열");
		assertThat(plan(new Block("table", null, null, null,
				Collections.nCopies(ThreadDocumentPlan.MAX_TABLE_COLUMNS + 1, "c"), List.of())).problem()).contains("열");
		assertThat(plan(new Block("table", null, null, null, List.of("a", "b"),
				Collections.nCopies(ThreadDocumentPlan.MAX_TABLE_ROWS + 1, List.of("1", "2")))).problem()).contains("행");
		assertThat(plan(new Block("table", null, null, null, List.of("a", "b"), List.of(List.of("1")))).problem())
				.contains("같은 개수");
		assertThat(plan(new Block("table", null, null, null, List.of("a", "b"), List.of(List.of("1", "")))).problem())
				.contains("빈 글");
		// 행이 없는 표는 워커가 받는다(열 제목만 있는 표).
		assertThat(plan(new Block("table", null, null, null, List.of("a"), null)).problem()).isNull();
	}

	@Test
	void operationsCarryOnlyTheFieldsOfEachKind() {
		ThreadDocumentPlan plan = plan(new Block("heading", 2, "개요", List.of("무시"), null, null), paragraph("본문"),
				new Block("bullet_list", null, "무시", List.of("가"), null, null),
				new Block("table", null, null, null, List.of("a"), null),
				new Block("page_break", null, "무시", null, null, null));
		assertThat(plan.operations()).containsExactly(
				java.util.Map.of("type", "heading", "level", 2, "text", "개요"),
				java.util.Map.of("type", "paragraph", "text", "본문"),
				java.util.Map.of("type", "bullet_list", "items", List.of("가")),
				java.util.Map.of("type", "table", "columns", List.of("a"), "rows", List.of()),
				java.util.Map.of("type", "page_break"));
	}
}
