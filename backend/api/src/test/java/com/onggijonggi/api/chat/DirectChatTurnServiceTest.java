package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.AthKind;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Class Name : DirectChatTurnServiceTest.java
 * Description : DIRECT 첫 발화의 원자 구성 요소와 기존 방의 두 seq 예약·소유자 404 은닉,
 *               그리고 idempotency 재시도·충돌·고아 복구(이슈 #233)를 저장소 단위에서 고정한다.
 *               실제 트랜잭션 원자성은 PostgreSQL migration/통합 검증이 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class DirectChatTurnServiceTest {

	@Mock
	private ThrRepository thrRepository;
	@Mock
	private ThrMbrRepository thrMbrRepository;
	@Mock
	private MsgRepository msgRepository;
	@Mock
	private MsgIdmKeyRepository msgIdmKeyRepository;

	private DirectChatTurnService service;

	@BeforeEach
	void setUp() {
		service = new DirectChatTurnService(thrRepository, thrMbrRepository, msgRepository, msgIdmKeyRepository);
	}

	@Test
	void createsDirectThreadOwnerHumanMessageAndPendingAgentAndReservesAgentSequence() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		when(msgIdmKeyRepository.findByUserIdAndKey(userId, "key-1")).thenReturn(Optional.empty());
		when(thrRepository.findByIdForSeqUpdate(threadId)).thenReturn(Optional.empty());
		when(thrRepository.save(any(Thr.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(thrMbrRepository.save(any(ThrMbr.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(msgRepository.save(any(Msg.class))).thenAnswer(invocation -> invocation.getArgument(0));

		DirectChatTurnService.StoredTurn turn = service.prepareOrCreateWithPendingAgentBlocking(threadId, userId,
				"안녕", "안녕", "key-1");

		ArgumentCaptor<Thr> thread = ArgumentCaptor.forClass(Thr.class);
		verify(thrRepository).save(thread.capture());
		assertThat(thread.getValue().getKind()).isEqualTo(ThrKind.DIRECT);
		assertThat(thread.getValue().getDrcOwnUserId()).isEqualTo(userId);
		assertThat(thread.getValue().getNextSeq()).isEqualTo(2);

		ArgumentCaptor<ThrMbr> owner = ArgumentCaptor.forClass(ThrMbr.class);
		verify(thrMbrRepository).save(owner.capture());
		assertThat(owner.getValue().getRole()).isEqualTo(ThrMbrRole.OWNER);
		assertThat(owner.getValue().getStatus()).isEqualTo(ThrMbrStatus.ACTIVE);

		ArgumentCaptor<Msg> messages = ArgumentCaptor.forClass(Msg.class);
		verify(msgRepository, times(2)).save(messages.capture());
		assertThat(messages.getAllValues()).extracting(Msg::getSeq).containsExactly(0L, 1L);
		assertThat(messages.getAllValues()).extracting(Msg::getThrId).containsOnly(threadId);
		assertThat(messages.getAllValues()).extracting(Msg::getAthKind).containsExactly(AthKind.HUMAN, AthKind.AGENT);
		assertThat(messages.getAllValues().get(1).getStatus()).isEqualTo(MsgStatus.PENDING);
		assertThat(turn.threadId()).isEqualTo(threadId);
		assertThat(turn.agentSeq()).isEqualTo(1L);
		assertThat(turn.replay()).isFalse();

		ArgumentCaptor<MsgIdmKey> savedKey = ArgumentCaptor.forClass(MsgIdmKey.class);
		verify(msgIdmKeyRepository).save(savedKey.capture());
		assertThat(savedKey.getValue().getKey()).isEqualTo("key-1");
		assertThat(savedKey.getValue().getContent()).isEqualTo("안녕");
	}

	@Test
	void rejectsCollabOrAnotherUsersThreadWithoutLeakingItsKind() {
		UUID threadId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		Thr collab = Thr.collab(ownerId, "협업방");
		when(msgIdmKeyRepository.findByUserIdAndKey(actorId, "key-1")).thenReturn(Optional.empty());
		when(thrRepository.findByIdForSeqUpdate(threadId)).thenReturn(Optional.of(collab));

		assertThatThrownBy(() -> service.prepareExistingWithPendingAgentBlocking(threadId, actorId, "안녕", "key-1"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						status -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	/** 같은 키·같은 content로 재시도하면 새로 저장하지 않고 기존 값을 replay=true로 돌려준다. */
	@Test
	void replaysStoredTurnForTheSameKeyAndContentWithoutSavingAgain() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID humanMsgId = UUID.randomUUID();
		UUID agentMsgId = UUID.randomUUID();
		MsgIdmKey existing = new MsgIdmKey(userId, "key-1", "안녕", threadId, humanMsgId, 0L, agentMsgId, 1L);
		Msg agentMsg = Msg.pendingAgent(agentMsgId, threadId, 1L);
		agentMsg.complete("답변");
		when(msgIdmKeyRepository.findByUserIdAndKey(userId, "key-1")).thenReturn(Optional.of(existing));
		when(msgRepository.findById(agentMsgId)).thenReturn(Optional.of(agentMsg));

		DirectChatTurnService.StoredTurn turn = service.prepareExistingWithPendingAgentBlocking(threadId, userId,
				"안녕", "key-1");

		assertThat(turn.replay()).isTrue();
		assertThat(turn.humanMessageId()).isEqualTo(humanMsgId);
		assertThat(turn.agentMessageId()).isEqualTo(agentMsgId);
		assertThat(turn.agentStatus()).isEqualTo(MsgStatus.COMPLETE);
		verify(thrRepository, never()).findByIdForSeqUpdate(any());
		verify(msgRepository, never()).save(any());
	}

	/** 같은 키에 다른 content가 오면 클라이언트 버그·키 재사용 실수로 보고 거절한다(순수 방어용). */
	@Test
	void rejectsTheSameKeyWithDifferentContent() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		MsgIdmKey existing = new MsgIdmKey(userId, "key-1", "안녕", threadId, UUID.randomUUID(), 0L,
				UUID.randomUUID(), 1L);
		when(msgIdmKeyRepository.findByUserIdAndKey(userId, "key-1")).thenReturn(Optional.of(existing));

		assertThatThrownBy(
				() -> service.prepareExistingWithPendingAgentBlocking(threadId, userId, "다른 내용", "key-1"))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	/**
	 * TTL(5분)을 넘긴 키는 새 요청으로 취급하고, 옛 행을 지운다 — 지우지 않으면 뒤이은 저장이
	 * (user_id, idm_key) 유니크 인덱스와 충돌해 TTL 만료 뒤 재시도가 매번 실패한다.
	 */
	@Test
	void treatsAnExpiredKeyAsANewRequestAndDeletesTheStaleRow() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		MsgIdmKey expired = new MsgIdmKey(userId, "key-1", "안녕", threadId, UUID.randomUUID(), 0L,
				UUID.randomUUID(), 1L);
		var createdAt = MsgIdmKey.class.getDeclaredField("createdAt");
		createdAt.setAccessible(true);
		createdAt.set(expired, java.time.Instant.now().minus(java.time.Duration.ofMinutes(6)));
		when(msgIdmKeyRepository.findByUserIdAndKey(userId, "key-1")).thenReturn(Optional.of(expired));
		when(thrRepository.findByIdForSeqUpdate(threadId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.prepareExistingWithPendingAgentBlocking(threadId, userId, "안녕", "key-1"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						status -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
		// notFound()는 findByIdForSeqUpdate가 empty를 반환했기 때문이다 — TTL 만료로 "새 요청"
		// 취급돼 정상 이어쓰기 경로(thr 조회)를 탔다는 뜻이다. replay 분기였다면 이 조회 자체가
		// 없었을 것이다(위 replaysStoredTurnForTheSameKeyAndContentWithoutSavingAgain 참고).
		verify(thrRepository).findByIdForSeqUpdate(threadId);
		verify(msgIdmKeyRepository).deleteImmediatelyByUserIdAndKey(userId, "key-1");
	}

	/**
	 * 같은 사용자가 같은 clientMsgId를 다른 스레드에 재사용하면(정상 경로에서는 안 나오지만)
	 * 엉뚱한 스레드의 메시지·답변이 섞여 들어가는 걸 막는다 — content 불일치와 같은 충돌로
	 * 다룬다.
	 */
	@Test
	void rejectsTheSameKeyUsedForADifferentThread() {
		UUID threadId = UUID.randomUUID();
		UUID otherThreadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		MsgIdmKey existing = new MsgIdmKey(userId, "key-1", "안녕", otherThreadId, UUID.randomUUID(), 0L,
				UUID.randomUUID(), 1L);
		when(msgIdmKeyRepository.findByUserIdAndKey(userId, "key-1")).thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.prepareExistingWithPendingAgentBlocking(threadId, userId, "안녕", "key-1"))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	/** 서버 재시작 등으로 고아가 된 PENDING 턴은 FAILED로 닫고 옛 키를 지운 뒤 새 발화로 진행한다. */
	@Test
	void recoversAnOrphanedPendingTurnAsAFreshOne() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID orphanedAgentMsgId = UUID.randomUUID();
		Msg orphaned = Msg.pendingAgent(orphanedAgentMsgId, threadId, 1L);
		Thr thread = Thr.direct(threadId, userId, "안녕");
		when(msgRepository.findById(orphanedAgentMsgId)).thenReturn(Optional.of(orphaned));
		when(thrRepository.findByIdForSeqUpdate(threadId)).thenReturn(Optional.of(thread));
		when(thrMbrRepository.findByThrIdAndUserIdAndRoleAndStatus(threadId, userId, ThrMbrRole.OWNER,
				ThrMbrStatus.ACTIVE)).thenReturn(Optional.of(new ThrMbr(threadId, userId, ThrMbrRole.OWNER, userId)));
		when(msgRepository.save(any(Msg.class))).thenAnswer(invocation -> invocation.getArgument(0));

		DirectChatTurnService.StoredTurn fresh = service.recoverOrphanedTurnBlocking(threadId, userId, "다시 보냄",
				"key-1", orphanedAgentMsgId);

		assertThat(fresh.replay()).isFalse();
		assertThat(orphaned.getStatus()).isEqualTo(MsgStatus.FAILED);
		verify(msgIdmKeyRepository).deleteImmediatelyByUserIdAndKey(userId, "key-1");
		verify(msgIdmKeyRepository).save(any(MsgIdmKey.class));
	}

}
