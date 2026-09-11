package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ChatMessageCommand.java
 * Description : 인증된 클라이언트의 채팅 발화를 내부 처리용으로 표현하며, traceId는 개별 메시지
 *               처리 시도를 식별한다.
 *
 *               사람을 두 가지로 들고 다닌다 — 저장(msg의 작성자 참가 행 조회)에는 내부
 *               app_user.id가, 방송 프레임에는 subject와 표시 이름이 필요하기 때문이다(이슈 #130).
 *
 * @param from 작성자의 내부 app_user.id. 저장 경로에서만 쓰고 밖으로 내보내지 않는다
 * @param fromSubject 작성자의 Keycloak subject. 프레임이 사람을 가리키는 값
 * @param fromDisplayName 작성자의 표시 이름
 * @param modelId 이 발화가 @AI 멘션일 때 쓸 게이트웨이 모델 별칭. null이면 서버 기본값(이슈 #160)
 */
record ChatMessageCommand(UUID threadId, UUID from, String fromSubject, String fromDisplayName,
		String content, String modelId, String traceId) {
}
