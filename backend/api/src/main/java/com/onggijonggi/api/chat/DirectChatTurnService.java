package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgIdmKey;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgIdmKeyRepository;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
 *
 *               idempotencyKey(= 인바운드의 clientMsgId, 이슈 #233)가 이미 쓰인 적 있으면 새로
 *               저장하지 않고 그 결과를 그대로 돌려준다(StoredTurn.replay()=true) — WS 전송
 *               확인을 잃은 재시도가 중복 HUMAN 메시지를 만드는 걸 막는다. 이 클래스는 DB만
 *               보므로 그 AGENT 턴이 지금 메모리에서 실제로 진행 중인지는 판단하지 못한다 —
 *               그 판단과, 서버 재시작 등으로 고아가 된 PENDING을 복구하는 것은 호출부
 *               (ThreadWebSocketHandler·ThreadMessageDispatcher)의 몫이다(recoverOrphanedTurnBlocking
 *               참고).
 */
@Service
public class DirectChatTurnService {

	/** 이 기간이 지난 키는 재사용하지 않는다 — thr_idm_key(#149)의 24시간보다 훨씬 짧다.
	 * 채팅 재시도는 보통 수초 안에 일어나는 일이라 짧은 TTL로도 실제 재시도는 다 잡히고,
	 * 너무 길면 한참 뒤 같은 내용을 진짜로 다시 보내고 싶은 사용자의 새 메시지까지 막는다. */
	private static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofMinutes(5);

	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final MsgRepository msgRepository;
	private final MsgIdmKeyRepository msgIdmKeyRepository;

	public DirectChatTurnService(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository,
			MsgRepository msgRepository, MsgIdmKeyRepository msgIdmKeyRepository) {
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.msgRepository = msgRepository;
		this.msgIdmKeyRepository = msgIdmKeyRepository;
	}

	/** WS bootstrap 전용 — HUMAN·PENDING AGENT를 같은 트랜잭션에서 만든다(이슈 #162). */
	@Transactional
	public StoredTurn prepareOrCreateWithPendingAgentBlocking(UUID threadId, UUID userId, String content,
			String title, String idempotencyKey) {
		Optional<StoredTurn> replay = checkIdempotency(threadId, userId, idempotencyKey, content);
		if (replay.isPresent()) {
			return replay.get();
		}
		return thrRepository.findByIdForSeqUpdate(threadId)
				.map(thread -> appendToExisting(thread, userId, content, idempotencyKey))
				.orElseGet(() -> create(threadId, userId, content, title, idempotencyKey));
	}

	/** WS bootstrap의 PK 경합 재시도 전용. 새 DIRECT 생성의 PK 경합 뒤엔 이 경로만 재시도한다.
	 * 타인·COLLAB은 모두 404다. */
	@Transactional
	public StoredTurn prepareExistingWithPendingAgentBlocking(UUID threadId, UUID userId, String content,
			String idempotencyKey) {
		Optional<StoredTurn> replay = checkIdempotency(threadId, userId, idempotencyKey, content);
		if (replay.isPresent()) {
			return replay.get();
		}
		Thr thread = thrRepository.findByIdForSeqUpdate(threadId).orElseThrow(DirectChatTurnService::notFound);
		return appendToExisting(thread, userId, content, idempotencyKey);
	}

	/**
	* 서버 재시작 등으로 이전 시도의 AGENT 턴이 메모리 어디에도 없이 DB에만 PENDING으로 남은
	* (고아) 경우를 복구한다(이슈 #233). 그 msg를 FAILED로 닫고, 옛 idempotency key 행을
	* 지운 뒤(같은 키로 새 행을 다시 넣어야 해서 soft-invalidate가 아니라 DELETE다), 이번
	* 요청을 처음부터 다시(키가 없었던 것처럼) 진행한다 — 결과는 평범한 첫 이어쓰기 발화와
	* 같다. 호출부가 "PENDING인데 메모리엔 없다"를 이미 확인한 뒤에만 불러야 한다.
	*/
	@Transactional
	public StoredTurn recoverOrphanedTurnBlocking(UUID threadId, UUID userId, String content, String idempotencyKey,
			UUID orphanedAgentMsgId) {
		Msg orphaned = msgRepository.findById(orphanedAgentMsgId).orElseThrow(DirectChatTurnService::notFound);
		orphaned.fail(MsgStatus.FAILED);
		msgRepository.save(orphaned);
		// 즉시 실행되는 삭제여야 한다 — 아래에서 appendToExisting이 곧 같은 키로 새 행을
		// 저장하는데, 일반 delete()는 flush까지 미뤄져 Hibernate 기본 순서(INSERT 먼저)상
		// 새 INSERT가 옛 행이 남은 채로 나가 유니크 인덱스와 충돌한다.
		msgIdmKeyRepository.deleteImmediatelyByUserIdAndKey(userId, idempotencyKey);

		Thr thread = thrRepository.findByIdForSeqUpdate(threadId).orElseThrow(DirectChatTurnService::notFound);
		return appendToExisting(thread, userId, content, idempotencyKey);
	}

	/**
	* 유효한 키가 있으면 그 결과를 replay=true로 돌려준다. content가 다르거나 threadId가
	* 다르면(같은 사용자가 같은 clientMsgId를 다른 스레드에 재사용하는 경우 — 정상 경로에서는
	* 안 나오지만, 우연·오용 모두 이 방으로 다른 방의 메시지·답변이 섞여 들어가는 걸 막는다)
	* 클라이언트 버그·키 재사용 실수로 보고 거절한다 — 정상 재시도 경로(§6 참고, chat.tsx의
	* retryLatestTurn이 같은 clientMsgId로 재전송)는 원본 content·같은 스레드를 그대로
	* 재사용하므로 이 분기는 순수 방어용이다. 키가 없으면 새 요청으로 본다(empty). TTL을
	* 넘겼으면 옛 행을 지우고 새 요청으로 본다 — 일반 delete()로는 부족하다(아래 참고).
	*/
	private Optional<StoredTurn> checkIdempotency(UUID threadId, UUID userId, String idempotencyKey, String content) {
		Optional<MsgIdmKey> existing = msgIdmKeyRepository.findByUserIdAndKey(userId, idempotencyKey);
		if (existing.isEmpty()) {
			return Optional.empty();
		}
		MsgIdmKey key = existing.get();
		if (isExpired(key)) {
			// 즉시 실행되는 삭제여야 한다 — 이 메서드가 empty를 반환한 뒤 곧 호출부가 같은
			// 키로 새 행을 저장하는데, 일반 delete()는 flush까지 미뤄져 Hibernate 기본 순서
			// (INSERT 먼저)상 새 INSERT가 옛 행이 남은 채로 나가 유니크 인덱스와 충돌한다
			// (PR #239 리뷰로 확인됨 — Mockito 단위 테스트로는 이 순서 문제를 못 잡는다).
			msgIdmKeyRepository.deleteImmediatelyByUserIdAndKey(userId, idempotencyKey);
			return Optional.empty();
		}
		if (!key.getContent().equals(content) || !key.getThrId().equals(threadId)) {
			throw new IdempotencyKeyConflictException();
		}
		Msg agentMsg = msgRepository.findById(key.getAgentMsgId()).orElseThrow(DirectChatTurnService::notFound);
		return Optional.of(new StoredTurn(key.getHumanMsgId(), key.getHumanSeq(), key.getAgentMsgId(),
				key.getThrId(), key.getAgentSeq(), true, agentMsg.getStatus()));
	}

	private boolean isExpired(MsgIdmKey key) {
		return key.getCreatedAt().isBefore(Instant.now().minus(IDEMPOTENCY_KEY_TTL));
	}

	private StoredTurn create(UUID threadId, UUID userId, String content, String title, String idempotencyKey) {
		Thr thread = thrRepository.save(Thr.direct(threadId, userId, title));
		ThrMbr owner = thrMbrRepository.save(new ThrMbr(threadId, userId, ThrMbrRole.OWNER, userId));
		return persistTurn(thread, owner, content, userId, idempotencyKey);
	}

	private StoredTurn appendToExisting(Thr thread, UUID userId, String content, String idempotencyKey) {
		if (thread.getKind() != ThrKind.DIRECT || !userId.equals(thread.getDrcOwnUserId())) {
			throw notFound();
		}
		ThrMbr owner = thrMbrRepository.findByThrIdAndUserIdAndRoleAndStatus(thread.getId(), userId,
				ThrMbrRole.OWNER, ThrMbrStatus.ACTIVE).orElseThrow(DirectChatTurnService::notFound);
		return persistTurn(thread, owner, content, userId, idempotencyKey);
	}

	private StoredTurn persistTurn(Thr thread, ThrMbr owner, String content, UUID userId, String idempotencyKey) {
		long humanSeq = thread.reserveSeqBlock(2);
		long agentSeq = humanSeq + 1;
		UUID humanMsgId = UUID.randomUUID();
		UUID agentMsgId = UUID.randomUUID();
		msgRepository.save(Msg.human(humanMsgId, thread.getId(), humanSeq, owner.getId(), content));
		msgRepository.save(Msg.pendingAgent(agentMsgId, thread.getId(), agentSeq));
		msgIdmKeyRepository.save(new MsgIdmKey(userId, idempotencyKey, content, thread.getId(), humanMsgId, humanSeq,
				agentMsgId, agentSeq));
		return new StoredTurn(humanMsgId, humanSeq, agentMsgId, thread.getId(), agentSeq, false, MsgStatus.PENDING);
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	/** replay 응답 구성용(이슈 #233) — 이미 COMPLETE인 AGENT 메시지의 저장된 내용을 그대로 돌려준다. */
	public Optional<Msg> findAgentMessage(UUID agentMsgId) {
		return msgRepository.findById(agentMsgId);
	}

	/**
	* 스트림 완료 시 어느 PENDING AGENT 메시지를 COMPLETE로 닫을지 caller에게 전달한다.
	* humanMessageId·humanSeq는 WS dispatcher가 방송 프레임을 이미 저장된 값과 맞추는 데 쓴다(이슈 #162).
	*
	* replay가 true면 이 결과는 새로 저장된 게 아니라 기존 idempotency key가 가리키던 값이다
	* (이슈 #233) — 호출부가 agentStatus로 그 AGENT 턴이 이미 끝났는지(COMPLETE·FAILED·
	* CANCELLED·DENIED) 아직 PENDING인지 보고 재접속 응답을 분기한다.
	*/
	public record StoredTurn(UUID humanMessageId, long humanSeq, UUID agentMessageId, UUID threadId, long agentSeq,
			boolean replay, MsgStatus agentStatus) {
	}

}
