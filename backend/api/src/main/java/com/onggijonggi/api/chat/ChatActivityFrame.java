package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ChatActivityFrame.java
 * Description : AI 턴이 도구를 실행하기 시작했거나(active=true) 끝냈음(false)을 방에 알리는 활동 프레임(이슈 #397).
 *               저장하지 않는다 — 재접속한 화면은 다음 알림부터 본다. 턴이 끝난 뒤의 늦은 알림은 보내지 않는다. 단
 *               "시작"이 턴 종료와 엇갈려 종료 프레임 뒤에 갔을 수 있으면 "끝"을 바로 이어 보낸다.
 *
 *               msgId는 그 턴의 AGENT 메시지 id로, ChatAnswerFrame의 msgId와 같은 턴 식별자다. kind는 종류
 *               코드(WEB_SEARCH·WEB_FETCH·DOCUMENT_SEARCH·DOCUMENT_REVISE·TOOL)이고 문구는 화면이 고른다.
 *               target은 검색어·주소·파일 이름처럼 그 도구가 다루는 대상(없으면 null)이고, agent는 역할
 *               에이전트로 부른 턴이면 그 값이다 — 협업방이 답변 말풍선이 생기기 전에 AI 자리 이름을 붙이는 데 쓴다.
 */
public record ChatActivityFrame(UUID threadId, UUID msgId, UUID turnId, String kind, String target, boolean active,
		Agent agent) implements WsFrame {
}
