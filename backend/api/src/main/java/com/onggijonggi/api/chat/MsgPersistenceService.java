package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
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

	/**
	* @AI 멘션에 쓸 LLM 문맥을 이번에 보낸 메시지 저장 전에 먼저 읽어, 방금 보낸 메시지가 문맥에
	* 중복으로 끼는 걸 막는다(이슈 #100). 한 트랜잭션으로 묶어 "조회 후 저장" 순서를 보장한다 —
	* 별개의 두 호출로 나누면 그 사이에 순서가 뒤집힐 수 있다.
	*/
	@Transactional
	public List<Msg> persistHumanMessageAndFetchContextBlocking(UUID thrId, UUID userId, String content,
			int contextLimit) {
		List<Msg> priorContext = recentCompleteContextBlocking(thrId, contextLimit);
		persistHumanMessageBlocking(thrId, userId, content);
		return priorContext;
	}

	/**
	* 오래된 것부터(시간순) 반환한다 — LLM 프롬프트에 그대로 이어 붙일 수 있게. contextLimit이
	* 0이면(문맥 끄기) 쿼리 없이 빈 리스트를 바로 반환한다 — PageRequest는 pageSize 1 이상을
	* 요구해 0을 그대로 넘기면 예외가 난다.
	*/
	@Transactional
	public List<Msg> recentCompleteContextBlocking(UUID thrId, int contextLimit) {
		if (contextLimit == 0) {
			return List.of();
		}
		List<Msg> recent = new ArrayList<>(msgRepository.findByThrIdAndStatusOrderBySeqDesc(thrId,
				MsgStatus.COMPLETE, PageRequest.of(0, contextLimit)));
		Collections.reverse(recent);
		return recent;
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
