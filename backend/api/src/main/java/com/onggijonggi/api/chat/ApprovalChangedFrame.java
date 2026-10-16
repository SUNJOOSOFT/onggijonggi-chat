package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ApprovalChangedFrame.java
 * Description : 승인 요청이 생겼거나 승인·반려로 처리됐음을 그 방 구독자 전원에게 알리는 프레임(#407). 요청한 메시지의
 *               내용은 싣지 않는다 — 누가 누구에게 어떤 일이 있었는지만 알리고, 내용은 요청자와 지정된 사람만 REST
 *               목록으로 읽는다. 그래서 토스트와 모달을 띄울지는 받는 화면이 targetSubject·requesterSubject가 자기 자신인지로
 *               정한다. 저장하지 않는다 — 접속하지 않았던 사람은 목록 조회로 대기 중인 요청을 본다.
 *
 *               msgId·seq는 그 사건으로 대화 흐름에 남긴 승인 줄(ApprovalNotice)의 id와 순서다. 줄을 남기지 못했으면
 *               null이고, 화면은 이 자리에 줄을 넣지 않는다(participant.changed와 같은 규칙).
 * @param event 요청이 생겼는지, 승인·반려로 처리됐는지
 * @param requesterDisplayName 요청한 사람의 표시 이름. 찾지 못하면 subject다
 * @param targetDisplayName 지정된 사람의 표시 이름. 찾지 못하면 subject다
 */
public record ApprovalChangedFrame(UUID threadId, UUID approvalId, ApprovalNotice.Event event,
		String requesterSubject, String requesterDisplayName, String targetSubject, String targetDisplayName,
		UUID msgId, Long seq) implements WsFrame {
}
