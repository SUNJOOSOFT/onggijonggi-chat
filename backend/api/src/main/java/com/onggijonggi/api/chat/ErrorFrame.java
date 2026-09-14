package com.onggijonggi.api.chat;

import java.util.UUID;

/**
 * Class Name : ErrorFrame.java
 * Description : WebSocket으로 전환된 뒤 프레임을 주고받는 동안 생긴 오류를 알린다. HTTP 쪽 공통
 *               에러 봉투(ErrorResponse)와 code/message/traceId 세 필드를 같은 모양으로 맞춰,
 *               프론트가 두 경로의 오류를 한 가지로 다루게 한다.
 *
 *               인증·인가·레이트리밋 실패는 이 프레임으로 오지 않는다. 전환 전 핸드셰이크는 아직
 *               HTTP라 보안 체인이 그 자리에서 HTTP 에러 봉투로 끊고(WsSecurityConfig·
 *               WsOriginWebFilter·RateLimitWebFilter), 전환된 뒤의 토큰 만료는
 *               CollabWebSocketHandler가 커스텀 close code로 끊는다(이슈 #62). 핸드셰이크 실패를
 *               굳이 프레임으로 바꾸지 않는 이유는 브라우저가 그 응답의 status도 body도
 *               클라이언트에 넘기지 않아 어차피 닿지 않기 때문이다
 *               (frontend lib/api/ws-connection.ts). 이 경계의 논의는 이슈 #11에 있다.
 *
 *               연결 수립 자체가 실패하는 경우처럼 특정 방에 속하지 않는 오류라면 threadId는
 *               null일 수 있다.
 *
 *               traceId는 연결 단위가 아니라 메시지(턴) 단위로 발급한다(이슈 #12) — 커넥션 하나가
 *               여러 턴을 실어 나르는 WS 특성상, HTTP처럼 요청 하나 단위의 추적력을 유지하려면
 *               턴마다 새로 발급해야 한다. 발급 지점은 CollabWebSocketHandler가 인바운드 프레임을
 *               받는 자리다.
 */
public record ErrorFrame(UUID threadId, String code, String message, String traceId) implements WsFrame {
}
