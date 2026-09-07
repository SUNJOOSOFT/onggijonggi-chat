package com.onggijonggi.api.chat;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.reactivestreams.Publisher;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Class Name : WsTestExchange.java
 * Description : WebSocket 통합 테스트에서 수신 demand가 생긴 뒤에만 송신하도록 교환 순서를 보장한다.
 *               첫 프레임 전송은 {@link #FIRST_FRAME_SEND_DELAY}만큼 늦춘다(이슈 #102).
 *               관심 없는 프레임을 걸러낼 수도 있다 — 입퇴장 방송(이슈 #25)이 생기면서 한 커넥션에
 *               섞여 오는 프레임이 늘었고, 서버의 방 등록 순서는 클라이언트가 알 수 없어 도착
 *               순서에 기대면 테스트가 흔들린다. take 이전에 걸러 관심 프레임만 세게 한다.
 */
final class WsTestExchange {

	/**
	 * reactor-netty의 WebSocket 업그레이드 경로에는 핸드셰이크 응답을 플러시하는 시점과, 채널을
	 * 실제 WebSocket 세션으로 전환하는 rebind 리스너 콜백이 실행되는 시점 사이에 좁은 창이 있다
	 * ({@code WebsocketServerOperations.initHandshaker}). 그 창에 도착한 프레임은 이미 끝난
	 * 이전 HTTP 교환의 {@code FluxReceive}로 잘못 배달돼 예외·로그 없이 조용히 release된다
	 * ({@code FluxReceive.onInboundNext}, 리시버가 이미 종결된 경우). 실제 클라이언트는 네트워크
	 * 왕복 지연 때문에 이 창을 사실상 항상 넘기지만, 같은 JVM 안에서 지연 없이 곧바로 응답하는
	 * 테스트 클라이언트는 이 창을 맞힐 수 있다. 첫 프레임 전송을 살짝 늦춰 실제 클라이언트가
	 * 자연스럽게 갖는 지연을 흉내 낸다.
	 */
	private static final Duration FIRST_FRAME_SEND_DELAY = Duration.ofMillis(20);

	private WsTestExchange() {
	}

	/**
	 * 참여자 스냅샷(이슈 #26)은 연결마다 첫 프레임으로 반드시 온다. 그 프레임을 보는 테스트가
	 * 아니면 이 조건으로 걸러야 한다 — 세어 버리면 기대 프레임 수가 하나씩 밀리고, 기대 수를
	 * 채운 수신이 먼저 끝나면 지연된 첫 전송이 닫힌 세션에 부딪힌다.
	 */
	static Predicate<WebSocketMessage> exceptPresenceSnapshot() {
		return message -> !message.getPayloadAsText().contains("\"type\":\"presence.snapshot\"");
	}

	static Mono<Void> exchange(WebSocketSession session,
			Function<WebSocketSession, Publisher<WebSocketMessage>> outbound, long expectedFrames,
			Consumer<WebSocketMessage> onInbound) {
		return exchange(session, outbound, expectedFrames, onInbound, () -> {
		});
	}

	static Mono<Void> exchange(WebSocketSession session,
			Function<WebSocketSession, Publisher<WebSocketMessage>> outbound, long expectedFrames,
			Consumer<WebSocketMessage> onInbound, Runnable onReceiveRequested) {
		return exchange(session, outbound, expectedFrames, onInbound, onReceiveRequested, message -> true);
	}

	static Mono<Void> exchange(WebSocketSession session,
			Function<WebSocketSession, Publisher<WebSocketMessage>> outbound, long expectedFrames,
			Consumer<WebSocketMessage> onInbound, Runnable onReceiveRequested,
			Predicate<WebSocketMessage> interesting) {
		Sinks.One<Void> receiveRequested = Sinks.one();
		Mono<Void> receive = session.receive()
				.doOnRequest(ignored -> {
					receiveRequested.tryEmitEmpty();
					onReceiveRequested.run();
				})
				.filter(interesting)
				.take(expectedFrames)
				.doOnNext(onInbound)
				.then();
		Mono<Void> send = receiveRequested.asMono()
				.then(Mono.delay(FIRST_FRAME_SEND_DELAY))
				.then(Mono.defer(() -> session.send(outbound.apply(session))));
		return Mono.when(receive, send);
	}

}
