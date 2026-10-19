package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.chat.ThreadDocumentPlan.Block;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * Class Name : ThreadDocumentCreateToolTest.java
 * Description : 모델이 보는 새 문서 도구의 정의(이름·입력 스키마)와, 모델이 넘긴 JSON 인자가 문서 계획으로 풀려 이 턴의 방·요청자·
 *               턴 키와 함께 ThreadDocumentCreation으로 넘어가는지 확인한다(#415).
 */
class ThreadDocumentCreateToolTest {

	private final ThreadDocumentCreation creation = mock(ThreadDocumentCreation.class);
	private final UUID room = UUID.randomUUID();
	private final CurrentActor actor = new CurrentActor(UUID.randomUUID(), "s", "s");

	private ToolCallback callback() {
		ToolCallback[] callbacks = ToolCallbacks.from(new ThreadDocumentCreateTool(creation, room, actor, "turn-1"));
		assertThat(callbacks).hasSize(1);
		return callbacks[0];
	}

	@Test
	void theModelSeesOneToolWithTheBlockKindsInItsSchema() {
		ToolCallback callback = callback();

		assertThat(callback.getToolDefinition().name()).isEqualTo(ThreadDocumentCreateTool.NAME);
		assertThat(callback.getToolDefinition().description()).contains("reviseThreadDocument").contains("등록했습니다");
		assertThat(callback.getToolDefinition().inputSchema()).contains("title", "format", "blocks", "bullet_list", "columns", "rows");
	}

	@Test
	void theJsonArgumentsBecomeAPlanForTheTurnsRoomAndRequester() {
		when(creation.create(eq(room), eq(actor), eq("turn-1"), any(), any()))
				.thenReturn(new ThreadDocumentCreation.Result(true, "등록했습니다"));

		String result = callback().call("""
				{"title":"주간 보고","format":"MD","blocks":[
				  {"type":"heading","level":1,"text":"개요"},
				  {"type":"paragraph","text":"본문"},
				  {"type":"bullet_list","items":["가","나"]},
				  {"type":"table","columns":["항목","상태"],"rows":[["API","완료"]]},
				  {"type":"page_break"}]}""");

		assertThat(result).contains("등록했습니다");
		ArgumentCaptor<ThreadDocumentPlan> plan = ArgumentCaptor.forClass(ThreadDocumentPlan.class);
		verify(creation).create(eq(room), eq(actor), eq("turn-1"), plan.capture(), any());
		assertThat(plan.getValue()).isEqualTo(new ThreadDocumentPlan("주간 보고", "MD", List.of(
				new Block("heading", 1, "개요", null, null, null),
				new Block("paragraph", null, "본문", null, null, null),
				new Block("bullet_list", null, null, List.of("가", "나"), null, null),
				new Block("table", null, null, null, List.of("항목", "상태"), List.of(List.of("API", "완료"))),
				new Block("page_break", null, null, null, null, null))));
		assertThat(plan.getValue().problem()).isNull();
	}

	@Test
	void callsOfOneTurnShareTheSameMemoryOfWhatWasMade() {
		ThreadDocumentCreateTool tool = new ThreadDocumentCreateTool(creation, room, actor, "turn-1");
		when(creation.create(any(), any(), any(), any(), any())).thenReturn(new ThreadDocumentCreation.Result(false, "x"));
		List<Block> blocks = List.of(new Block("paragraph", null, "a", null, null, null));

		tool.createThreadDocument("가", "DOCX", blocks);
		tool.createThreadDocument("나", "DOCX", blocks);

		var sets = ArgumentCaptor.forClass(java.util.Set.class);
		verify(creation, org.mockito.Mockito.times(2)).create(any(), any(), any(), any(), sets.capture());
		assertThat(sets.getAllValues().get(0)).isSameAs(sets.getAllValues().get(1));
	}
}
