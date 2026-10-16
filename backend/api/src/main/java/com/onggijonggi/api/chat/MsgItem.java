package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : MsgItem.java
 * Description : GET /api/collab/threads/{threadId}/messages 응답 항목.
 * @param id 메시지 id
 * @param seq 스레드 내 순서(정본, created_at 아님)
 * @param athKind HUMAN/AGENT/SYSTEM
 * @param status PENDING/COMPLETE/DENIED/FAILED/CANCELLED
 * @param content 메시지 본문(PENDING이면 빈 문자열)
 * @param authorSubject HUMAN 메시지 작성자의 Keycloak subject. WS 프레임의 from과 같은 값이라,
 *        이력과 실시간이 같은 사람을 같은 값으로 가리킨다(이슈 #190). AGENT·SYSTEM은 null이다.
 * @param authorDisplayName HUMAN 메시지 작성자의 표시 이름. DB에 스냅샷을 남기지 않고 요청마다
 *        Keycloak을 다시 조회하므로, 이름이 바뀌면 과거 메시지도 최신 이름을 따라간다(이슈 #147).
 *        AGENT·SYSTEM은 작성자가 없어 null이다.
 * @param createdAt 메시지 생성 시각
 * @param completedAt 완료 시각(PENDING이면 null)
 * @param attachments HUMAN 메시지에 실린 첨부. 없으면 빈 배열이다. WS chat.message의 같은 필드와 짝이다
 * @param citations 방 문서 검색으로 찾은 근거(이슈 #347). 근거 없이 답했거나 HUMAN·SYSTEM 메시지면
 *        빈 배열이다 — 실시간 스트리밍 DONE 프레임과 같은 값을 이력 조회에서도 복원한다
 * @param agent `@SOL`·`@FIN`·`@LAW`로 멘션해 역할별 페르소나로 답했을 때만 채워진다(이슈 #357).
 *        `@AI`로 답했거나 HUMAN·SYSTEM 메시지면 null이다 — WS chat.answer의 같은 필드와 짝이다
 * @param membership SYSTEM 메시지가 입퇴장 줄이면 그 내용. 그 밖의 메시지는 null이다. 표시 이름은 authorDisplayName처럼
 *        요청마다 다시 조회한다 — WS participant.changed의 msgId·seq와 짝이다
 * @param approval SYSTEM 메시지가 승인 요청·처리 줄이면 그 내용. 그 밖의 메시지는 null이다 — WS approval.changed의
 *        msgId·seq와 짝이다
 */
public record MsgItem(
		UUID id,
		long seq,
		String athKind,
		String status,
		String content,
		String authorSubject,
		String authorDisplayName,
		Instant createdAt,
		Instant completedAt,
		List<MsgFileView> attachments,
		List<Citation> citations,
		Agent agent,
		Membership membership,
		Approval approval
) {

	/**
	* @param event JOINED/LEFT
	* @param subject 들어오거나 나간 사람의 Keycloak subject
	* @param displayName 그 사람의 표시 이름. 찾지 못하면 subject다
	*/
	public record Membership(String event, String subject, String displayName) {
	}

	/**
	* @param event REQUESTED/APPROVED/REJECTED
	* @param requesterDisplayName 요청한 사람의 표시 이름. 찾지 못하면 subject다
	* @param targetDisplayName 지정된 사람의 표시 이름. 찾지 못하면 subject다
	* @param originMsgId 승인을 요청한 원글의 id. 이 필드가 생기기 전에 저장된 줄은 null이다
	* @param reason 반려 사유. 반려가 아니거나 사유를 적지 않았으면 null이다
	*/
	public record Approval(String event, UUID approvalId, String requesterSubject, String requesterDisplayName,
			String targetSubject, String targetDisplayName, UUID originMsgId, String reason) {
	}

	/** pyl_json은 종류마다 담는 것이 달라, AGENT만 에이전트 표시로·SYSTEM만 입퇴장 줄로 읽는다. */
	static MsgItem from(Msg msg, String authorSubject, String authorDisplayName, List<MsgFileView> attachments,
			Membership membership, Approval approval, ObjectMapper objectMapper) {
		return new MsgItem(msg.getId(), msg.getSeq(), msg.getAthKind().name(), msg.getStatus().name(),
				msg.getContent(), authorSubject, authorDisplayName, msg.getCreatedAt(), msg.getCompletedAt(),
				attachments, Citation.fromSrcJson(objectMapper, msg.getSrcJson()),
				msg.getAthKind() == AthKind.AGENT ? Agent.fromPylJson(objectMapper, msg.getPylJson()) : null,
				membership, approval);
	}

}
