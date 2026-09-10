package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.ThrMbrRole;

/**
 * Class Name : ThreadParticipant.java
 * Description : 참여자 명단 조회의 서비스 레벨 표현(이슈 #20·#23). 컨트롤러가 이 값에 표시 이름을
 *               얹어 최종 응답(ParticipantView)을 만든다.
 *
 *               내부 app_user.id는 담지 않는다. WS 프레임은 사람을 내부 id로 가리키고 이 API는
 *               subject로 가리켜 서로 맞물리지 않는데, 어느 쪽으로 맞출지는 이슈 #130에서 정한다 —
 *               여기서 내부 id를 함께 내려주면 그 결정을 미리 한쪽으로 굳혀 버린다.
 * @param subject 참가자의 Keycloak subject
 * @param role 이 방에서의 역할
 * @param self 이 값이 호출자 자신의 참가 행인지(이슈 #23) — 클라이언트가 자기 subject를 알 방법이
 *             없어(next-auth 세션에 노출 안 됨) 서버가 이미 아는 actorUserId로 판정해 알려준다
 * @param pending 아직 참가가 아니라 대기 초대(thr_inv)인지(이슈 #172). 초대해도 명단에 아무것도
 *                뜨지 않아 조용한 실패와 구분되지 않던 것을 없앤다. 이 행은 role을 "받게 될
 *                역할"로 채우고 self는 항상 false다 — 대상은 아직 app_user 행이 없다
 */
public record ThreadParticipant(String subject, ThrMbrRole role, boolean self, boolean pending) {
}
