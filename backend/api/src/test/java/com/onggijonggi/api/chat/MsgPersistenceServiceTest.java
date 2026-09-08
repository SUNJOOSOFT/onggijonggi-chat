package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * Class Name : MsgPersistenceServiceTest.java
 * Description : 문맥 조회(이슈 #100)가 현재 발화 저장보다 먼저 일어나 방금 보낸 메시지가 문맥에
 *               중복으로 끼지 않는지, 최근 메시지가 시간순으로 뒤집혀 반환되는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MsgPersistenceServiceTest {

	@Mock
	private MsgRepository msgRepository;
	@Mock
	private ThrRepository thrRepository;
	@Mock
	private ThrMbrRepository thrMbrRepository;

	private MsgPersistenceService service;

	private final UUID thrId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new MsgPersistenceService(msgRepository, thrRepository, thrMbrRepository);
	}

	@Test
	void fetchesContextBeforePersistingTheCurrentHumanMessage() {
		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(thrId, userId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.of(new ThrMbr(thrId, userId, ThrMbrRole.MEMBER, userId)));
		when(msgRepository.findByThrIdAndStatusOrderBySeqDesc(eq(thrId), eq(MsgStatus.COMPLETE), any(Pageable.class)))
				.thenReturn(List.of());
		when(thrRepository.allocateNextSeq(thrId)).thenReturn(5L);
		when(msgRepository.save(any(Msg.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.persistHumanMessageAndFetchContextBlocking(thrId, userId, "hi", 20);

		InOrder order = inOrder(msgRepository, thrRepository);
		order.verify(msgRepository).findByThrIdAndStatusOrderBySeqDesc(eq(thrId), eq(MsgStatus.COMPLETE),
				any(Pageable.class));
		order.verify(thrRepository).allocateNextSeq(thrId);
		order.verify(msgRepository).save(any(Msg.class));
	}

	@Test
	void reversesRecentMessagesIntoChronologicalOrder() {
		Msg newest = Msg.human(thrId, 2, UUID.randomUUID(), "newest");
		Msg oldest = Msg.human(thrId, 0, UUID.randomUUID(), "oldest");
		when(msgRepository.findByThrIdAndStatusOrderBySeqDesc(eq(thrId), eq(MsgStatus.COMPLETE), any(Pageable.class)))
				.thenReturn(List.of(newest, oldest));

		List<Msg> context = service.recentCompleteContextBlocking(thrId, 20);

		assertThat(context).extracting(Msg::getContent).containsExactly("oldest", "newest");
	}

	@Test
	void returnsEmptyContextWithoutQueryingWhenLimitIsZero() {
		List<Msg> context = service.recentCompleteContextBlocking(thrId, 0);

		assertThat(context).isEmpty();
		verifyNoInteractions(msgRepository);
	}

	@Test
	void skipsPersistingWhenCallerIsNotAnActiveParticipant() {
		when(thrMbrRepository.findByThrIdAndUserIdAndStatus(thrId, userId, ThrMbrStatus.ACTIVE))
				.thenReturn(Optional.empty());

		Optional<Msg> saved = service.persistHumanMessageBlocking(thrId, userId, "hi");

		assertThat(saved).isEmpty();
	}

}
