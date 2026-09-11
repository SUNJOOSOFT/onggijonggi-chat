package com.onggijonggi.api.chat;

import java.util.List;
import java.util.UUID;

/**
 * Class Name : ChatAnswerFrame.java
 * Description : AI 응답 스트리밍(delta)·근거 인용(citations)·진행 상태(status)를 하나로 묶은
 *               프레임(이슈 #10 코멘트 확정 스펙). 기존 chat.token/chat.done 두 타입과 PR #46이
 *               제안 스펙으로 먼저 넣어둔 chat.citation을 이 타입 하나로 흡수한다.
 *
 *               delta·citations는 패킷마다 필요한 것만 채워서 온다 — 둘 다 비어 있는 패킷(예: 상태만
 *               알리는 패킷)도 유효하고, citations를 delta보다 먼저(delta="", status=STREAMING
 *               조합으로) 보낼 수도 있다 — 근거 패널이 답변 완료 전에 먼저 채워지는 UX를 지원하기
 *               위함이다.
 *
 *               restrictedResultsOmitted는 기존 REST /api/chat/citations 응답(CitationsResponse)에
 *               있던 RBAC 소프트 필터링 신호를 그대로 옮긴 것이다. citations와는 서로 독립적인
 *               필드다 — citations가 빈 배열이어도(전부 걸러진 경우) true일 수 있으므로, citations가
 *               비었다고 해서 자동으로 false로 취급하면 안 된다(PR #50 리뷰).
 *
 *               msgId는 이 턴의 AGENT 메시지 id다(이슈 #190). 한 턴이 PENDING msg 행 하나와
 *               1:1이라 이 값이 곧 턴 식별자 역할을 한다(D5) — 같은 턴의 delta들은 모두 같은
 *               msgId·seq를 달고 온다. seq는 턴이 시작되는 순간 확정되므로, 스트리밍 도중
 *               도착한 사람 메시지가 이 답변 앞으로 끼어들지 않는다.
 */
public record ChatAnswerFrame(
		UUID sessionId,
		UUID msgId,
		long seq,
		String delta,
		List<Citation> citations,
		boolean restrictedResultsOmitted,
		ChatAnswerStatus status) implements WsFrame {
}
