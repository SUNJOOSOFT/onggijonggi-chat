package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.onggijonggi.api.web.WebTools;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

class LlmChatStreamServiceTest {

	/** 모델에 넘어간 Prompt에 현재 시각 도구와 발화자의 시간대가 함께 실린다. 도구 실행은 모델 구현(ToolCallingManager)이 맡는다. */
	@Test
	void attachesTheCurrentTimeToolWithTheSendersTimeZone() {
		AtomicReference<Prompt> received = new AtomicReference<>();
		ChatModel model = new ChatModel() {

			/** ChatClient는 모델 기본 옵션이 도구 옵션일 때만 도구를 싣는다. 실제 OpenAiChatModel의 옵션(OpenAiChatOptions)이 그렇다. */
			@Override
			public ChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}

			@Override
			public ChatResponse call(Prompt prompt) {
				received.set(prompt);
				return new ChatResponse(List.of(new Generation(new AssistantMessage("답"))));
			}

			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.just(call(prompt));
			}
		};
		CurrentTimeTool tool = new CurrentTimeTool(Clock.fixed(Instant.parse("2026-10-07T06:00:00Z"), ZoneOffset.UTC),
				ZoneId.of("Asia/Seoul"));
		// 웹 도구는 꺼진 것으로 둔다(mock의 enabled()는 false). 웹 도구 연결은 web.LlmChatStreamServiceWebToolsTest가 본다.
		LlmChatStreamService service = new LlmChatStreamService(ChatClient.builder(model), tool, mock(WebTools.class));

		service.streamChat(new ChatStreamRequest(UUID.randomUUID(), "test-model",
				List.of(new ChatMessage("user", "지금 몇 시야")), "America/New_York")).blockLast();

		ToolCallingChatOptions options = (ToolCallingChatOptions) received.get().getOptions();
		assertThat(options.getToolContext()).containsEntry(CurrentTimeTool.TIME_ZONE, ZoneId.of("America/New_York"));
		ToolCallback callback = options.getToolCallbacks().stream()
				.filter(candidate -> candidate.getToolDefinition().name().equals("currentDateTime"))
				.findFirst().orElseThrow();
		assertThat(callback.call("{}", new ToolContext(options.getToolContext())))
				.contains("2026-10-07T02:00:00-04:00[America/New_York] WEDNESDAY");
	}
}
