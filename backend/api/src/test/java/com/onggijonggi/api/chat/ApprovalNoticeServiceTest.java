package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ApprovalNoticeServiceTest.java
 * Description : 승인 줄을 SYSTEM 메시지로 저장하고 방에 프레임을 보내는 흐름과, 저장·알림·이름 조회가 실패해도
 *               요청·처리를 막지 않는지를 검증한다(#407).
 */
class ApprovalNoticeServiceTest {

	private static final UUID THREAD = UUID.randomUUID();

	private final ObjectMapper objectMapper = new JsonMapper();
	private final MsgRepository messages = mock(MsgRepository.class);
	private final ThreadMessageDispatcher dispatcher = mock(ThreadMessageDispatcher.class);
	private final RoomSessionRegistry registry = mock(RoomSessionRegistry.class);
	private final RankedDisplayNames names = mock(RankedDisplayNames.class);
	private static final UUID ORIGIN = UUID.randomUUID();

	private final ApprovalNotice notice = new ApprovalNotice(ApprovalNotice.Event.REQUESTED, UUID.randomUUID(),
			"kc-1", "kc-2", ORIGIN, null);
	private ApprovalNoticeService service;

	@BeforeEach
	void setup() {
		service = new ApprovalNoticeService(messages, dispatcher, registry, names, objectMapper);
		when(dispatcher.allocateSeqBlocking(THREAD)).thenReturn(7L);
		when(messages.save(any(Msg.class))).thenAnswer(call -> call.getArgument(0));
		when(names.displayName("kc-1")).thenReturn(Mono.just(Optional.of("주성민")));
		when(names.displayName("kc-2")).thenReturn(Mono.just(Optional.of("이한결")));
	}

	@Test
	void savesASystemLineAndSendsAFrameCarryingItsIdAndSeq() {
		service.recordBlocking(THREAD, notice);
		ArgumentCaptor<Msg> saved = ArgumentCaptor.forClass(Msg.class);
		verify(messages).save(saved.capture());
		Msg line = saved.getValue();
		assertThat(line.getAthKind()).isEqualTo(AthKind.SYSTEM);
		assertThat(line.getSeq()).isEqualTo(7L);
		assertThat(line.getContent()).isEmpty();
		assertThat(ApprovalNotice.fromPylJson(objectMapper, line.getPylJson())).isEqualTo(notice);
		ArgumentCaptor<WsFrame> sent = ArgumentCaptor.forClass(WsFrame.class);
		verify(registry).notifyIfListening(eq(THREAD), sent.capture());
		assertThat(sent.getValue()).isEqualTo(new ApprovalChangedFrame(THREAD, notice.approvalId(),
				ApprovalNotice.Event.REQUESTED, "kc-1", "주성민", "kc-2", "이한결", line.getId(), 7L, ORIGIN, null));
	}

	@Test
	void aFailedLineSaveStillSendsTheFrameWithoutTheLine() {
		when(messages.save(any(Msg.class))).thenThrow(new IllegalStateException("db down"));
		assertThatCode(() -> service.recordBlocking(THREAD, notice)).doesNotThrowAnyException();
		ArgumentCaptor<WsFrame> sent = ArgumentCaptor.forClass(WsFrame.class);
		verify(registry).notifyIfListening(eq(THREAD), sent.capture());
		var frame = (ApprovalChangedFrame) sent.getValue();
		assertThat(frame.msgId()).isNull();
		assertThat(frame.seq()).isNull();
		assertThat(frame.approvalId()).isEqualTo(notice.approvalId());
	}

	@Test
	void aFailedNotificationNeverReachesTheCaller() {
		doThrow(new IllegalStateException("room closed")).when(registry).notifyIfListening(eq(THREAD), any());
		assertThatCode(() -> service.recordBlocking(THREAD, notice)).doesNotThrowAnyException();
	}

	@Test
	void displayNamesFallBackToTheSubjectWhenTheLookupFailsOrIsEmpty() {
		when(names.displayName("kc-1")).thenReturn(Mono.error(new IllegalStateException("keycloak down")));
		when(names.displayName("kc-2")).thenReturn(Mono.just(Optional.empty()));
		service.recordBlocking(THREAD, notice);
		ArgumentCaptor<WsFrame> sent = ArgumentCaptor.forClass(WsFrame.class);
		verify(registry).notifyIfListening(eq(THREAD), sent.capture());
		var frame = (ApprovalChangedFrame) sent.getValue();
		assertThat(frame.requesterDisplayName()).isEqualTo("kc-1");
		assertThat(frame.targetDisplayName()).isEqualTo("kc-2");
	}

}
