package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ChatMessageFrame.java
 * Description : 참여자 간 일반 대화 메시지 프레임. AI 호출 여부에 따른 라우팅 정책(단순
 *               브로드캐스트 vs LLM 파이프라인 호출)은 GitHub 이슈 #13(협업 채팅방 코어)에서
 *               결정 중이니 확정 여부는 그쪽을 확인한다.
 *
 *               작성자를 subject로 가리키고 표시 이름을 함께 싣는다(이슈 #130). 이전에는 내부
 *               app_user.id를 실었는데, 그 값은 참여자 관리 API가 쓰는 subject와 맞물리지 않아
 *               클라이언트가 "이 사람이 명단의 누구인지" 맞춰볼 수 없었다.
 *
 * @param from 작성자의 Keycloak subject
 * @param fromDisplayName 작성자의 표시 이름
 */
public record ChatMessageFrame(UUID sessionId, String from, String fromDisplayName, String content)
		implements WsFrame {
}
