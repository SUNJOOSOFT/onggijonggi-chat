package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
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
 * Description : DIRECT 첫 발화의 원자 구성 요소와 기존 방의 두 seq 예약·소유자 404 은닉을
 *               저장소 단위에서 고정한다. 실제 트랜잭션 원자성은 PostgreSQL migration/통합 검증이 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class DirectChatTurnServiceTest {

	@Mock
	private ThrRepository thrRepository;
	@Mock
	private ThrMbrRepository thrMbrRepository;
	@Mock
	private MsgRepository msgRepository;

	private DirectChatTurnService service;

	@BeforeEach
	void setUp() {
		service = new DirectChatTurnService(thrRepository, thrMbrRepository, msgRepository);
	}

	@Test
	void createsDirectThreadOwnerHumanMessageAndPendingAgentAndReservesAgentSequence() {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		when(thrRepository.findByIdForSeqUpdate(threadId)).thenReturn(Optional.empty());
		when(thrRepository.save(any(Thr.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(thrMbrRepository.save(any(ThrMbr.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(msgRepository.save(any(Msg.class))).thenAnswer(invocation -> invocation.getArgument(0));

		DirectChatTurnService.StoredTurn turn = service.prepareOrCreateWithPendingAgentBlocking(threadId, userId,
				"안녕", "안녕");

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
	}

	@Test
	void rejectsCollabOrAnotherUsersThreadWithoutLeakingItsKind() {
		UUID threadId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		Thr collab = Thr.collab(ownerId, "협업방");
		when(thrRepository.findByIdForSeqUpdate(threadId)).thenReturn(Optional.of(collab));

		assertThatThrownBy(() -> service.prepareExistingWithPendingAgentBlocking(threadId, actorId, "안녕"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						status -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}

}
