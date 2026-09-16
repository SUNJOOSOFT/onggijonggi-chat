package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : DirectChatTurnService.java
 * Description : WS 1:1 발화를 공용 thr/msg에 저장한다. 새 DIRECT Thread·OWNER 참여·HUMAN·PENDING
 *               AGENT 메시지를 하나의 트랜잭션으로 만들고, 기존 Thread에서는 행 잠금으로 HUMAN·
 *               AGENT 두 seq를 함께 예약한다. FIFO 초과 턴을 즉시 DENIED로 전이하려면 AGENT
 *               PENDING 행이 미리 있어야 해서, HUMAN 저장과 항상 같은 트랜잭션에서 만든다
 *               (이슈 #162).
 */
@Service
public class DirectChatTurnService {

	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final MsgRepository msgRepository;

	public DirectChatTurnService(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository,
			MsgRepository msgRepository) {
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.msgRepository = msgRepository;
	}

	/** WS bootstrap 전용 — HUMAN·PENDING AGENT를 같은 트랜잭션에서 만든다(이슈 #162). */
	@Transactional
	public StoredTurn prepareOrCreateWithPendingAgentBlocking(UUID threadId, UUID userId, String content,
			String title) {
		return thrRepository.findByIdForSeqUpdate(threadId)
				.map(thread -> appendToExisting(thread, userId, content))
				.orElseGet(() -> create(threadId, userId, content, title));
	}

	/** WS bootstrap의 PK 경합 재시도 전용. 새 DIRECT 생성의 PK 경합 뒤엔 이 경로만 재시도한다.
	 * 타인·COLLAB은 모두 404다. */
	@Transactional
	public StoredTurn prepareExistingWithPendingAgentBlocking(UUID threadId, UUID userId, String content) {
		Thr thread = thrRepository.findByIdForSeqUpdate(threadId).orElseThrow(DirectChatTurnService::notFound);
		return appendToExisting(thread, userId, content);
	}

	private StoredTurn create(UUID threadId, UUID userId, String content, String title) {
		Thr thread = thrRepository.save(Thr.direct(threadId, userId, title));
		ThrMbr owner = thrMbrRepository.save(new ThrMbr(threadId, userId, ThrMbrRole.OWNER, userId));
		return persistTurn(thread, owner, content);
	}

	private StoredTurn appendToExisting(Thr thread, UUID userId, String content) {
		if (thread.getKind() != ThrKind.DIRECT || !userId.equals(thread.getDrcOwnUserId())) {
			throw notFound();
		}
		ThrMbr owner = thrMbrRepository.findByThrIdAndUserIdAndRoleAndStatus(thread.getId(), userId,
				ThrMbrRole.OWNER, ThrMbrStatus.ACTIVE).orElseThrow(DirectChatTurnService::notFound);
		return persistTurn(thread, owner, content);
	}

	private StoredTurn persistTurn(Thr thread, ThrMbr owner, String content) {
		long humanSeq = thread.reserveSeqBlock(2);
		long agentSeq = humanSeq + 1;
		UUID humanMsgId = UUID.randomUUID();
		UUID agentMsgId = UUID.randomUUID();
		msgRepository.save(Msg.human(humanMsgId, thread.getId(), humanSeq, owner.getId(), content));
		msgRepository.save(Msg.pendingAgent(agentMsgId, thread.getId(), agentSeq));
		return new StoredTurn(humanMsgId, humanSeq, agentMsgId, thread.getId(), agentSeq);
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	/**
	* 스트림 완료 시 어느 PENDING AGENT 메시지를 COMPLETE로 닫을지 caller에게 전달한다.
	* humanMessageId·humanSeq는 WS dispatcher가 방송 프레임을 이미 저장된 값과 맞추는 데 쓴다(이슈 #162).
	*/
	public record StoredTurn(UUID humanMessageId, long humanSeq, UUID agentMessageId, UUID threadId, long agentSeq) {
	}

}
