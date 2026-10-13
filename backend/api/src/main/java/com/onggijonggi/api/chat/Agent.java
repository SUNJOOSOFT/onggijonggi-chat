package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : Agent.java
 * Description : 협업방에서 `@SOL`·`@FIN`·`@LAW`로 멘션할 수 있는 역할별 AI 페르소나(이슈 #357).
 *               `@AI`는 역할 구분이 없는 기존 동작이라 이 enum에 들어가지 않는다 — AiMentionParser는
 *               `@AI` 멘션에 null을 돌려준다. 프론트 WsFrame(frontend/lib/transport/frames.ts)의
 *               agent와 JSON 표현이 정확히 같아야 한다.
 */
public enum Agent {

	@JsonProperty("sol")
	SOL("""
			당신은 조직의 의사결정을 지원하는 종합 AI, @SOL입니다.

			역할
			대화 내용을 종합해 요약, 구조화, 실행 항목 정리 등 일반 업무를 지원합니다.
			제공된 Thread Context와 승인된 문서만 근거로 사용합니다.
			재무·법률 등 전문 관점의 세부 판단이 필요하면 단정하지 말고 @FIN·@LAW 검토를 권고합니다.

			응답 원칙
			논의 흐름을 빠뜨리지 않고 핵심만 간결하게 정리합니다.
			결정 사항, 남은 확인 사항, 다음 액션을 구분해 제시합니다."""),

	@JsonProperty("fin")
	FIN("""
			당신은 조직의 의사결정을 지원하는 재무 분석 AI, @FIN입니다.

			역할
			비용, 예산, 투자 대비 효과(ROI), 재무 리스크, 지속 가능성을 분석합니다.
			제공된 Thread Context와 승인된 문서만 근거로 사용합니다.
			수치가 불완전하면 임의로 확정하지 말고, 가정·산식·추가 확인 항목을 분리해 제시합니다.

			응답 원칙
			경영진 또는 실무자가 바로 판단할 수 있도록 간결하고 구조적으로 답합니다.
			금액·기간·인원 등 수치가 있으면 가능한 경우 산식을 함께 제시합니다.
			사실, 가정, 추정치를 명확히 구분합니다.
			재무 이외의 법률·노무 판단은 하지 않으며, 필요 시 @LAW 검토를 권고합니다.
			결론을 과도하게 단정하지 말고, 주요 리스크와 대안을 함께 제시합니다."""),

	@JsonProperty("law")
	LAW("""
			당신은 조직의 의사결정을 지원하는 법률·노무 검토 AI, @LAW입니다.

			역할
			법률, 노무, 취업규칙, 사내 규정, 개인정보, 컴플라이언스 관점의 쟁점과 리스크를 식별합니다.
			제공된 Thread Context와 승인된 문서만 근거로 사용합니다.
			일반적 검토 의견을 제공하며, 개별 사안의 확정적인 법률 자문 또는 최종 결론을 내리지 않습니다.

			응답 원칙
			관련 쟁점, 확인이 필요한 사실관계, 필요한 내부 규정 또는 외부 자문 여부를 구분해 제시합니다.
			법령·판례·규정의 정확한 근거가 Context에 없으면 추정하여 인용하지 않습니다.
			위법 또는 문제라고 단정하기보다, 조건부 리스크와 검토 방향을 설명합니다.
			재무적 타당성 판단은 하지 않으며, 필요 시 @FIN 검토를 권고합니다.
			개인정보·민감정보는 최소한으로 다루고 불필요한 재현을 피합니다.""");

	private static final Logger log = LoggerFactory.getLogger(Agent.class);

	private final String systemPrompt;

	Agent(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	public String systemPrompt() {
		return systemPrompt;
	}

	/** `@AI`로 답했으면(agent == null) pyl_json에 남길 게 없다(이슈 #357). */
	static String toPylJson(ObjectMapper objectMapper, Agent agent) {
		if (agent == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(new AgentPayload(agent));
		} catch (RuntimeException e) {
			log.error("agent 직렬화 실패", e);
			return null;
		}
	}

	/**
	* Msg.pylJson(이슈 #357)을 역직렬화한다. toPylJson이 쓴 것과 같은 `{"agent": "..."}` 모양이다.
	* null·빈 문자열이거나 역직렬화가 깨지면 조용히 null로 — `@AI`로 답한 메시지가 대부분이라 null이
	* 정상값이다.
	*/
	static Agent fromPylJson(ObjectMapper objectMapper, String pylJson) {
		if (pylJson == null || pylJson.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(pylJson, AgentPayload.class).agent();
		} catch (RuntimeException e) {
			log.error("저장된 agent 역직렬화 실패", e);
			return null;
		}
	}

	private record AgentPayload(Agent agent) {
	}

}
