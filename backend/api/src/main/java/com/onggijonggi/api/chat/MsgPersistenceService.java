package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : MsgPersistenceService.java
 * Description : msg(V11__message.sql) 저장 로직(03·CORE, 이슈 #18). 전부 블로킹 JPA 호출이라
 *               메서드 이름에 Blocking을 붙인다 — 호출부(CollabMessageDispatcher)가
 *               Mono.fromCallable(...).subscribeOn(boundedElastic())로 감싸야 함을 이름으로
 *               드러내기 위함이다. seq 채번과 msg 저장은 한 트랜잭션으로 묶어, 중간에 실패해도
 *               next_seq만 헛돌지 않게 한다.
 */
@Service
public class MsgPersistenceService {

	private final MsgRepository msgRepository;
	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;

	public MsgPersistenceService(MsgRepository msgRepository, ThrRepository thrRepository,
			ThrMbrRepository thrMbrRepository) {
		this.msgRepository = msgRepository;
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
	}

	/**
	* 호출자가 이미 방을 나갔거나 참가자가 아니면(참여 상태가 그 사이 바뀐 경우) 조용히 건너뛴다 —
	* 방송은 이미 끝난 뒤라 저장을 막을 이유가 없고, thr_mbr_id 없이는 msg_human_has_participant를
	* 지킬 수 없기 때문이다.
	*/
	@Transactional
	public Optional<Msg> persistHumanMessageBlocking(UUID thrId, UUID userId, String content) {
		Optional<ThrMbr> member = thrMbrRepository.findByThrIdAndUserIdAndStatus(thrId, userId, ThrMbrStatus.ACTIVE);
		if (member.isEmpty()) {
			return Optional.empty();
		}
		long seq = thrRepository.allocateNextSeq(thrId);
		return Optional.of(msgRepository.save(Msg.human(thrId, seq, member.get().getId(), content)));
	}

	@Transactional
	public Msg createPendingAgentMessageBlocking(UUID thrId) {
		long seq = thrRepository.allocateNextSeq(thrId);
		return msgRepository.save(Msg.pendingAgent(thrId, seq));
	}

	/** msgId 행이 이미 없거나(DB 문제로 저장이 안 됐던 경우) 다른 이유로 못 찾으면 조용히 넘어간다. */
	@Transactional
	public void completeBlocking(UUID msgId, String content) {
		msgRepository.findById(msgId).ifPresent(msg -> {
			msg.complete(content);
			msgRepository.save(msg);
		});
	}

	@Transactional
	public void failBlocking(UUID msgId, MsgStatus terminalStatus) {
		msgRepository.findById(msgId).ifPresent(msg -> {
			msg.fail(terminalStatus);
			msgRepository.save(msg);
		});
	}

}
