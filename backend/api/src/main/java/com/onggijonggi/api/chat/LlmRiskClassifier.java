package com.onggijonggi.api.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Class Name : LlmRiskClassifier.java
 * Description : RiskClassifier를 LiteLLM 게이트웨이(ChatClient)로 구현한다(#28). 규칙 기반 대신
 *               LLM 판정 하나로 시작하기로 한 결정(#27 코멘트)에 따라 새 인프라를 들이지 않고
 *               기존 게이트웨이를 재사용한다. 세분화된 위험 유형 분류는 오탐·누락 사례가 쌓인
 *               뒤 후속으로 다룬다 — 지금은 RISKY/SAFE 이진 판정만 한다.
 */
@Component
public class LlmRiskClassifier implements RiskClassifier {

	private static final String SYSTEM_PROMPT = """
			다음 발화가 안전 정책을 위반할 위험이 있는지 판정한다.
			위험하면 RISKY, 아니면 SAFE라고만 답한다. 다른 말은 덧붙이지 않는다.
			""";

	private final ChatClient chatClient;

	private final String modelId;

	public LlmRiskClassifier(ChatClient.Builder chatClientBuilder,
			@Value("${app.collab.risk-check.model:${app.collab.ai.model:${spring.ai.openai.chat.options.model}}}")
			String modelId) {
		this.chatClient = chatClientBuilder.build();
		this.modelId = modelId;
	}

	@Override
	public boolean isRisky(String content) {
		String verdict = chatClient.prompt()
				.system(SYSTEM_PROMPT)
				.user(content)
				.options(ChatOptions.builder().model(modelId))
				.call()
				.content();
		return verdict != null && verdict.strip().toUpperCase().startsWith("RISKY");
	}

}
