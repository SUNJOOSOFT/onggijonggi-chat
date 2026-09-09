package com.onggijonggi.api.chat;

/**
 * Class Name : PresenceParticipant.java
 * Description : presence 프레임이 사람 한 명을 가리키는 방식(이슈 #130). 식별은 subject로 하고
 *               화면 표시는 displayName으로 한다 — 내부 app_user.id는 밖으로 내보내지 않으며,
 *               참여자 관리 API(ParticipantView)와 같은 식별자 체계를 쓴다.
 *
 *               displayName을 프레임에 함께 싣는 것은 presence가 지금 접속 중인 사람만 다루기
 *               때문이다. 각자 자기 토큰의 claim을 들고 들어오므로 방송 시점에 Keycloak을 다시
 *               조회할 일이 없다. 접속 중이 아닌 과거 작성자의 이름은 이력 조회(#147) 몫이다.
 * @param subject 참가자의 Keycloak subject
 * @param displayName JWT claim에서 읽은 표시 이름. claim이 없으면 subject로 대체된다
 */
public record PresenceParticipant(String subject, String displayName) {
}
