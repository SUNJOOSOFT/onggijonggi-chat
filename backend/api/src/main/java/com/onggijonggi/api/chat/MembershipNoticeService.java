package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : MembershipNoticeService.java
 * Description : 참가자가 되거나 참가가 끝난 순간 협업방 대화 흐름에 입퇴장 줄을 남긴다. SYSTEM 메시지로
 *               저장해 그 순간 접속해 있지 않던 사람도 이력에서 본다.
 *
 *               참가 기록과 같은 트랜잭션으로 묶지 않는다 — 줄이 빠지는 것보다 참가 변경이 실패하는 쪽이
 *               더 나쁘다. 방 생성은 예외로 CollabThreadCreationService가 같은 트랜잭션에서 직접 남긴다.
 *
 *               워크스페이스 권한을 잃는 것은 여기 오지 않는다. 그쪽은 참가 기록을 건드리지 않고 판정만
 *               막아(ThreadMembershipService) 남길 사건이 없고, 참여자 명단이 "권한 없음"으로 보여준다.
 */
@Service
public class MembershipNoticeService {

	private final MsgRepository msgRepository;
	private final ThreadMessageDispatcher threadMessageDispatcher;
	private final ObjectMapper objectMapper;

	public MembershipNoticeService(MsgRepository msgRepository, ThreadMessageDispatcher threadMessageDispatcher,
			ObjectMapper objectMapper) {
		this.msgRepository = msgRepository;
		this.threadMessageDispatcher = threadMessageDispatcher;
		this.objectMapper = objectMapper;
	}

	/** 블로킹이라 호출부가 이벤트 루프 밖이어야 한다. */
	public Msg recordBlocking(UUID threadId, MembershipNotice.Event event, String subject) {
		long seq = threadMessageDispatcher.allocateSeqBlocking(threadId);
		return msgRepository.save(newNotice(threadId, seq, event, subject, objectMapper));
	}

	static Msg newNotice(UUID threadId, long seq, MembershipNotice.Event event, String subject,
			ObjectMapper objectMapper) {
		return Msg.system(threadId, seq, "", new MembershipNotice(event, subject).toPylJson(objectMapper));
	}

}
