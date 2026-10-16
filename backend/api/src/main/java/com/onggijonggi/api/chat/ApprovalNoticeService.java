package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ApprovalNoticeService.java
 * Description : 승인 요청이 생기거나 처리된 순간 협업방 대화 흐름에 승인 줄을 SYSTEM 메시지로 남기고, 듣고 있는 화면에
 *               ApprovalChangedFrame을 보낸다(#407). 줄을 저장해 두므로 그 순간 접속해 있지 않던 사람도 이력에서 본다.
 *
 *               요청·처리와 같은 트랜잭션으로 묶지 않는다 — 줄이나 알림이 빠지는 것보다 요청·처리가 실패하는 쪽이
 *               더 나쁘다(MembershipNoticeService와 같은 원칙). 그래서 여기서 나는 실패는 던지지 않고 기록만 한다.
 */
@Service
public class ApprovalNoticeService {

	private static final Logger log = LoggerFactory.getLogger(ApprovalNoticeService.class);

	private final MsgRepository msgRepository;
	private final ThreadMessageDispatcher threadMessageDispatcher;
	private final RoomSessionRegistry roomSessionRegistry;
	private final RankedDisplayNames rankedDisplayNames;
	private final ObjectMapper objectMapper;

	public ApprovalNoticeService(MsgRepository msgRepository, ThreadMessageDispatcher threadMessageDispatcher,
			RoomSessionRegistry roomSessionRegistry, RankedDisplayNames rankedDisplayNames, ObjectMapper objectMapper) {
		this.msgRepository = msgRepository;
		this.threadMessageDispatcher = threadMessageDispatcher;
		this.roomSessionRegistry = roomSessionRegistry;
		this.rankedDisplayNames = rankedDisplayNames;
		this.objectMapper = objectMapper;
	}

	/** 블로킹이라 호출부가 이벤트 루프 밖이어야 한다. */
	public void recordBlocking(UUID threadId, ApprovalNotice notice) {
		Msg line = saveLine(threadId, notice);
		try {
			roomSessionRegistry.notifyIfListening(threadId, new ApprovalChangedFrame(threadId, notice.approvalId(),
					notice.event(), notice.requesterSubject(), displayName(notice.requesterSubject()),
					notice.targetSubject(), displayName(notice.targetSubject()),
					line == null ? null : line.getId(), line == null ? null : line.getSeq(),
						notice.originMsgId(), notice.reason()));
		} catch (RuntimeException failure) {
			log.error("승인 알림 전송 실패 threadId={} approvalId={}", threadId, notice.approvalId(), failure);
		}
	}

	private Msg saveLine(UUID threadId, ApprovalNotice notice) {
		try {
			long seq = threadMessageDispatcher.allocateSeqBlocking(threadId);
			return msgRepository.save(Msg.system(threadId, seq, "", notice.toPylJson(objectMapper)));
		} catch (RuntimeException failure) {
			log.error("승인 줄 저장 실패 threadId={} approvalId={}", threadId, notice.approvalId(), failure);
			return null;
		}
	}

	/** Keycloak 조회가 실패하거나 이름이 없으면 subject로 대신한다 — 이름 하나 때문에 알림을 잃지 않는다. */
	private String displayName(String subject) {
		try {
			return rankedDisplayNames.displayName(subject).blockOptional().flatMap(name -> name).orElse(subject);
		} catch (RuntimeException failure) {
			log.warn("승인 알림 표시 이름 조회 실패 subject={}", subject, failure);
			return subject;
		}
	}

}
