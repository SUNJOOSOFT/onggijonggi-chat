package com.onggijonggi.api.chat;

import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Class Name : LlmChatStreamService.java
 * Description : ChatClient.prompt().advisors().stream() 오케스트레이션을 거쳐 실제 LLM(vLLM 등
 *               OpenAI 호환 엔드포인트) 응답을 텍스트 청크 스트림으로 생성하는 구현체. RAG Advisor
 *               Chain은 아직 없어 .advisors()는 빈 채로 호출한다(추후 advisor 삽입 지점).
 */
@Service
public class LlmChatStreamService {

	private final ChatClient chatClient;

	public LlmChatStreamService(ChatClient.Builder chatClientBuilder) {
		this.chatClient = chatClientBuilder.build();
	}

	/**
	 * 화면에서 고른 modelId로 라우팅한다. 이 값은 게이트웨이 model_list의 model_name(별칭)이라
	 * 게이트웨이가 실제 공급자·모델을 찾아준다. 목록에 없는 이름이면 게이트웨이가 거절한다 —
	 * 어떤 이름이 유효한지 아는 곳이 게이트웨이 하나뿐이라 BFF는 따로 대조하지 않는다.
	 */
	public Flux<String> streamChat(ChatStreamRequest request) {
		List<Message> messages = request.messages().stream()
				.map(this::toSpringAiMessage)
				.toList();

		return chatClient.prompt()
				.messages(messages)
				.options(ChatOptions.builder().model(request.modelId()))
				.advisors()
				.stream()
				.content()
				.onErrorMap(LlmChatStreamService::unwrapGatewayRejection);
	}

	/**
	* SDK가 비동기 호출이라 게이트웨이가 거절한 응답이 CompletionException 등에 싸여 올라온다.
	* @ExceptionHandler는 던져진 타입만 보고 원인 체인은 보지 않아, 감싼 채로 두면 전용 핸들러
	* (MODEL_UNAVAILABLE) 대신 catch-all(INTERNAL_ERROR)에 걸린다 — 사용자에게 원인 모를 문구가
	* 나가므로 원래 예외를 꺼내 다시 던진다. 게이트웨이 거절이 아니면 그대로 흘려보낸다.
	*/
	private static Throwable unwrapGatewayRejection(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof OpenAIServiceException) {
				return current;
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return error;
	}

	/** role이 "assistant"/"system"이 아니면 무조건 user로 취급한다(알 수 없는 role도 안전하게 처리). */
	private Message toSpringAiMessage(ChatMessage message) {
		return switch (message.role().toLowerCase()) {
			case "assistant" -> new AssistantMessage(message.content());
			case "system" -> new SystemMessage(message.content());
			default -> new UserMessage(message.content());
		};
	}

}
