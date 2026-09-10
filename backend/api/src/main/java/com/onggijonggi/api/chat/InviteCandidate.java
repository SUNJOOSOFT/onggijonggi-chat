package com.onggijonggi.api.chat;

/**
 * Class Name : InviteCandidate.java
 * Description : GET /api/collab/threads/{threadId}/participants/candidates 응답 항목(이슈 #172).
 *
 *               초대창이 subject(UUID)를 손으로 받던 것을 대체한다. 사람은 displayName만 보고
 *               고르고, subject는 화면이 그대로 초대 API에 되돌려 준다 — 그 값을 앱 안에서
 *               알아낼 경로가 없다는 것이 이 검색이 생긴 이유다.
 *
 *               이미 참가 중이거나 이미 대기 초대가 있는 사람은 서버가 걸러서 내려준다. 고를 수
 *               없는 항목을 보여주고 눌렀을 때 실패시키는 것보다 낫다.
 * @param subject 초대에 그대로 넘길 Keycloak subject
 * @param displayName 화면에 보일 이름(username = OIDC preferred_username)
 */
public record InviteCandidate(String subject, String displayName) {
}
