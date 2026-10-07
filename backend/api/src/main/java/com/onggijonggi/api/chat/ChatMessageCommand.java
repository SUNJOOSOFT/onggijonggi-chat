package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.MsgFile;
import com.onggijonggi.common.chat.domain.ThrKind;
import java.util.List;
import java.util.UUID;

/**
 * Class Name : ChatMessageCommand.java
 * Description : 인증된 클라이언트의 채팅 발화를 내부 처리용으로 표현하며, traceId는 개별 메시지
 *               처리 시도를 식별한다.
 *
 *               사람을 두 가지로 들고 다닌다 — 저장(msg의 작성자 참가 행 조회)에는 내부
 *               app_user.id가, 방송 프레임에는 subject와 표시 이름이 필요하기 때문이다(이슈 #130).
 *
 * @param kind 이 발화가 속한 Thread 종류. dispatcher가 멘션 파싱·FIFO 초과·취소 인가를
 *             DIRECT·COLLAB으로 가르는 데 쓴다(이슈 #162)
 * @param from 작성자의 내부 app_user.id. 저장 경로에서만 쓰고 밖으로 내보내지 않는다
 * @param fromSubject 작성자의 Keycloak subject. 프레임이 사람을 가리키는 값
 * @param fromDisplayName 작성자의 표시 이름
 * @param files 이 발화에 실린 첨부. WS 핸들러가 본인 것인지 확인을 마친 것이다. 없으면 빈 리스트
 * @param model {@code @AI} 턴에 쓸 게이트웨이 모델 별칭. null이면 서버 기본값(이슈 #160)
 * @param timeZone 보낸 사람 브라우저의 시간대(IANA 이름). {@code @AI} 턴의 현재 시각 도구가 쓴다.
 *                 null이거나 틀리면 서버 기본값(CurrentTimeTool)
 * @param clientMsgId 클라이언트가 만든 임시 메시지 id. 에코에 돌려준다(이슈 #160). 서버는 해석하지
 *                     않는 불투명 문자열로 다룬다 — UUID로 좁히지 않는다(이슈 #224)
 * @param turnId 클라이언트가 만든 턴 식별자. 에코·답변·대기 프레임에 돌려주고 취소 지목에 쓴다(이슈 #160)
 * @param connectionId 이 발화가 들어온 커넥션. 턴 식별자를 이 커넥션 범위에서만 인정하는 데 쓴다
 * @param reservedTurn DIRECT만 채운다. `DirectChatTurnService`가 이미 저장한 HUMAN·PENDING AGENT의
 *                      msgId·seq — dispatcher가 새로 만들지 않고 그대로 재사용한다(이슈 #162)
 */
record ChatMessageCommand(UUID threadId, ThrKind kind, UUID from, String fromSubject, String fromDisplayName,
		String content, List<MsgFile> files, String model, String timeZone, String clientMsgId, UUID turnId, UUID connectionId,
		String traceId, ReservedTurn reservedTurn) {

	List<UUID> fileIds() {
		return files.stream().map(MsgFile::getId).toList();
	}

	/** 방송 프레임에 싣는 모양 — 추출한 텍스트는 빼고 이름만 보인다. */
	List<MsgFileView> fileViews() {
		return files.stream().map(MsgFileView::from).toList();
	}

	/** bootstrap 또는 기존 DIRECT 이어쓰기가 이미 예약한 HUMAN·AGENT 자리(이슈 #162). */
	record ReservedTurn(UUID humanMsgId, long humanSeq, UUID agentMsgId, long agentSeq) {
	}
}
