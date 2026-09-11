package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onggijonggi.api.auth.FixedWindowRateLimiter;
import com.onggijonggi.api.auth.JwtDisplayNames;
import com.onggijonggi.api.auth.WsSubProtocolBearerTokenConverter;
import com.onggijonggi.api.auth.UserIdentityService;
import java.security.Principal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : CollabWebSocketHandler.java
 * Description : 협업 채팅 WebSocket 연결의 수신·송신 수명과 프레임 처리를 담당한다.
 */
@Component
public class CollabWebSocketHandler implements WebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(CollabWebSocketHandler.class);

	private static final String WS_PATH_PREFIX = "/api/ws/";

	private static final int CONNECTION_BUFFER_SIZE = 256;

	private static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4000, "token expired");

	private static final CloseStatus SLOW_CONSUMER = new CloseStatus(1011, "outbound buffer overflow");

	/** evict(이슈 #135)로 FORBIDDEN 프레임을 보낸 뒤, session.send()가 그 프레임을 실제로 flush할
	 * 시간을 벌어주고서야 닫는다 — evictionNotice가 완료된 즉시 닫으면, 그 완료 신호가 프레임이
	 * 실제로 네트워크에 나가는 것보다 먼저 도착할 여지가 있다. */
	private static final Duration EVICTED_FRAME_FLUSH_GRACE_PERIOD = Duration.ofMillis(200);

	/** 종료 사유가 정해진 뒤 session.send(outbound)가 스스로 완료돼 정상 close로 이어질 시간(이슈 #181).
	 * 이 안에 안 끝나면(느린 소비자처럼 backpressure로 send가 막힌 경우) 직접 close로 넘어간다 —
	 * 그 경로의 close 프레임은 degraded일 수 있으나 그 상황은 클라이언트가 읽지 않는 상태다. */
	private static final Duration CLOSE_DRAIN_GRACE_PERIOD = Duration.ofMillis(500);

	private static final Set<String> SERVER_ONLY_TYPES = Set.of("chat.answer", "presence.join",
			"presence.leave", "presence.snapshot", "error", "system.notice");

	private final ObjectMapper objectMapper;

	private final RoomSessionRegistry roomSessionRegistry;

	private final CollabMessageDispatcher collabMessageDispatcher;

	private final UserIdentityService userIdentityService;

	private final ThreadMembershipService threadMembershipService;

	/**
	 * 이 핸들러만 쓰는 버킷이다(이슈 #74). 핸드셰이크 한도(WsSecurityConfig)와 나누는 이유는
	 * 성격이 달라서다 — 핸드셰이크는 끊길 때마다 한 번이고 메시지는 대화 중 연속 발화다.
	 * 세는 단위는 연결이 아니라 sub다. 연결 단위로 세면 소켓을 끊었다 붙이는 것만으로 카운터가
	 * 초기화되는데, 클라이언트가 백오프로 자동 재연결하므로(ws-connection.ts) 도배를 막지 못하면서
	 * 핸드셰이크 부하만 늘린다.
	 */
	private final FixedWindowRateLimiter messageRateLimiter;

	public CollabWebSocketHandler(ObjectMapper objectMapper, RoomSessionRegistry roomSessionRegistry,
			CollabMessageDispatcher collabMessageDispatcher, UserIdentityService userIdentityService,
			ThreadMembershipService threadMembershipService, Clock rateLimitClock,
			@Value("${app.ratelimit.window-seconds:60}") long rateLimitWindowSeconds,
			@Value("${app.ratelimit.ws-message-per-minute:60}") int wsMessagePerMinute) {
		this.objectMapper = objectMapper;
		this.roomSessionRegistry = roomSessionRegistry;
		this.collabMessageDispatcher = collabMessageDispatcher;
		this.userIdentityService = userIdentityService;
		this.threadMembershipService = threadMembershipService;
		this.messageRateLimiter =
				new FixedWindowRateLimiter(rateLimitClock, rateLimitWindowSeconds, wsMessagePerMinute);
	}

	@Override
	public List<String> getSubProtocols() {
		return List.of(WsSubProtocolBearerTokenConverter.PROTOCOL_NAME);
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		UUID threadId = threadIdOf(session);
		if (threadId == null) {
			return sendErrorAndClose(session, null, "MALFORMED_REQUEST",
					"threadId는 UUID 형식이어야 합니다.", newTraceId());
		}

		return session.getHandshakeInfo().getPrincipal()
				.map(CollabWebSocketHandler::sessionInfoOf)
				.defaultIfEmpty(new SessionInfo("EMPTY", "EMPTY", null))
				.flatMap(info -> userIdentityService.resolveOrProvision(info.subject())
						.onErrorMap(UserProvisioningFailure::new)
						.flatMap(userId -> admitOrReject(session, threadId, userId,
								new PresenceParticipant(info.subject(), info.displayName()),
								info.tokenExpiresAt()))
						.onErrorResume(UserProvisioningFailure.class, error -> {
							String traceId = newTraceId();
							log.error("WebSocket user provisioning failed threadId={} traceId={}",
									threadId, traceId, error.getCause());
							return sendErrorAndClose(session, threadId, "INTERNAL_ERROR",
									"WebSocket 세션을 초기화하지 못했습니다.", traceId);
						}));
	}

	/**
	* 참가자가 아니면 방에 넣지 않고 error 프레임으로 알린 뒤 정상 종료로 닫는다. 이 시점은 이미
	* WebSocket으로 전환된 뒤라 HTTP 상태코드를 쓸 수 없고, 핸드셰이크 단계에서 끊으면 브라우저가
	* 그 응답을 클라이언트에 넘기지 않아 거부인지 서버 장애인지 구분되지 않는다.
	*/
	private Mono<Void> admitOrReject(WebSocketSession session, UUID threadId, UUID userId,
			PresenceParticipant actor, Instant tokenExpiresAt) {
		return threadMembershipService.isActiveParticipant(threadId, userId)
				.onErrorMap(MembershipLookupFailure::new)
				.flatMap(participant -> participant
						? handleRoomSession(session, threadId, userId, actor, tokenExpiresAt)
						: rejectRoomAccess(session, threadId))
				.onErrorResume(MembershipLookupFailure.class, error -> {
					String traceId = newTraceId();
					log.error("WebSocket room membership lookup failed threadId={} traceId={}",
							threadId, traceId, error.getCause());
					return sendErrorAndClose(session, threadId, "INTERNAL_ERROR",
							"WebSocket 세션을 초기화하지 못했습니다.", traceId);
				});
	}

	/** 재연결해도 같은 거부라, 클라이언트가 재연결 루프를 멈출 수 있게 사유를 실어 보낸다. */
	private Mono<Void> rejectRoomAccess(WebSocketSession session, UUID threadId) {
		String traceId = newTraceId();
		log.debug("WebSocket room access denied threadId={} traceId={}", threadId, traceId);
		return sendErrorAndClose(session, threadId, "FORBIDDEN", "이 방에 들어갈 권한이 없습니다.", traceId);
	}

	private Mono<Void> handleRoomSession(WebSocketSession session, UUID threadId, UUID userId,
			PresenceParticipant actor, Instant tokenExpiresAt) {
		UUID connectionId = UUID.randomUUID();
		// 종료 사유 하나로 수렴한다 — 클라이언트가 먼저 닫든(peer close), 서버가 토큰 만료·느린
		// 소비자·evict로 끊든 모두 여기에 CloseStatus를 넣는다. outbound(방 브로드캐스트)는 이걸로
		// 끊기고, 실제 session.close()는 send(outbound)가 완료된 뒤 단 한 곳(lifecycle)에서 이 사유로
		// 불린다. session.close()가 살아 있는 session.send()와 같은 채널에서 경합하면
		// CloseWebSocketFrame이 이중 해제돼(refCnt: 0, decrement: 1) 종료 핸드셰이크가 깨지고
		// 클라이언트가 close code를 받지 못한다(이슈 #181). 종료 경로마다 session.close()를 부르던
		// 예전 구조(messageLoop·tokenExpiry·slowConsumer·evictedClose 각자 close)가 그 경합을 냈다.
		Sinks.One<CloseStatus> closeReason = Sinks.one();
		Sinks.One<Void> outboundOverflow = Sinks.one();
		RoomSessionRegistry.RoomMembership membership = roomSessionRegistry.join(threadId, connectionId, actor);

		// [#181 계측] 핸드셰이크 통과 시점에 토큰이 exp 대비 몇 초 남았는지 — 근-만료 토큰 무한
		// 재발급(이슈 #181) 원인 규명용, 로그 레벨 debug. 실서버 배포 후 원인이 확정되면 제거한다
		// (진행 상황은 이슈 #181 코멘트에 남긴다 — docs/는 로컬 전용이라 팀 공유가 안 된다).
		Instant now = Instant.now();
		long skewSeconds = tokenExpiresAt == null ? Long.MIN_VALUE
				: Duration.between(now, tokenExpiresAt).toSeconds();
		log.debug("[#181] WS admit threadId={} connectionId={} sub={} tokenExp={} now={} skewSeconds={}",
				threadId, connectionId, actor.subject(), tokenExpiresAt, now, skewSeconds);

		// 참여자 스냅샷(#26)은 방송이 아니라 이 연결의 값이라, 방 버퍼 밖에서 맨 앞에 붙인다 —
		// 느린 소비자용 버퍼 한 칸을 명단이 차지할 이유가 없다.
		Flux<WsFrame> roomFrames = bufferForConnection(membership.frames(), outboundOverflow)
				.startWith(membership.snapshot());

		// session.receive()는 outbound와 분리해 독립 구독한다 — outbound를 사유로 끊을 때
		// session.receive()가 함께 취소되면 Reactor Netty 채널이 즉시 무너져, 뒤이은
		// session.close(status)의 close 프레임이 코드 없이 나가거나(클라이언트 1005) 아예 못
		// 나간다(이슈 #181). 인바운드 처리 결과 프레임은 sink로 옮겨 outbound에 실어 보낸다.
		Sinks.Many<WsFrame> inboundResponses = Sinks.many().unicast().onBackpressureBuffer();
		Mono<Void> inboundPump = session.receive()
				.concatMap(message -> handleInbound(message, threadId, userId, actor, membership.generation()))
				.doOnNext(inboundResponses::tryEmitNext)
				// 클라이언트/피어가 먼저 닫으면 receive()가 끝난다 — 정상 종료(1000)로 수렴시킨다.
				.doFinally(ignored -> {
					closeReason.tryEmitValue(CloseStatus.NORMAL);
					inboundResponses.tryEmitComplete();
				})
				.then();

		// evict(이슈 #135)로 강제 종료될 때도 FORBIDDEN 프레임을 보낸 뒤 닫는다. 별도 Mono로
		// session.send()를 한 번 더 부르지 않고 기존 outbound 스트림에 이어 붙이는 이유는,
		// WebSocketSession.send()는 세션당 한 번만 구독할 수 있어(Reactor Netty 제약) 두 번째
		// send()가 같은 커넥션에서 충돌하기 때문이다. evict 트리거는 그 프레임이 flush될 시간을
		// 준 뒤에야 closeReason에 사유를 넣는다(EVICTED_FRAME_FLUSH_GRACE_PERIOD).
		ErrorFrame evictedFrame = new ErrorFrame(threadId, "FORBIDDEN", "참여자 명단에서 제외되어 연결이 종료됩니다.",
				newTraceId());
		Flux<WsFrame> evictionNotice = membership.kicked()
				.doOnSuccess(ignored -> log.debug(
						"Closing evicted WebSocket connection threadId={} connectionId={}", threadId, connectionId))
				.thenMany(Flux.<WsFrame>just(evictedFrame))
				.cache();

		// outbound는 방 브로드캐스트·인바운드 응답·evict 통보로만 구성한다. session.receive()는
		// 여기 없다 — closeReason으로 이 flux가 끊겨도 인바운드 구독은 살아 있어 채널이 온전하다.
		Flux<WebSocketMessage> outbound = Flux.merge(roomFrames, inboundResponses.asFlux(), evictionNotice)
				.takeUntilOther(closeReason.asMono())
				.map(frame -> session.textMessage(serialize(frame)));

		// 서버발 종료 트리거 — session.close()는 부르지 않고 closeReason에 사유만 넣는다.
		Mono<Void> tokenExpiry = tokenExpiresAt == null
				? Mono.never()
				: Mono.delay(durationUntil(tokenExpiresAt))
						.doOnNext(ignored -> closeReason.tryEmitValue(TOKEN_EXPIRED))
						.then();
		Mono<Void> slowConsumer = outboundOverflow.asMono()
				.doOnSuccess(ignored -> {
					log.warn("Closing slow WebSocket consumer threadId={} connectionId={}", threadId, connectionId);
					closeReason.tryEmitValue(SLOW_CONSUMER);
				})
				.then();
		Mono<Void> evicted = evictionNotice.then()
				.then(Mono.delay(EVICTED_FRAME_FLUSH_GRACE_PERIOD))
				.doOnSuccess(ignored -> closeReason.tryEmitValue(CloseStatus.NORMAL))
				.then();

		// 정상 종료 경로 — send(outbound)가 완료된(= outbound가 사유로 끊긴) 뒤에 그 사유로 한 번
		// 닫는다. 이 시점엔 session.send()가 살아 있지 않고 session.receive()는 독립 구독이라,
		// 채널이 온전한 상태에서 close 프레임이 나간다(정확한 close code).
		Mono<Void> cleanClose = session.send(outbound)
				.then(closeReason.asMono())
				.flatMap(session::close);
		// 안전장치 — send가 backpressure로 끝나지 않으면(느린 소비자) 사유 확정 후 잠깐 기다렸다가
		// 직접 닫는다. cleanClose에 우선권을 주는 지연이다.
		Mono<Void> forceClose = closeReason.asMono()
				.delayElement(CLOSE_DRAIN_GRACE_PERIOD)
				.flatMap(session::close);
		Mono<Void> lifecycle = Mono.firstWithSignal(cleanClose, forceClose);

		// 트리거·inboundPump는 side-effect만 낸다. 스스로 이겨서는 안 되므로(그러면 lifecycle이
		// 취소돼 close가 안 불린다) then(Mono.never())로 매달아 둔다 — lifecycle이 닫고 완료하면
		// firstWithSignal이 함께 취소한다.
		return Mono.firstWithSignal(lifecycle, inboundPump.then(Mono.never()),
						tokenExpiry.then(Mono.never()), slowConsumer.then(Mono.never()),
						evicted.then(Mono.never()))
				.doFinally(signal -> {
					// [#181 계측] 연결 수명 — 근-만료 토큰 무한 재발급(이슈 #181) 원인 규명용. 실서버
					// 배포 후 원인이 확정되면 제거한다(진행 상황은 이슈 #181 코멘트에 남긴다).
					log.debug("[#181] WS session end threadId={} connectionId={} signal={} livedMs={}",
							threadId, connectionId, signal, Duration.between(now, Instant.now()).toMillis());
					roomSessionRegistry.leave(threadId, connectionId, actor)
							.ifPresent(generation -> collabMessageDispatcher.closeGeneration(threadId, generation));
				});
	}

	/**
	* 인바운드 프레임 하나를 처리한다. 파싱은 두 걸음이다 — 먼저 봉투에서 type만 꺼내 서버 전용
	* 타입을 조용히 걸러내고(이슈 #157: 위조해 보내도 오류를 되돌려주지 않는 것이 기존 동작이다),
	* 그다음 InboundFrame 화이트리스트로 다시 읽는다. 한 걸음으로 합치면 서버 전용 타입이
	* 화이트리스트에 없다는 이유로 MALFORMED_REQUEST를 받게 돼 그 동작이 뒤집힌다.
	*/
	private Mono<WsFrame> handleInbound(WebSocketMessage message, UUID threadId, UUID userId,
			PresenceParticipant actor, UUID roomGeneration) {
		String traceId = newTraceId();
		String payload = textPayload(message);
		if (payload == null) {
			return Mono.just(malformed(threadId, traceId));
		}

		InboundFrame inbound;
		try {
			if (SERVER_ONLY_TYPES.contains(objectMapper.readValue(payload, InboundEnvelope.class).type())) {
				return Mono.empty();
			}
			inbound = objectMapper.readValue(payload, InboundFrame.class);
		} catch (Exception error) {
			log.debug("Malformed WebSocket frame threadId={} traceId={}", threadId, traceId, error);
			return Mono.just(malformed(threadId, traceId));
		}

		// Java 17이라 switch 패턴 매칭(프리뷰)을 쓸 수 없어 instanceof 패턴으로 가른다.
		// InboundFrame이 sealed이므로 분기를 빠뜨리면 여기 마지막 malformed로 떨어진다 —
		// 컴파일러가 잡아주지 못하는 자리라, 타입을 더할 때 이 메서드를 함께 봐야 한다.
		if (inbound instanceof InboundChatMessage chatMessage) {
			return handleChatMessage(chatMessage, threadId, userId, actor, roomGeneration, traceId);
		}
		if (inbound instanceof InboundChatCancel cancel) {
			return handleCancel(cancel, threadId, actor, roomGeneration, traceId);
		}
		if (inbound instanceof InboundRoomSubscribe subscribe) {
			return handleSubscribe(subscribe.threadId(), threadId, traceId);
		}
		if (inbound instanceof InboundRoomUnsubscribe unsubscribe) {
			return handleUnsubscribe(unsubscribe.threadId(), threadId, traceId);
		}
		return Mono.just(malformed(threadId, traceId));
	}

	private Mono<WsFrame> handleChatMessage(InboundChatMessage inbound, UUID threadId, UUID userId,
			PresenceParticipant actor, UUID roomGeneration, String traceId) {
		if (inbound.content() == null || inbound.content().isBlank()) {
			return Mono.just(malformed(threadId, traceId));
		}

		// 한도를 넘으면 이 프레임만 버리고 연결은 유지한다(이슈 #74). 끊으면 클라이언트가 백오프로
		// 다시 붙어 핸드셰이크 쪽 부하로 옮겨갈 뿐이다. 멤버십 조회(DB)보다 앞에 두어 값싼 검사가
		// 먼저 걸리게 한다.
		if (!messageRateLimiter.tryAcquire(actor.subject())) {
			log.debug("WebSocket message rate limited threadId={} traceId={}", threadId, traceId);
			return Mono.just(new ErrorFrame(threadId, "RATE_LIMITED",
					"메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요.", traceId));
		}

		ChatMessageCommand command = new ChatMessageCommand(threadId, userId, actor.subject(),
				actor.displayName(), inbound.content(), inbound.modelId(), traceId);
		return threadMembershipService.isActiveParticipant(threadId, userId)
				.flatMap(participant -> participant
						? rejectIfLocked(command, roomGeneration, traceId)
						: Mono.just(new ErrorFrame(threadId, "FORBIDDEN",
								"이 방에 메시지를 보낼 권한이 없습니다.", traceId)))
				.onErrorResume(error -> {
					log.error("WebSocket membership re-check failed threadId={} traceId={}",
							threadId, traceId, error);
					return Mono.just(new ErrorFrame(threadId, "INTERNAL_ERROR",
							"메시지를 처리하지 못했습니다.", traceId));
				});
	}

	/**
	* 취소는 DB를 보지 않는다 — 멈출 대상이 메모리에 있는 실행 중(또는 대기 중) 턴이고, 그 턴을
	* 누가 불렀는지도 디스패처가 이미 들고 있다. 참가자 자격 재확인(handleChatMessage가 하는 것)을
	* 여기서 하지 않는 이유도 같다: 방에서 빠진 사람이 자기가 띄운 턴을 거두는 것은 막을 이유가 없다.
	*
	* 지목한 턴이 없으면(이미 끝났거나 남의 방 id를 보냈거나) 조용히 넘어간다 — 스트림이 막
	* 끝난 직후의 취소는 흔한 경합이고, 그때마다 오류를 돌려주면 화면이 이유 없이 시끄러워진다.
	*/
	private Mono<WsFrame> handleCancel(InboundChatCancel inbound, UUID threadId, PresenceParticipant actor,
			UUID roomGeneration, String traceId) {
		if (inbound.requestMsgId() == null) {
			return Mono.just(malformed(threadId, traceId));
		}
		CollabMessageDispatcher.CancelOutcome outcome = collabMessageDispatcher.cancel(threadId, roomGeneration,
				inbound.requestMsgId(), actor.subject());
		if (outcome == CollabMessageDispatcher.CancelOutcome.FORBIDDEN) {
			log.debug("WebSocket cancel denied threadId={} traceId={}", threadId, traceId);
			return Mono.just(new ErrorFrame(threadId, "FORBIDDEN",
					"자신이 요청한 AI 응답만 중단할 수 있습니다.", traceId));
		}
		return Mono.empty();
	}

	/**
	* 구독·해지는 이번 범위에서 계약과 파싱까지다(이슈 #160) — 실제 멀티플렉싱은 #161이 붙인다.
	*
	* 두 프레임이 "이 연결의 방인가"를 같은 방향으로 읽지 않는다. 지금 이 연결은 경로가 고정한 방
	* 하나만 듣고 있으므로, 그 방을 가리키는 구독은 이미 이뤄진 상태라 할 일이 없고(조용히 넘어간다)
	* 그 방을 가리키는 해지는 곧 연결 종료와 같은 뜻이라 받아줄 수 없다. 반대로 다른 방은 구독을
	* 아직 못 받고, 해지는 애초에 듣고 있지 않으니 할 일이 없다.
	*
	* 처음에는 한 헬퍼에 거절 문구만 바꿔 넘겼는데, 그러면 해지 쪽 판정이 통째로 뒤집힌다 —
	* 자기 방 해지가 조용히 성공한 것처럼 보이고(클라이언트는 끊었다고 믿지만 프레임은 계속 온다),
	* 다른 방 해지에 "이 연결의 방은 해지할 수 없습니다"라는 엉뚱한 사유가 나갔다. 두 프레임의
	* 판정 방향이 반대라 공유할 수 있는 것은 모양뿐이고 의미가 아니었다.
	*/
	private Mono<WsFrame> handleSubscribe(UUID requestedThreadId, UUID connectionThreadId, String traceId) {
		if (connectionThreadId.equals(requestedThreadId)) {
			return Mono.empty();
		}
		return Mono.just(new ErrorFrame(connectionThreadId, "FORBIDDEN",
				"아직 이 연결로는 다른 방을 구독할 수 없습니다.", traceId));
	}

	/** {@link #handleSubscribe}와 판정 방향이 반대인 이유는 그쪽 주석에 있다. */
	private Mono<WsFrame> handleUnsubscribe(UUID requestedThreadId, UUID connectionThreadId, String traceId) {
		if (connectionThreadId.equals(requestedThreadId)) {
			return Mono.just(new ErrorFrame(connectionThreadId, "FORBIDDEN",
					"이 연결의 방은 해지할 수 없습니다. 연결을 닫아 주세요.", traceId));
		}
		return Mono.empty();
	}

	/**
	* 입장 인가는 연결할 때 한 번뿐이라, 그 뒤에 참가자에서 빠진 사람이 같은 연결로 계속 말할 수
	* 있다. 그래서 메시지마다 다시 확인한다. LOCKED·ARCHIVED로 바뀐 방도 같은 이유로 여기서
	* 재확인한다(#131) — 잠긴 뒤에도 기존 대화는 계속 읽히지만 새 메시지는 막는다. 연결은 닫지
	* 않는다 — 형식이 틀린 프레임을 에러 프레임만 돌려주고 넘어가는 것과 같은 처리다. 이미 열린
	* 연결로 방송이 계속 가는 것(받기)은 이슈 #135가 다룬다.
	*/
	private Mono<WsFrame> rejectIfLocked(ChatMessageCommand command, UUID roomGeneration, String traceId) {
		return threadMembershipService.isOpenForWriting(command.threadId())
				.flatMap(open -> open
						? dispatch(command, roomGeneration, traceId)
						: Mono.just(new ErrorFrame(command.threadId(), "THREAD_LOCKED",
								"잠기거나 보관된 방에는 메시지를 보낼 수 없습니다.", traceId)));
	}

	/**
	* 재확인 자체가 실패할 수도 있다(DB 장애 등) — 연결 시점 검사(admitOrReject)가 같은 조회를
	* onErrorMap/onErrorResume으로 감싸는 것과 같은 이유로, 여기서도 에러를 그대로 흘려보내지
	* 않는다. 흘려보내면 이 Mono가 합쳐지는 outbound Flux 전체가 에러로 끝나 연결이 비정상
	* 종료된다 — "형식 오류는 연결을 유지한다"는 위 설계 의도와 반대가 된다.
	*/
	private Mono<WsFrame> dispatch(ChatMessageCommand command, UUID roomGeneration, String traceId) {
		try {
			return Mono.justOrEmpty(collabMessageDispatcher.dispatch(command, roomGeneration));
		} catch (RuntimeException error) {
			log.error("WebSocket room broadcast failed threadId={} traceId={}",
					command.threadId(), traceId, error);
			return Mono.just(new ErrorFrame(command.threadId(), "INTERNAL_ERROR",
					"메시지를 방송하지 못했습니다.", traceId));
		}
	}

	private Mono<Void> sendErrorAndClose(WebSocketSession session, UUID sessionId, String code,
			String message, String traceId) {
		ErrorFrame frame = new ErrorFrame(sessionId, code, message, traceId);
		return session.send(Mono.just(session.textMessage(serialize(frame))))
				.then(Mono.defer(() -> session.close(CloseStatus.NORMAL)))
				.doOnError(error -> log.debug("Failed to send WebSocket error frame traceId={}", traceId, error))
				.onErrorResume(ignored -> Mono.empty());
	}

	private ErrorFrame malformed(UUID threadId, String traceId) {
		return new ErrorFrame(threadId, "MALFORMED_REQUEST", "WebSocket 프레임 형식이 올바르지 않습니다.", traceId);
	}

	private String serialize(WsFrame frame) {
		try {
			return objectMapper.writeValueAsString(frame);
		} catch (Exception error) {
			throw new IllegalStateException("WebSocket frame serialization failed", error);
		}
	}

	static Flux<WsFrame> bufferForConnection(Flux<WsFrame> frames, Sinks.One<Void> outboundOverflow) {
		return frames.onBackpressureBuffer(CONNECTION_BUFFER_SIZE,
				ignored -> outboundOverflow.tryEmitEmpty(), BufferOverflowStrategy.DROP_LATEST);
	}

	static String textPayload(WebSocketMessage message) {
		return message.getType() == WebSocketMessage.Type.TEXT ? message.getPayloadAsText() : null;
	}

	private static UUID threadIdOf(WebSocketSession session) {
		String path = session.getHandshakeInfo().getUri().getPath();
		if (!path.startsWith(WS_PATH_PREFIX)) {
			return null;
		}
		String value = path.substring(WS_PATH_PREFIX.length());
		if (value.isEmpty() || value.contains("/")) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static Duration durationUntil(Instant instant) {
		Duration remaining = Duration.between(Instant.now(), instant);
		return remaining.isNegative() ? Duration.ZERO : remaining;
	}

	private static SessionInfo sessionInfoOf(Principal principal) {
		if (principal instanceof JwtAuthenticationToken jwtAuthentication) {
			var jwt = jwtAuthentication.getToken();
			return new SessionInfo(jwt.getSubject(), JwtDisplayNames.of(jwt), jwt.getExpiresAt());
		}
		return new SessionInfo(principal.getName(), principal.getName(), null);
	}

	private static String newTraceId() {
		return UUID.randomUUID().toString();
	}

	/** type만 먼저 읽기 위한 최소 봉투 — 서버 전용 타입 선별(handleInbound 주석 참조)에만 쓴다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record InboundEnvelope(String type) {
	}

	private record SessionInfo(String subject, String displayName, Instant tokenExpiresAt) {
	}

	private static final class UserProvisioningFailure extends RuntimeException {

		UserProvisioningFailure(Throwable cause) {
			super(cause);
		}
	}

	private static final class MembershipLookupFailure extends RuntimeException {

		MembershipLookupFailure(Throwable cause) {
			super(cause);
		}
	}

}
