package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Class Name : InboundChatMessage.java
 * Description : 참여자가 올려보내는 발화(이슈 #160에서 modelId를 더했다).
 *
 *               modelId는 이 발화가 {@code @AI} 멘션일 때 어느 모델로 답할지다. 비워 보내면
 *               서버 기본값(app.collab.ai.model)을 쓴다 — 협업방 화면에는 아직 모델 선택이 없어
 *               지금은 전부 이 경로로 온다. 필드를 미리 여는 것은 1:1을 이 프레임으로 옮길 때
 *               (#162) 모델 선택이 사라지지 않게 하려는 것이다 — 1:1은 지금
 *               ChatStreamRequest.modelId로 요청마다 모델을 고르고 있고, 그 자리를 여기 만들어
 *               두지 않으면 이관이 곧 기능 후퇴가 된다.
 *
 *               게이트웨이 별칭을 그대로 받는다(LiteLLM model_list의 model_name). 값이 목록에
 *               없으면 게이트웨이가 거절하고, 그 실패는 다른 LLM 오류와 같은 경로로 흐른다 —
 *               서버가 미리 검증하지 않는 이유는 어떤 별칭이 살아 있는지 아는 곳이 게이트웨이뿐이기
 *               때문이다(ModelCatalogService가 매 요청 /v1/models를 되묻는 것과 같은 이유).
 *
 * @param content 발화 원문
 * @param modelId 이 턴에 쓸 게이트웨이 모델 별칭. null이면 서버 기본값
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundChatMessage(String content, String modelId) implements InboundFrame {
}
