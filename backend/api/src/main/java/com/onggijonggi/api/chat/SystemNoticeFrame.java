package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : SystemNoticeFrame.java
 * Description : 위험 질문 차단 알림, 토큰 소진 안내 같은 실시간 시스템 알림 프레임(#28). 01·CLIENT는
 *               이미 이 계약(severity가 화면 표시 방식을, code가 아니라 message를 그대로 보여준다)을
 *               이슈 #29에서 구현해뒀다 — frontend/lib/transport/frames.ts SystemNoticeFrame을
 *               확인한다.
 * @param severity warning(배너) 또는 info(토스트) — 01·CLIENT가 이 값으로 표시 방식을 고른다
 * @param code 알림 종류를 가리키는 고정 토큰(예: RISKY_CONTENT). 01·CLIENT는 분기에 쓰지 않는다
 */
public record SystemNoticeFrame(UUID sessionId, String severity, String code, String message, String traceId)
		implements WsFrame {
}
