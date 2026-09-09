package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrRiskCursor;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.chat.persistence.ThrRiskCursorRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Class Name : RiskCheckBatchServiceTest.java
 * Description : RiskCheckBatchService의 스캔·커서 전진·SYSTEM 메시지 생성을 Mockito로 검증한다.
 *               RiskClassifier를 목으로 대체해 실 LLM 없이 판정 분기만 좁혀 본다.
 */
@ExtendWith(MockitoExtension.class)
class RiskCheckBatchServiceTest {

	@Mock
	private ThrRepository thrRepository;

	@Mock
	private MsgRepository msgRepository;

	@Mock
	private ThrRiskCursorRepository thrRiskCursorRepository;

	@Mock
	private RiskClassifier riskClassifier;

	@Mock
	private RoomSessionRegistry roomSessionRegistry;

	private RiskCheckBatchService service;

	@BeforeEach
	void setUp() {
		service = new RiskCheckBatchService(thrRepository, msgRepository, thrRiskCursorRepository, riskClassifier,
				roomSessionRegistry);
	}

	@Test
	void createsASystemMessageWhenARiskyMessageIsFound() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		Msg risky = Msg.human(thr.getId(), 1, UUID.randomUUID(), "위험해 보이는 발화");

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 0L))
				.thenReturn(List.of(risky));
		when(riskClassifier.isRisky("위험해 보이는 발화")).thenReturn(true);
		when(thrRepository.allocateNextSeq(thr.getId())).thenReturn(2L);

		service.scanThread(thr);

		ArgumentCaptor<Msg> savedMsg = ArgumentCaptor.forClass(Msg.class);
		verify(msgRepository).save(savedMsg.capture());
		assertThat(savedMsg.getValue().getAthKind()).isEqualTo(AthKind.SYSTEM);
		assertThat(savedMsg.getValue().getSeq()).isEqualTo(2L);

		ArgumentCaptor<ThrRiskCursor> savedCursor = ArgumentCaptor.forClass(ThrRiskCursor.class);
		verify(thrRiskCursorRepository).save(savedCursor.capture());
		assertThat(savedCursor.getValue().getLastSeq()).isEqualTo(1L);

		ArgumentCaptor<SystemNoticeFrame> notice = ArgumentCaptor.forClass(SystemNoticeFrame.class);
		verify(roomSessionRegistry).notifyIfListening(eq(thr.getId()), notice.capture());
		assertThat(notice.getValue().severity()).isEqualTo("warning");
		assertThat(notice.getValue().code()).isEqualTo("RISKY_CONTENT");
	}

	@Test
	void doesNotCreateASystemMessageWhenNothingIsRisky() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		Msg safe = Msg.human(thr.getId(), 1, UUID.randomUUID(), "평범한 발화");

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 0L))
				.thenReturn(List.of(safe));
		when(riskClassifier.isRisky("평범한 발화")).thenReturn(false);

		service.scanThread(thr);

		verify(msgRepository, never()).save(any());
		verify(thrRiskCursorRepository).save(any());
		verify(roomSessionRegistry, never()).notifyIfListening(any(), any());
	}

	/** 이미 스캔한 메시지를 다시 검사하지 않는다 — 커서 이후 것만 조회한다. */
	@Test
	void resumesScanningFromTheCursor() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		ThrRiskCursor cursor = new ThrRiskCursor(thr.getId());
		cursor.advanceTo(5L);

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.of(cursor));
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 5L))
				.thenReturn(List.of());

		service.scanThread(thr);

		verify(msgRepository).findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 5L);
		verify(thrRiskCursorRepository, never()).save(any());
	}

	@Test
	void classifiesEveryNewMessageEvenAfterARiskyOne() {
		Thr thr = Thr.collab(UUID.randomUUID(), "room");
		Msg first = Msg.human(thr.getId(), 1, UUID.randomUUID(), "위험한 발화");
		Msg second = Msg.human(thr.getId(), 2, UUID.randomUUID(), "평범한 발화");

		when(thrRiskCursorRepository.findById(thr.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(), AthKind.HUMAN, 0L))
				.thenReturn(List.of(first, second));
		when(riskClassifier.isRisky("위험한 발화")).thenReturn(true);
		when(riskClassifier.isRisky("평범한 발화")).thenReturn(false);
		when(thrRepository.allocateNextSeq(thr.getId())).thenReturn(3L);

		service.scanThread(thr);

		verify(riskClassifier, times(2)).isRisky(any());
		verify(msgRepository, times(1)).save(any());
		ArgumentCaptor<ThrRiskCursor> savedCursor = ArgumentCaptor.forClass(ThrRiskCursor.class);
		verify(thrRiskCursorRepository).save(savedCursor.capture());
		assertThat(savedCursor.getValue().getLastSeq()).isEqualTo(2L);
	}

	/** 스레드 스캔 실패는 그 스레드만 영향받고, 나머지 스레드는 그대로 스캔된다. */
	@Test
	void scanAllThreadsContinuesAfterOneThreadFails() {
		Thr failing = Thr.collab(UUID.randomUUID(), "failing room");
		Thr healthy = Thr.collab(UUID.randomUUID(), "healthy room");

		when(thrRepository.findByKindAndStatusNot(ThrKind.COLLAB, ThrStatus.ARCHIVED))
				.thenReturn(List.of(failing, healthy));
		when(thrRiskCursorRepository.findById(failing.getId())).thenThrow(new RuntimeException("boom"));
		when(thrRiskCursorRepository.findById(healthy.getId())).thenReturn(Optional.empty());
		when(msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(eq(healthy.getId()),
				eq(AthKind.HUMAN), eq(0L))).thenReturn(List.of());

		service.scanAllThreads();

		verify(thrRiskCursorRepository).findById(healthy.getId());
	}

}
