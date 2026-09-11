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
 *               드러내기 위함이다. 협업방 경로의 seq는 호출부(CollabMessageDispatcher)가 블록으로
 *               미리 예약해 넘긴다(이슈 #190) — 방송 프레임에 seq를 실어야 해서 저장을 기다릴 수
 *               없기 때문이다. 그래서 이 클래스는 협업 메시지의 채번을 더 이상 하지 않고,
 *               예약해놓고 쓰지 않은 번호가 구멍으로 남는 것을 전제한다.
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
	* seq를 n개 한 번에 예약한다(이슈 #190). 돌려주는 값은 블록의 첫 seq다.
	*
	* 방 워커만 부르므로 방 단위로 직렬이고, 예약한 번호를 메모리에서 하나씩 꺼내 쓰는 동안에는
	* DB를 다시 부르지 않는다 — 방송 전에 DB를 기다리지 않게 하려는 것이 이 메서드의 존재 이유다.
	*/
	@Transactional
	public long allocateSeqBlockBlocking(UUID thrId, int size) {
		return thrRepository.findByIdForSeqUpdate(thrId)
				.orElseThrow(() -> new IllegalStateException("thr not found: " + thrId))
				.reserveSeqBlock(size);
	}

	/**
	* 호출자가 이미 방을 나갔거나 참가자가 아니면(참여 상태가 그 사이 바뀐 경우) 조용히 건너뛴다 —
	* 방송은 이미 끝난 뒤라 저장을 막을 이유가 없고, thr_mbr_id 없이는 msg_human_has_participant를
	* 지킬 수 없기 때문이다.
	*
	* id·seq는 호출부가 방송 프레임에 실은 값 그대로여야 한다 — 화면에 보인 것과 이력이 같은
	* 메시지를 가리켜야 하기 때문이다(이슈 #190).
	*/
	@Transactional
	public Optional<Msg> persistHumanMessageBlocking(UUID msgId, long seq, UUID thrId, UUID userId,
			String content) {
		Optional<ThrMbr> member = thrMbrRepository.findByThrIdAndUserIdAndStatus(thrId, userId, ThrMbrStatus.ACTIVE);
		if (member.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(msgRepository.save(Msg.human(msgId, thrId, seq, member.get().getId(), content)));
	}

	/**
	* @AI 멘션에 쓸 LLM 문맥을 이번에 보낸 메시지 저장 전에 먼저 읽어, 방금 보낸 메시지가 문맥에
	* 중복으로 끼는 걸 막는다(이슈 #100). 한 트랜잭션으로 묶어 "조회 후 저장" 순서를 보장한다 —
	* 별개의 두 호출로 나누면 그 사이에 순서가 뒤집힐 수 있다.
	*/
	@Transactional
	public List<Msg> persistHumanMessageAndFetchContextBlocking(UUID msgId, long seq, UUID thrId, UUID userId,
			String content, int contextLimit) {
		List<Msg> priorContext = recentCompleteContextBlocking(thrId, contextLimit);
		persistHumanMessageBlocking(msgId, seq, thrId, userId, content);
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
	public Msg createPendingAgentMessageBlocking(UUID msgId, long seq, UUID thrId) {
		return msgRepository.save(Msg.pendingAgent(msgId, thrId, seq));
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
