package com.onggijonggi.api.chat;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

/**
 * Class Name : FakeChatModelConfig.java
 * Description : 실 LLM에 붙지 않도록 ChatModel을 고정 응답 가짜 구현으로 교체한다. WS·HTTP 어느
 *               경로든 LlmChatStreamService → ChatClient 오케스트레이션 자체는 그대로 태우되
 *               네트워크는 타지 않아야 하는 컨트롤러·핸들러 테스트가 공유한다
 *               (FakeJwtDecoderConfig와 같은 결).
 */
@TestConfiguration
class FakeChatModelConfig {

	static final String FAKE_REPLY = "(fake) 안녕하세요";

	@Bean
	@Primary
	ChatModel fakeChatModel() {
		return new ChatModel() {

			@Override
			public ChatResponse call(Prompt prompt) {
				return new ChatResponse(List.of(new Generation(new AssistantMessage(FAKE_REPLY))));
			}

			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.just(call(prompt));
			}
		};
	}

}
