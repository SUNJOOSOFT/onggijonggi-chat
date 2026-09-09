package com.onggijonggi.api.chat;

/**
 * Class Name : RiskClassifier.java
 * Description : 사람 발화 하나가 위험한지 판단하는 경계(#28). 실 판정은 LLM 호출이라 테스트에서
 *               갈아 끼울 수 있게 인터페이스로 뗀다 — LlmChatStreamService/ChatStreamService와
 *               같은 이유다.
 */
public interface RiskClassifier {

	boolean isRisky(String content);

}
