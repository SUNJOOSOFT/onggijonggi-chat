package com.onggijonggi.api.chat;

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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : RiskCheckBatchService.java
 * Description : 위험 발화 사후 검증 배치(#28). 실시간 차단이 아니라 주기적으로 스레드를 스캔해
 *               위험 발화를 감지하면 SYSTEM 메시지로 남긴다.
 *
 *               감지 결과를 WS 프레임으로 즉시 방송하지 않는다 — 접속자가 없는 방에서 방송하면
 *               알림이 사라지는 문제(#27 코멘트)를, presence.snapshot 같은 별도 재생 설계 없이
 *               msg 영속화 + 기존 GET /messages 조회로 해결하기로 했다. 지금 접속해 있는 사람에게
 *               실시간으로 배너를 띄우는 것은 이번 범위가 아니다.
 *
 *               스캔 위치는 msg가 아니라 별도 커서(thr_risk_crs)에 둔다 — 이유는 ThrRiskCursor를
 *               참고한다.
 */
@Service
@ConditionalOnProperty(name = "app.collab.risk-check.enabled", havingValue = "true", matchIfMissing = true)
public class RiskCheckBatchService {

	private static final Logger log = LoggerFactory.getLogger(RiskCheckBatchService.class);

	/** 사람이 읽을 문구 — 세분화된 코드 없이 RISKY_CONTENT 하나로 시작한다(#27 코멘트). */
	private static final String RISK_NOTICE = "위험할 수 있는 발화가 감지되었습니다. (RISKY_CONTENT)";

	private final ThrRepository thrRepository;

	private final MsgRepository msgRepository;

	private final ThrRiskCursorRepository thrRiskCursorRepository;

	private final RiskClassifier riskClassifier;

	public RiskCheckBatchService(ThrRepository thrRepository, MsgRepository msgRepository,
			ThrRiskCursorRepository thrRiskCursorRepository, RiskClassifier riskClassifier) {
		this.thrRepository = thrRepository;
		this.msgRepository = msgRepository;
		this.thrRiskCursorRepository = thrRiskCursorRepository;
		this.riskClassifier = riskClassifier;
	}

	/** 스레드 하나의 실패가 나머지 스레드 스캔을 막지 않는다 — 각 스레드를 독립적으로 처리한다. */
	@Scheduled(fixedDelayString = "${app.collab.risk-check.interval:30s}")
	public void scanAllThreads() {
		for (Thr thr : thrRepository.findByKindAndStatusNot(ThrKind.COLLAB, ThrStatus.ARCHIVED)) {
			try {
				scanThread(thr);
			} catch (RuntimeException error) {
				log.error("위험 질문 사후 검증 실패 threadId={}", thr.getId(), error);
			}
		}
	}

	@Transactional
	void scanThread(Thr thr) {
		long lastSeq = thrRiskCursorRepository.findById(thr.getId()).map(ThrRiskCursor::getLastSeq).orElse(0L);
		List<Msg> newMessages = msgRepository.findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(thr.getId(),
				AthKind.HUMAN, lastSeq);
		if (newMessages.isEmpty()) {
			return;
		}
		for (Msg message : newMessages) {
			if (riskClassifier.isRisky(message.getContent())) {
				persistRiskNotice(thr.getId());
			}
		}
		advanceCursor(thr.getId(), newMessages.get(newMessages.size() - 1).getSeq());
	}

	private void persistRiskNotice(UUID threadId) {
		long seq = thrRepository.allocateNextSeq(threadId);
		msgRepository.save(Msg.system(threadId, seq, RISK_NOTICE));
	}

	private void advanceCursor(UUID threadId, long seq) {
		ThrRiskCursor cursor = thrRiskCursorRepository.findById(threadId)
				.orElseGet(() -> new ThrRiskCursor(threadId));
		cursor.advanceTo(seq);
		thrRiskCursorRepository.save(cursor);
	}

}
