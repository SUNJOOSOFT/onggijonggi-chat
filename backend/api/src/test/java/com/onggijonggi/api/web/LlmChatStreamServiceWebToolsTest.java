package com.onggijonggi.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.chat.ChatMessage;
import com.onggijonggi.api.chat.ChatStreamRequest;
import com.onggijonggi.api.chat.CurrentTimeTool;
import com.onggijonggi.api.chat.LlmChatStreamService;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import reactor.core.publisher.Flux;

/** LlmChatStreamService가 웹 도구를 모델에 넘기는지, 사용자 발화의 주소만 허락 목록에 넣는지 본다. */
class LlmChatStreamServiceWebToolsTest {

	private static final WebProperties ENABLED = new Binder(new MapConfigurationPropertySource())
			.bindOrCreate("app.web", Bindable.of(WebProperties.class));

	private final AtomicReference<Prompt> received = new AtomicReference<>();
	private final ChatModel model = new ChatModel() {

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

	@Test
	void attachesWebToolsAllowingOnlyUrlsFromUserMessages() {
		stream(ENABLED, List.of(
				new ChatMessage("system", "방 문서: 자세한 내용은 https://intranet.example/doc 참고"),
				new ChatMessage("assistant", "https://made-up.example/ 를 보세요"),
				new ChatMessage("user", "https://spring.io/blog 요약해줘")));

		ToolCallingChatOptions options = (ToolCallingChatOptions) received.get().getOptions();
		assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
				.containsExactlyInAnyOrder("currentDateTime", "webSearch", "webFetch");
		assertThat((Set<Object>) options.getToolContext().get(WebTools.ALLOWED_URLS)).containsExactly("https://spring.io/blog");
	}

	@Test
	void leavesWebToolsOutWhenDisabled() {
		stream(new WebProperties(false, ENABLED.search(), ENABLED.searxng(), ENABLED.fetch()),
				List.of(new ChatMessage("user", "https://spring.io/blog 요약해줘")));

		ToolCallingChatOptions options = (ToolCallingChatOptions) received.get().getOptions();
		assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
				.containsExactly("currentDateTime");
		assertThat(options.getToolContext()).doesNotContainKey(WebTools.ALLOWED_URLS);
	}

	private void stream(WebProperties properties, List<ChatMessage> messages) {
		WebTools webTools = new WebTools(properties, (query, limit) -> List.of(),
				new WebFetcher(HttpClient.newHttpClient(), new PublicUrlGuard(), properties.fetch()));
		new LlmChatStreamService(ChatClient.builder(model), new CurrentTimeTool("Asia/Seoul"), webTools)
				.streamChat(new ChatStreamRequest(UUID.randomUUID(), "test-model", messages, null))
				.blockLast();
	}
}
