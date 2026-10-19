package com.onggijonggi.api.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Class Name : ThreadDocumentPlan.java
 * Description : 모델이 새 문서 도구(ThreadDocumentCreateTool)에 넘기는 문서 계획 — 제목·형식·본문 요소 목록. 문서 워커가 받는
 *               DOCX renderer DSL의 한도와 같은 기준으로 problem()이 먼저 검증해, 넘는 계획은 워커를 부르지 않고 모델이 고쳐 다시
 *               부를 수 있는 이유로 돌려준다. 한도의 출처는 worker/doc/docs/document-dsl.md다. 워커는 정의되지 않은 필드를
 *               거절하므로 operations()는 요소 종류마다 해당 필드만 싣는다.
 */
public record ThreadDocumentPlan(String title, String format, List<Block> blocks) {

	static final int MAX_BLOCKS = 500;
	static final int MAX_TEXT_LENGTH = 10_000;
	static final int MAX_TABLE_COLUMNS = 20;
	static final int MAX_TABLE_ROWS = 200;
	static final int MAX_LIST_ITEMS = 200;
	private static final List<String> FORMATS = List.of("DOCX", "MD");

	/** 본문 요소 하나. type에 해당하는 필드만 채우고 나머지는 비운다. */
	public record Block(
			@ToolParam(description = "요소 종류. heading, paragraph, bullet_list, numbered_list, table, page_break 중 하나.") String type,
			@ToolParam(required = false, description = "heading의 수준(1~6, 1이 가장 큼).") Integer level,
			@ToolParam(required = false, description = "heading과 paragraph의 글.") String text,
			@ToolParam(required = false, description = "bullet_list와 numbered_list의 항목들.") List<String> items,
			@ToolParam(required = false, description = "table의 열 제목들.") List<String> columns,
			@ToolParam(required = false, description = "table의 행들. 각 행은 열 제목과 같은 개수의 칸을 가진다.") List<List<String>> rows) { }

	/** 파일 확장자. 검증을 통과한 계획에서만 쓴다. */
	String extension() {
		return "MD".equals(normalizedFormat()) ? ".md" : ".docx";
	}

	String normalizedFormat() {
		return format == null ? "" : format.strip().toUpperCase(Locale.ROOT);
	}

	/** 한도나 형식을 넘으면 모델에 돌려줄 이유 한 줄, 문제가 없으면 null. */
	String problem() {
		if (title == null || title.isBlank()) return "제목을 넘기세요.";
		if (title.length() > MAX_TEXT_LENGTH) return "제목이 너무 깁니다.";
		if (!FORMATS.contains(normalizedFormat())) return "형식은 DOCX 또는 MD만 만들 수 있습니다.";
		if (blocks == null || blocks.isEmpty()) return "본문 요소를 하나 이상 넘기세요.";
		if (blocks.size() > MAX_BLOCKS) return "본문 요소가 " + MAX_BLOCKS + "개를 넘습니다. 내용을 줄이세요.";
		for (int i = 0; i < blocks.size(); i++) {
			String problem = problem(blocks.get(i));
			if (problem != null) return (i + 1) + "번째 요소: " + problem;
		}
		return null;
	}

	private static String problem(Block block) {
		if (block == null || block.type() == null) return "type이 없습니다.";
		return switch (block.type()) {
			case "heading" -> block.level() == null || block.level() < 1 || block.level() > 6
					? "heading의 level은 1~6이어야 합니다." : textProblem(block.text());
			case "paragraph" -> textProblem(block.text());
			case "bullet_list", "numbered_list" -> {
				List<String> items = block.items();
				if (items == null || items.isEmpty() || items.size() > MAX_LIST_ITEMS)
					yield "목록 항목은 1~" + MAX_LIST_ITEMS + "개여야 합니다.";
				yield items.stream().map(ThreadDocumentPlan::textProblem).filter(p -> p != null).findFirst().orElse(null);
			}
			case "table" -> tableProblem(block);
			case "page_break" -> null;
			default -> "알 수 없는 요소 종류 '" + block.type() + "'입니다.";
		};
	}

	private static String tableProblem(Block block) {
		List<String> columns = block.columns();
		List<List<String>> rows = block.rows() == null ? List.of() : block.rows();
		if (columns == null || columns.isEmpty() || columns.size() > MAX_TABLE_COLUMNS)
			return "표의 열은 1~" + MAX_TABLE_COLUMNS + "개여야 합니다.";
		if (rows.size() > MAX_TABLE_ROWS) return "표의 행은 " + MAX_TABLE_ROWS + "개까지입니다.";
		for (String column : columns) {
			String problem = textProblem(column);
			if (problem != null) return problem;
		}
		for (List<String> row : rows) {
			if (row == null || row.size() != columns.size()) return "표의 모든 행은 열 제목과 같은 개수의 칸이어야 합니다.";
			for (String cell : row) {
				String problem = textProblem(cell);
				if (problem != null) return problem;
			}
		}
		return null;
	}

	private static String textProblem(String text) {
		if (text == null || text.isEmpty()) return "빈 글은 넣을 수 없습니다.";
		if (text.length() > MAX_TEXT_LENGTH) return "한 글이 " + MAX_TEXT_LENGTH + "자를 넘습니다. 나눠 쓰세요.";
		return null;
	}

	/** 워커 DSL의 operations. 요소 종류마다 해당 필드만 싣는다. */
	List<Map<String, Object>> operations() {
		List<Map<String, Object>> operations = new ArrayList<>();
		for (Block block : blocks) {
			Map<String, Object> operation = new LinkedHashMap<>();
			operation.put("type", block.type());
			switch (block.type()) {
				case "heading" -> {
					operation.put("level", block.level());
					operation.put("text", block.text());
				}
				case "paragraph" -> operation.put("text", block.text());
				case "bullet_list", "numbered_list" -> operation.put("items", block.items());
				case "table" -> {
					operation.put("columns", block.columns());
					operation.put("rows", block.rows() == null ? List.of() : block.rows());
				}
				default -> { }
			}
			operations.add(operation);
		}
		return operations;
	}
}
