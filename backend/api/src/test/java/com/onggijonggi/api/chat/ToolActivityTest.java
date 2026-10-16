package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.web.WebTools;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ToolActivityTest.java
 * Description : 도구 이름·인자가 방에 알릴 종류 코드·대상으로 바뀌는지 검증한다(이슈 #397).
 */
class ToolActivityTest {

	private final ObjectMapper json = JsonMapper.builder().build();

	@Test
	void mapsEachToolToItsKindAndTarget() {
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"서울 날씨\"}", json))
				.contains(new ToolActivity("WEB_SEARCH", "서울 날씨"));
		assertThat(ToolActivity.of("webFetch", "{\"url\":\"https://example.org/a\"}", json))
				.contains(new ToolActivity("WEB_FETCH", "https://example.org/a"));
		assertThat(ToolActivity.of(ThreadDocumentTool.NAME, "{\"query\":\"연차 신청\"}", json))
				.contains(new ToolActivity("DOCUMENT_SEARCH", "연차 신청"));
		assertThat(ToolActivity.of("unknownTool", "{\"x\":1}", json)).contains(new ToolActivity("TOOL", null));
	}

	/**
	* 모델에 넘기는 도구(LlmChatStreamService가 담는 클래스들)마다 진행 표시 종류가 정해져 있다 — 도구 이름을 바꾸거나
	* 새 도구를 더하고 ToolActivity를 잊으면 "작업 중…"으로 조용히 바뀌므로 여기서 잡는다. 현재 시각만 알리지 않는다.
	*/
	@Test
	void everyToolGivenToTheModelHasAnActivityKind() {
		for (Class<?> toolClass : List.of(CurrentTimeTool.class, WebTools.class, ThreadDocumentTool.class)) {
			for (Method method : toolClass.getDeclaredMethods()) {
				Tool tool = method.getAnnotation(Tool.class);
				if (tool == null) {
					continue;
				}
				String name = tool.name().isEmpty() ? method.getName() : tool.name();
				Optional<ToolActivity> activity = ToolActivity.of(name, "{}", json);
				if (name.equals(CurrentTimeTool.NAME)) {
					assertThat(activity).as(name).isEmpty();
				} else {
					assertThat(activity).as(name).hasValueSatisfying(value -> assertThat(value.kind()).isNotEqualTo("TOOL"));
				}
			}
		}
	}

	@Test
	void doesNotAnnounceTheCurrentTimeTool() {
		assertThat(ToolActivity.of("currentDateTime", "{}", json)).isEmpty();
	}

	/** 개정본은 파일 이름만 싣는다 — 바꿀 문장(edits)은 싣지 않는다. */
	@Test
	void revisionCarriesOnlyTheFileName() {
		assertThat(ToolActivity.of(ThreadDocumentTool.REVISE_NAME,
				"{\"fileName\":\"규정.docx\",\"edits\":[{\"before\":\"12시간\",\"after\":\"18시간\"}]}", json))
				.contains(new ToolActivity("DOCUMENT_REVISE", "규정.docx"));
	}

	@Test
	void cutsLongTargetsAndToleratesBrokenInput() {
		String longQuery = "가".repeat(150);
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"" + longQuery + "\"}", json).orElseThrow().target())
				.isEqualTo("가".repeat(ToolActivity.MAX_TARGET_LENGTH) + "…");
		assertThat(ToolActivity.of("webSearch", "{깨진", json)).contains(new ToolActivity("WEB_SEARCH", null));
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"  \"}", json)).contains(new ToolActivity("WEB_SEARCH", null));
	}

	/** 정확히 상한 길이면 자르지 않는다. 키가 없거나 문자열이 아니면 대상 없이 알린다. */
	@Test
	void keepsTargetsAtTheLimitAndSkipsMissingOrNonStringArguments() {
		String atLimit = "가".repeat(ToolActivity.MAX_TARGET_LENGTH);
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"" + atLimit + "\"}", json).orElseThrow().target())
				.isEqualTo(atLimit);
		assertThat(ToolActivity.of(ThreadDocumentTool.REVISE_NAME, "{\"edits\":[]}", json))
				.contains(new ToolActivity("DOCUMENT_REVISE", null));
		assertThat(ToolActivity.of("webSearch", "{\"query\":123}", json)).contains(new ToolActivity("WEB_SEARCH", null));
		assertThat(ToolActivity.of("webFetch", "{\"url\":\"mailto:a@example.org\"}", json).orElseThrow().target())
				.isNull();
	}

	/** 글자 단위로 자른다 — 이모지(UTF-16 두 단위)가 경계에 걸려도 반으로 갈리지 않는다. */
	@Test
	void cutsByCharactersNotUtf16Units() {
		String emojis = "😀".repeat(150);
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"" + emojis + "\"}", json).orElseThrow().target())
				.isEqualTo("😀".repeat(ToolActivity.MAX_TARGET_LENGTH) + "…");
	}

	/** 페이지 주소는 방 참여자 모두에게 보이므로 쿼리·조각·사용자 정보를 빼고, 주소가 아니면 싣지 않는다. */
	@Test
	void fetchTargetDropsQueryFragmentAndUserInfo() {
		assertThat(ToolActivity.of("webFetch",
				"{\"url\":\"https://user:pw@example.org:8443/a/b?token=secret&q=1#part\"}", json).orElseThrow().target())
				.isEqualTo("https://example.org:8443/a/b");
		assertThat(ToolActivity.of("webFetch", "{\"url\":\"그냥 글자\"}", json).orElseThrow().target()).isNull();
	}

	/** 모델이 만든 값의 줄바꿈·제어 문자는 한 줄 표시로 바꾼다. */
	@Test
	void flattensLineBreaksInTargets() {
		assertThat(ToolActivity.of("webSearch", "{\"query\":\"서울\\n날씨\\t오늘\"}", json).orElseThrow().target())
				.isEqualTo("서울 날씨 오늘");
	}
}
