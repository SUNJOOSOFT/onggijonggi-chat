package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.ThrMbrRole;

/**
 * Class Name : ParticipantView.java
 * Description : GET /api/collab/threads/{threadId}/participants 응답 항목(이슈 #23). 서비스 레벨
 *               ThreadParticipant(subject, role, self, pending)에 표시 이름을 얹은 컨트롤러 전용
 *               표현이다.
 *
 *               CollabThreadSummary.participants(표시 이름만 담은 List<String>)와는 모양이 달라
 *               재사용할 기존 필드명 선례가 없었다 — displayName은 KeycloakAdminClient.displayName()
 *               메서드명에 맞춘 것이다.
 * @param subject 참가자의 Keycloak subject
 * @param role 이 방에서의 역할
 * @param self 호출자 자신의 참가 행인지
 * @param displayName Keycloak Admin API로 해석한 표시 이름. 못 찾으면 subject로 대체된다
 * @param pending 아직 참가가 아니라 대기 초대인지(이슈 #172) — 화면이 "대기 중"으로 구분해
 *                보여주고 OWNER에게 취소를 내준다
 */
public record ParticipantView(String subject, ThrMbrRole role, boolean self, String displayName,
		boolean pending) {
}
