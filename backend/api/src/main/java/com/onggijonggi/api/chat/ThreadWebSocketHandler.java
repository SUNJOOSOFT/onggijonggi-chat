package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onggijonggi.api.auth.FixedWindowRateLimiter;
import com.onggijonggi.api.auth.JwtDisplayNames;
import com.onggijonggi.api.auth.WsSubProtocolBearerTokenConverter;
import com.onggijonggi.api.auth.UserIdentityService;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.ThrKind;
import java.security.Principal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ThreadWebSocketHandler.java
 * Description : 협업 채팅 WebSocket 연결의 수신·송신 수명과 프레임 처리를 담당한다.
 *
 *               커넥션은 사용자(탭)당 하나이고 여러 방을 나른다(이슈 #161). 수명은 두 층으로 갈린다 —
 *               커넥션(인증·토큰 만료·종료)과 그 위에 room.subscribe로 거는 방별 구독(참가자 확인·방
 *               버퍼·강제 해지)이다. 방 하나에서 일어난 일(참여자 제거, 버퍼 넘침)은 그 구독만 풀고
 *               커넥션과 다른 방은 건드리지 않는다.
 */
@Component
public class ThreadWebSocketHandler implements WebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(ThreadWebSocketHandler.class);

	/** 방 하나가 이 커넥션 앞에 쌓아 둘 수 있는 프레임 수. 커넥션이 아니라 방마다 따로 센다(이슈 #161) —
	 * 한 버퍼를 공유하면 한 방의 폭주가 그 사용자의 모든 방을 끊는다. */
	private static final int ROOM_BUFFER_SIZE = 256;

	/** DIRECT 자동 생성 제목 상한(#158/#159와 동일). */
	private static final int TITLE_MAX_LENGTH = 50;

	private static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4000, "token expired");

	/** 종료 사유가 정해진 뒤 session.send(outbound)가 스스로 완료돼 정상 close로 이어질 시간(이슈 #181).
	 * 이 안에 안 끝나면(느린 소비자처럼 backpressure로 send가 막힌 경우) 직접 close로 넘어간다 —
	 * 그 경로의 close 프레임은 degraded일 수 있으나 그 상황은 클라이언트가 읽지 않는 상태다. */
	private static final Duration CLOSE_DRAIN_GRACE_PERIOD = Duration.ofMillis(500);

	private static final Set<String> SERVER_ONLY_TYPES = Set.of("chat.answer", "presence.join",
			"presence.leave", "presence.snapshot", "error", "system.notice", "chat.queued", "pong");

	private final ObjectMapper objectMapper;

	private final RoomSessionRegistry roomSessionRegistry;

	private final ThreadMessageDispatcher threadMessageDispatcher;

	private final UserIdentityService userIdentityService;

	private final ThreadMembershipService threadMembershipService;

	private final DirectChatTurnService directChatTurnService;

	/**
	 * 이 핸들러만 쓰는 버킷이다(이슈 #74). 핸드셰이크 한도(WsSecurityConfig)와 나누는 이유는
	 * 성격이 달라서다 — 핸드셰이크는 끊길 때마다 한 번이고 메시지는 대화 중 연속 발화다.
	 * 세는 단위는 연결이 아니라 sub다. 연결 단위로 세면 소켓을 끊었다 붙이는 것만으로 카운터가
	 * 초기화되는데, 클라이언트가 백오프로 자동 재연결하므로(ws-connection.ts) 도배를 막지 못하면서
	 * 핸드셰이크 부하만 늘린다. 방 종류로도 나누지 않는다 — 한 sub의 모든 방 발화가 한 버킷이다.
	 */
	private final FixedWindowRateLimiter messageRateLimiter;

	public ThreadWebSocketHandler(ObjectMapper objectMapper, RoomSessionRegistry roomSessionRegistry,
			ThreadMessageDispatcher threadMessageDispatcher, UserIdentityService userIdentityService,
			ThreadMembershipService threadMembershipService, DirectChatTurnService directChatTurnService,
			Clock rateLimitClock,
			@Value("${app.ratelimit.window-seconds:60}") long rateLimitWindowSeconds,
			@Value("${app.ratelimit.ws-message-per-minute:60}") int wsMessagePerMinute) {
		this.objectMapper = objectMapper;
		this.roomSessionRegistry = roomSessionRegistry;
		this.threadMessageDispatcher = threadMessageDispatcher;
		this.userIdentityService = userIdentityService;
		this.threadMembershipService = threadMembershipService;
		this.directChatTurnService = directChatTurnService;
		this.messageRateLimiter =
				new FixedWindowRateLimiter(rateLimitClock, rateLimitWindowSeconds, wsMessagePerMinute);
	}

	@Override
	public List<String> getSubProtocols() {
		return List.of(WsSubProtocolBearerTokenConverter.PROTOCOL_NAME);
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		return session.getHandshakeInfo().getPrincipal()
				.map(ThreadWebSocketHandler::sessionInfoOf)
				.defaultIfEmpty(new SessionInfo("EMPTY", "EMPTY", null))
				.flatMap(info -> userIdentityService.resolveOrProvision(info.subject())
						.onErrorMap(UserProvisioningFailure::new)
						.flatMap(userId -> handleConnection(session, new Connection(UUID.randomUUID(), userId,
								new PresenceParticipant(info.subject(), info.displayName())), info.tokenExpiresAt()))
						.onErrorResume(UserProvisioningFailure.class, error -> {
							String traceId = newTraceId();
							log.error("WebSocket user provisioning failed traceId={}", traceId, error.getCause());
							return sendErrorAndClose(session, "INTERNAL_ERROR",
									"WebSocket 세션을 초기화하지 못했습니다.", traceId);
						}));
	}

	private Mono<Void> handleConnection(WebSocketSession session, Connection connection, Instant tokenExpiresAt) {
		// 종료 사유 하나로 수렴한다 — 클라이언트가 먼저 닫든(peer close), 서버가 토큰 만료로 끊든
		// 모두 여기에 CloseStatus를 넣는다. outbound는 이걸로 끊기고, 실제 session.close()는
		// send(outbound)가 완료된 뒤 단 한 곳(lifecycle)에서 이 사유로 불린다. session.close()가 살아
		// 있는 session.send()와 같은 채널에서 경합하면 CloseWebSocketFrame이 이중 해제돼(refCnt: 0,
		// decrement: 1) 종료 핸드셰이크가 깨지고 클라이언트가 close code를 받지 못한다(이슈 #181).
		Sinks.One<CloseStatus> closeReason = Sinks.one();

		// [#181 계측] 핸드셰이크 통과 시점에 토큰이 exp 대비 몇 초 남았는지 — 근-만료 토큰 무한
		// 재발급(이슈 #181) 원인 규명용, 로그 레벨 debug. 실서버 배포 후 원인이 확정되면 제거한다
		// (진행 상황은 이슈 #181 코멘트에 남긴다 — docs/는 로컬 전용이라 팀 공유가 안 된다).
		Instant now = Instant.now();
		long skewSeconds = tokenExpiresAt == null ? Long.MIN_VALUE
				: Duration.between(now, tokenExpiresAt).toSeconds();
		log.debug("[#181] WS admit connectionId={} sub={} tokenExp={} now={} skewSeconds={}",
				connection.id(), connection.actor().subject(), tokenExpiresAt, now, skewSeconds);

		// session.receive()는 outbound와 분리해 독립 구독한다 — outbound를 사유로 끊을 때
		// session.receive()가 함께 취소되면 Reactor Netty 채널이 즉시 무너져, 뒤이은
		// session.close(status)의 close 프레임이 코드 없이 나가거나(클라이언트 1005) 아예 못
		// 나간다(이슈 #181). 인바운드 처리 결과 프레임은 커넥션 sink로 옮겨 outbound에 실어 보낸다.
		Mono<Void> inboundPump = session.receive()
				.concatMap(message -> handleInbound(message, connection))
				.doOnNext(connection::send)
				// 클라이언트/피어가 먼저 닫으면 receive()가 끝난다 — 정상 종료(1000)로 수렴시킨다.
				.doFinally(ignored -> closeReason.tryEmitValue(CloseStatus.NORMAL))
				.then();

		// outbound는 인바운드 응답·방별 통보와 구독한 방들의 방송으로만 구성한다. session.receive()는
		// 여기 없다 — closeReason으로 이 flux가 끊겨도 인바운드 구독은 살아 있어 채널이 온전하다.
		// WebSocketSession.send()는 세션당 한 번만 구독할 수 있어(Reactor Netty 제약), 나중에 걸리는
		// 구독도 새 send()가 아니라 이 스트림에 끼워 넣는다.
		Flux<WebSocketMessage> outbound = connection.outbound()
				.takeUntilOther(closeReason.asMono())
				.map(frame -> session.textMessage(serialize(frame)));

		// 서버발 종료 트리거 — session.close()는 부르지 않고 closeReason에 사유만 넣는다.
		Mono<Void> tokenExpiry = tokenExpiresAt == null
				? Mono.never()
				: Mono.delay(durationUntil(tokenExpiresAt))
						.doOnNext(ignored -> closeReason.tryEmitValue(TOKEN_EXPIRED))
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
		return Mono.firstWithSignal(lifecycle, inboundPump.then(Mono.never()), tokenExpiry.then(Mono.never()))
				.doFinally(signal -> {
					// [#181 계측] 연결 수명 — 근-만료 토큰 무한 재발급(이슈 #181) 원인 규명용. 실서버
					// 배포 후 원인이 확정되면 제거한다(진행 상황은 이슈 #181 코멘트에 남긴다).
					log.debug("[#181] WS session end connectionId={} signal={} livedMs={}",
							connection.id(), signal, Duration.between(now, Instant.now()).toMillis());
					// 닫힘 표시를 먼저 한다 — 이 뒤에 끝나는 구독 요청은 스스로 방에서 빠진다(subscribe).
					connection.markClosed();
					roomSessionRegistry.leaveAll(connection.id(), connection.actor())
							.forEach((threadId, generation) -> threadMessageDispatcher.closeGeneration(threadId,
									generation));
				});
	}

	/**
	* 인바운드 프레임 하나를 처리한다. 파싱은 두 걸음이다 — 먼저 type만 읽어 서버 전용 타입을 조용히
	* 거르고(#157: 위조해 보내도 오류를 돌려주지 않는다), 그다음 InboundFrame 화이트리스트로 읽는다.
	* 한 걸음으로 합치면 서버 전용 타입이 화이트리스트에 없다는 이유로 MALFORMED_REQUEST를 받게 된다.
	*
	* 방을 알 수 없는 형식 오류는 threadId 없이(커넥션 전역으로) 돌려준다.
	*/
	private Mono<WsFrame> handleInbound(WebSocketMessage message, Connection connection) {
		String traceId = newTraceId();
		String payload = textPayload(message);
		if (payload == null) {
			return Mono.just(malformed(null, traceId));
		}

		InboundFrame inbound;
		try {
			if (SERVER_ONLY_TYPES.contains(objectMapper.readValue(payload, InboundEnvelope.class).type())) {
				return Mono.empty();
			}
			inbound = objectMapper.readValue(payload, InboundFrame.class);
		} catch (Exception error) {
			log.debug("Malformed WebSocket frame connectionId={} traceId={}", connection.id(), traceId, error);
			return Mono.just(malformed(null, traceId));
		}

		// Java 17이라 sealed 타입의 switch 패턴 매칭을 쓸 수 없다. 분기를 빠뜨려도 컴파일러가 못
		// 잡고 마지막 malformed로 떨어지므로, InboundFrame에 타입을 더할 때 여기를 함께 본다.
		if (inbound instanceof InboundChatMessage chatMessage) {
			return handleChatMessage(chatMessage, connection, traceIdOf(chatMessage.turnId(), traceId));
		}
		if (inbound instanceof InboundChatCancel cancel) {
			return handleCancel(cancel, connection, traceIdOf(cancel.turnId(), traceId));
		}
		if (inbound instanceof InboundRoomSubscribe subscribe) {
			return handleSubscribe(subscribe.threadId(), connection, traceId);
		}
		if (inbound instanceof InboundRoomUnsubscribe unsubscribe) {
			return handleUnsubscribe(unsubscribe.threadId(), connection, traceId);
		}
		if (inbound instanceof InboundPing) {
			// 주기적으로 묻고 무응답이면 다시 붙는 판단은 클라이언트가 한다(ws-connection.ts) — 서버는 답만 한다.
			return Mono.just(new PongFrame());
		}
		return Mono.just(malformed(null, traceId));
	}

	private Mono<WsFrame> handleChatMessage(InboundChatMessage inbound, Connection connection, String traceId) {
		UUID threadId = inbound.threadId();
		if (threadId == null || inbound.content() == null || inbound.content().isBlank()) {
			return Mono.just(malformed(threadId, traceId));
		}

		// 한도를 넘으면 이 프레임만 버리고 연결은 유지한다(이슈 #74). DIRECT bootstrap 예외도 이
		// 한도를 그대로 받는다(이슈 #162, §2.1) — 구독 없이 반복 시도해도 DB를 무한히 못 두드린다.
		PresenceParticipant actor = connection.actor();
		if (!messageRateLimiter.tryAcquire(actor.subject())) {
			log.debug("WebSocket message rate limited threadId={} traceId={}", threadId, traceId);
			return Mono.just(new ErrorFrame(threadId, "RATE_LIMITED",
					"메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요.", traceId));
		}

		// 구독하지 않은 방에는 원래 말할 수 없다(이슈 #161). 단, 구독되지 않은 첫 발화는 DIRECT
		// bootstrap 후보다(이슈 #162) — 그 경로로 넘긴다.
		Optional<UUID> roomGeneration = roomSessionRegistry.generationFor(threadId, connection.id());
		if (roomGeneration.isEmpty()) {
			if (inbound.clientMsgId() == null || inbound.turnId() == null) {
				return Mono.just(malformed(threadId, traceId));
			}
			return bootstrapDirect(threadId, connection, actor, inbound, traceId);
		}

		// kind는 dispatcher가 DIRECT·COLLAB을 가르는 데 필요하다(이슈 #162) — 참가자 재확인과
		// 함께 조회해 왕복을 하나 더 늘리지 않는다.
		return Mono.zip(threadMembershipService.isActiveParticipant(threadId, connection.userId()),
						threadMembershipService.kindOf(threadId))
				.flatMap(check -> {
					boolean participant = check.getT1();
					Optional<ThrKind> kind = check.getT2();
					if (!participant) {
						return Mono.just(new ErrorFrame(threadId, "FORBIDDEN",
								"이 방에 메시지를 보낼 권한이 없습니다.", traceId));
					}
					if (kind.isEmpty()) {
						return Mono.just(new ErrorFrame(threadId, "FORBIDDEN",
								"이 방에 메시지를 보낼 권한이 없습니다.", traceId));
					}
					if (kind.get() == ThrKind.DIRECT) {
						if (inbound.clientMsgId() == null || inbound.turnId() == null) {
							return Mono.just(malformed(threadId, traceId));
						}
						return threadMembershipService.isActiveDirectOwner(threadId, connection.userId())
								.flatMap(owner -> owner
										? prepareDirectTurn(threadId, connection.userId(), inbound.content(),
												inbound.clientMsgId())
								.flatMap(stored -> stored.replay()
										? handleDirectReplay(threadId, roomGeneration.get(), connection, actor,
												inbound, traceId, stored)
										: rejectIfLocked(
												new ChatMessageCommand(threadId, kind.get(), connection.userId(),
														actor.subject(), actor.displayName(), inbound.content(),
														inbound.model(), inbound.clientMsgId(), inbound.turnId(),
														connection.id(), traceId, toReservedTurn(stored)),
												roomGeneration.get(), traceId))
								.onErrorResume(IdempotencyKeyConflictException.class,
										error -> Mono.just(idempotencyConflict(threadId, traceId)))
								.onErrorResume(error -> reportDirectReserveFailure(threadId, roomGeneration.get(),
										inbound.turnId(), traceId, error))
										: Mono.just(new ErrorFrame(threadId, "FORBIDDEN",
												"이 방에 메시지를 보낼 권한이 없습니다.", traceId)));
					}
					ChatMessageCommand command = new ChatMessageCommand(threadId, kind.get(), connection.userId(),
							actor.subject(), actor.displayName(), inbound.content(), inbound.model(),
							inbound.clientMsgId(), inbound.turnId(), connection.id(), traceId, null);
					return rejectIfLocked(command, roomGeneration.get(), traceId);
				})
				.onErrorResume(error -> {
					log.error("WebSocket membership re-check failed threadId={} traceId={}",
							threadId, traceId, error);
					return Mono.just(new ErrorFrame(threadId, "INTERNAL_ERROR",
							"메시지를 처리하지 못했습니다.", traceId));
				});
	}

	/**
	* 기존 DIRECT 방에 이어 쓰는 발화마다 HUMAN·PENDING AGENT를 미리 예약한다(이슈 #162) — dispatcher가
	* 이 값을 그대로 재사용해 중복 PENDING·seq를 만들지 않는다. bootstrap(첫 발화)은 별도 경로에서
	* 이미 예약을 마친 뒤에만 이 핸들러로 들어오므로 여기서는 "기존 방 이어쓰기"만 다룬다.
	*
	* idempotencyKey가 이미 쓰인 적 있으면(이슈 #233) 새로 저장하지 않고 StoredTurn.replay()=true로
	* 기존 값을 그대로 돌려준다 — 호출부가 그 경우를 보고 별도로 분기한다.
	*/
	private Mono<DirectChatTurnService.StoredTurn> prepareDirectTurn(UUID threadId, UUID userId, String content,
			String idempotencyKey) {
		return Mono.fromCallable(() -> directChatTurnService.prepareExistingWithPendingAgentBlocking(threadId,
						userId, content, idempotencyKey))
				.subscribeOn(Schedulers.boundedElastic());
	}

	private static ChatMessageCommand.ReservedTurn toReservedTurn(DirectChatTurnService.StoredTurn stored) {
		return new ChatMessageCommand.ReservedTurn(stored.humanMessageId(), stored.humanSeq(),
				stored.agentMessageId(), stored.agentSeq());
	}

	private static ErrorFrame idempotencyConflict(UUID threadId, String traceId) {
		return new ErrorFrame(threadId, "IDEMPOTENCY_KEY_CONFLICT", "이미 다른 내용으로 쓰인 요청 id입니다.", traceId);
	}

	/**
	* replay 응답(이슈 #233) — echo는 정상 발화와 같은 경로(room 방송)로 보내고(멱등이라 다른 탭이
	* 다시 받아도 무해하다), AGENT 턴의 현재 상태에 따라 이 커넥션에만 보낼 응답을 유니캐스트로
	* 돌려준다. rejectIfLocked·dispatcher 큐에는 들어가지 않는다 — 새 발화가 아니라 기존 결과를
	* 돌려주는 것뿐이라 FIFO·레이트리밋(#74)을 소모하지 않는다.
	*
	* 그 턴이 아직 살아있으면(진행 중이거나 FIFO 대기 중이거나 워커가 아직 안 꺼낸 —
	* ThreadMessageDispatcher.activeTurnContentIfMatches 참고) 지금까지 누적된 내용(대기
	* 중이면 빈 문자열)을 그 turnId로 담아 보낸다 — mergeOtherTurnAnswer(프런트)의 "처음 본
	* 프레임=시작 텍스트" 경로가 캐치업 역할을 한다. 이미 끝났으면(COMPLETE·CANCELLED·DENIED)
	* 저장된 결과를, FAILED면 ChatAnswerStatus에 FAILED가 없어 ErrorFrame을 대신 보낸다. DB는
	* PENDING인데 메모리 어디에도(활성·대기·인플라이트) 없으면(서버 재시작 등으로 진짜 고아)
	* 새 발화로 복구한다.
	*/
	private Mono<WsFrame> handleDirectReplay(UUID threadId, UUID roomGeneration, Connection connection,
			PresenceParticipant actor, InboundChatMessage inbound, String traceId,
			DirectChatTurnService.StoredTurn stored) {
		try {
			roomSessionRegistry.broadcastIfCurrent(threadId, roomGeneration,
					new ChatMessageFrame(threadId, stored.humanMessageId(), inbound.clientMsgId(), inbound.turnId(),
							stored.humanSeq(), actor.subject(), actor.displayName(), inbound.content()));
		} catch (RuntimeException ignored) {
			// echo 재방송 실패는 무시한다 — 낙관적 렌더링이 이미 화면에 있어 치명적이지 않다.
		}

		Optional<String> streaming = threadMessageDispatcher.activeTurnContentIfMatches(threadId, roomGeneration,
				stored.agentMessageId());
		if (streaming.isPresent()) {
			return Mono.<WsFrame>just(new ChatAnswerFrame(threadId, stored.agentMessageId(), inbound.turnId(),
					threadMessageDispatcher.resolveModelId(inbound.model()), stored.agentSeq(), streaming.get(),
					List.of(), false, ChatAnswerStatus.STREAMING));
		}

		return switch (stored.agentStatus()) {
			case COMPLETE -> Mono
					.fromCallable(() -> directChatTurnService.findAgentMessage(stored.agentMessageId()))
					.subscribeOn(Schedulers.boundedElastic())
					.map(msg -> (WsFrame) new ChatAnswerFrame(threadId, stored.agentMessageId(), inbound.turnId(),
							threadMessageDispatcher.resolveModelId(inbound.model()), stored.agentSeq(),
							msg.map(Msg::getContent).orElse(""), List.of(), false, ChatAnswerStatus.DONE));
			case CANCELLED -> Mono.<WsFrame>just(new ChatAnswerFrame(threadId, stored.agentMessageId(),
					inbound.turnId(), threadMessageDispatcher.resolveModelId(inbound.model()), stored.agentSeq(), "",
					List.of(), false, ChatAnswerStatus.CANCELLED));
			case DENIED -> Mono.<WsFrame>just(new ChatAnswerFrame(threadId, stored.agentMessageId(),
					inbound.turnId(), threadMessageDispatcher.resolveModelId(inbound.model()), stored.agentSeq(), "",
					List.of(), false, ChatAnswerStatus.DENIED));
			case FAILED -> Mono.<WsFrame>just(new ErrorFrame(threadId, "MODEL_UNAVAILABLE",
					"이전 시도에서 응답을 만들지 못했습니다.", traceId));
			case PENDING -> recoverOrphanedDirect(threadId, roomGeneration, connection, actor, inbound, traceId,
					stored);
		};
	}

	/**
	* DB는 PENDING인데 메모리상 활성 턴이 어디에도 없는(서버 재시작 등으로 고아가 된) 경우를
	* 복구한다(이슈 #233) — 그 msg를 FAILED로 닫고 이번 요청을 처음부터 다시(키가 없었던 것처럼)
	* 진행한다. 결과는 평범한 첫 이어쓰기 발화와 완전히 같다 — 프런트가 이 경로를 구분해서 처리할
	* 필요가 없다.
	*/
	private Mono<WsFrame> recoverOrphanedDirect(UUID threadId, UUID roomGeneration, Connection connection,
			PresenceParticipant actor, InboundChatMessage inbound, String traceId,
			DirectChatTurnService.StoredTurn orphaned) {
		return Mono.fromCallable(() -> directChatTurnService.recoverOrphanedTurnBlocking(threadId,
						connection.userId(), inbound.content(), inbound.clientMsgId(), orphaned.agentMessageId()))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(fresh -> rejectIfLocked(
						new ChatMessageCommand(threadId, ThrKind.DIRECT, connection.userId(), actor.subject(),
								actor.displayName(), inbound.content(), inbound.model(), inbound.clientMsgId(),
								inbound.turnId(), connection.id(), traceId, toReservedTurn(fresh)),
						roomGeneration, traceId))
				.onErrorResume(error -> reportDirectReserveFailure(threadId, roomGeneration, inbound.turnId(),
						traceId, error));
	}

	/**
	* 기존 DIRECT 이어쓰기의 HUMAN·PENDING AGENT 예약 저장 자체가 실패하면(이슈 #162, §2.2) 아직
	* 아무것도 방송되지 않아 되돌릴 방송이 없다 — 방 전체에 warning
	* system.notice(MESSAGE_DELIVERY_FAILED)를 traceId=turnId로 한 번 방송한다. 요청 탭의 frame
	* bridge만 이 traceId를 보고 그 턴을 terminal 오류로 끝내고, 다른 탭은 배너로만 본다.
	*/
	private Mono<WsFrame> reportDirectReserveFailure(UUID threadId, UUID roomGeneration, UUID turnId, String traceId,
			Throwable error) {
		log.error("DIRECT 발화 저장 실패 threadId={} traceId={}", threadId, traceId, error);
		try {
			roomSessionRegistry.broadcastIfCurrent(threadId, roomGeneration, new SystemNoticeFrame(threadId,
					"warning", "MESSAGE_DELIVERY_FAILED", "메시지를 저장하지 못했습니다.", traceIdOf(turnId, traceId)));
		} catch (RuntimeException ignored) {
			// 통지는 한 번만 시도하고 재시도하지 않는다 — dispatcher의 broadcastQuietly와 같은 결.
		}
		return Mono.empty();
	}

	/**
	* 구독되지 않은 첫 chat.message에만 여는 DIRECT bootstrap 경로다(이슈 #162, §2.1). 요청자가
	* 소유할 수 있는 새·기존 DIRECT 방을 원자적으로 만들거나 재확인한 뒤, 성공하면 이 연결을 presence
	* 없는 경로로 자동 구독하고 같은 발화를 정상 dispatch로 흘려보낸다. 클라이언트 sessionId 충돌
	* (동시 최초 생성)은 #159와 같은 패턴으로 재조회해 이어 쓴다.
	*
	* 기존 방이 COLLAB이거나 남의 것이면 DirectChatTurnService가 404를 던진다 — 존재를 드러내지
	* 않으면서도 클라이언트의 기존 NOT_SUBSCRIBED 재구독 복구 경로를 그대로 태우려고 이 경우도
	* NOT_SUBSCRIBED로 답한다. 진짜 권한이 없으면 뒤이은 room.subscribe가 기존 FORBIDDEN으로
	* 마무리한다 — 새 오류 의미를 만들지 않는다.
	*/
	private Mono<WsFrame> bootstrapDirect(UUID threadId, Connection connection, PresenceParticipant actor,
			InboundChatMessage inbound, String traceId) {
		String title = titleFor(inbound.content());
		return Mono
				.fromCallable(() -> directChatTurnService.prepareOrCreateWithPendingAgentBlocking(threadId,
						connection.userId(), inbound.content(), title, inbound.clientMsgId()))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(DataIntegrityViolationException.class,
						error -> Mono.fromCallable(() -> directChatTurnService
										.prepareExistingWithPendingAgentBlocking(threadId, connection.userId(),
												inbound.content(), inbound.clientMsgId()))
								.subscribeOn(Schedulers.boundedElastic()))
				.flatMap(stored -> completeBootstrap(threadId, connection, actor, inbound, traceId, stored))
				.onErrorResume(IdempotencyKeyConflictException.class,
						error -> Mono.just(idempotencyConflict(threadId, traceId)))
				.onErrorResume(ResponseStatusException.class, error -> Mono.just(notSubscribed(threadId, traceId)))
				.onErrorResume(error -> {
					log.error("DIRECT bootstrap 실패 threadId={} traceId={}", threadId, traceId, error);
					return Mono.just(new ErrorFrame(threadId, "INTERNAL_ERROR", "방을 시작하지 못했습니다.", traceId));
				});
	}

	/** bootstrap 트랜잭션 성공 뒤 자동 구독하고 같은 발화를 dispatch로 흘려보낸다(이슈 #162). */
	private Mono<WsFrame> completeBootstrap(UUID threadId, Connection connection, PresenceParticipant actor,
			InboundChatMessage inbound, String traceId, DirectChatTurnService.StoredTurn stored) {
		subscribe(connection, threadId, false);
		Optional<UUID> generation = roomSessionRegistry.generationFor(threadId, connection.id());
		if (generation.isEmpty()) {
			// subscribe() 도중 커넥션이 이미 닫혀 leaveRoom으로 빠졌다 — 보낼 곳이 없다.
			return Mono.empty();
		}
		if (stored.replay()) {
			return handleDirectReplay(threadId, generation.get(), connection, actor, inbound, traceId, stored);
		}
		ChatMessageCommand command = new ChatMessageCommand(threadId, ThrKind.DIRECT, connection.userId(),
				actor.subject(), actor.displayName(), inbound.content(), inbound.model(), inbound.clientMsgId(),
				inbound.turnId(), connection.id(), traceId, toReservedTurn(stored));
		return rejectIfLocked(command, generation.get(), traceId);
	}

	/** DIRECT 자동 생성 제목 — 첫 HUMAN 발화를 trim하고 50자를 넘으면 자른다(#158/#159와 동일 규칙). */
	private static String titleFor(String content) {
		String trimmed = content.trim();
		return trimmed.length() > TITLE_MAX_LENGTH ? trimmed.substring(0, TITLE_MAX_LENGTH) : trimmed;
	}

	/**
	* 취소는 DB를 보지 않는다 — 멈출 대상은 메모리에 있는 턴이고, 어느 커넥션이 불렀는지도 디스패처가
	* 들고 있다. 참가자 재확인을 하지 않는 이유도 같다: 방에서 빠진 사람이 자기가 띄운 턴을 거두는 것을
	* 막을 이유가 없다. 발화 빈도 제한(#74)에도 세지 않는다 — 발화가 아니고 비용도 없다.
	*
	* 이 커넥션이 구독하지 않은 방의 취소는 찾을 턴이 없는 것과 같다 — 조용히 넘어간다.
	*/
	private Mono<WsFrame> handleCancel(InboundChatCancel inbound, Connection connection, String traceId) {
		if (inbound.threadId() == null || inbound.turnId() == null) {
			return Mono.just(malformed(inbound.threadId(), traceId));
		}
		Optional<UUID> generation = roomSessionRegistry.generationFor(inbound.threadId(), connection.id());
		if (generation.isEmpty()) {
			return Mono.empty();
		}
		return threadMembershipService.kindOf(inbound.threadId())
				.flatMap(kind -> {
					if (kind.filter(value -> value == ThrKind.DIRECT).isPresent()) {
						return threadMembershipService.isActiveDirectOwner(inbound.threadId(), connection.userId())
								.flatMap(owner -> owner
										? Mono.<WsFrame>fromRunnable(() -> threadMessageDispatcher.cancel(inbound.threadId(),
												generation.get(), inbound.turnId(), connection.id()))
										: Mono.just(new ErrorFrame(inbound.threadId(), "FORBIDDEN",
												"이 방의 AI 응답을 취소할 권한이 없습니다.", traceId)));
					}
					if (kind.isPresent()) {
						threadMessageDispatcher.cancel(inbound.threadId(), generation.get(), inbound.turnId(), connection.id());
					}
					return Mono.empty();
				});
	}

	/**
	* 방 하나를 이 커넥션에 건다(이슈 #161). 인가는 여기서 한 번 하고, 발화마다 다시 한다
	* (handleChatMessage) — 구독 뒤에 참가자에서 빠진 사람이 같은 구독으로 계속 말할 수 없게.
	*
	* 참가자가 아니면 FORBIDDEN을 그 방 threadId로 돌려주고 커넥션은 유지한다 — 다른 방 구독까지 끊을
	* 이유가 없다. 화면(room-state.ts)은 이 코드를 받으면 그 방을 닫고 다시 구독하지 않는다.
	*
	* 인바운드는 커넥션마다 순서대로 처리되므로(concatMap), 이 Mono가 끝나야 뒤따라 온 발화가 처리된다
	* — 클라이언트는 구독 프레임 바로 뒤에 발화를 이어 보내도 된다.
	*/
	private Mono<WsFrame> handleSubscribe(UUID threadId, Connection connection, String traceId) {
		if (threadId == null) {
			return Mono.just(malformed(null, traceId));
		}
		if (roomSessionRegistry.generationFor(threadId, connection.id()).isPresent()) {
			return Mono.empty();
		}
		return threadMembershipService.isActiveParticipant(threadId, connection.userId())
				.flatMap(participant -> participant
						? threadMembershipService.kindOf(threadId).flatMap(kind -> {
							if (kind.filter(value -> value == ThrKind.DIRECT).isPresent()) {
								return threadMembershipService.isActiveDirectOwner(threadId, connection.userId())
										.flatMap(owner -> owner
												? Mono.<WsFrame>fromRunnable(() -> subscribe(connection, threadId, false))
												: Mono.just(new ErrorFrame(threadId, "FORBIDDEN", "이 방에 들어갈 권한이 없습니다.", traceId)));
							}
							return kind.isPresent()
									? Mono.<WsFrame>fromRunnable(() -> subscribe(connection, threadId))
									: Mono.just(new ErrorFrame(threadId, "FORBIDDEN", "이 방에 들어갈 권한이 없습니다.", traceId));
						})
						: Mono.just(new ErrorFrame(threadId, "FORBIDDEN", "이 방에 들어갈 권한이 없습니다.", traceId)))
				.onErrorResume(error -> {
					log.error("WebSocket room subscription failed threadId={} traceId={}", threadId, traceId, error);
					return Mono.just(new ErrorFrame(threadId, "INTERNAL_ERROR", "방 구독을 처리하지 못했습니다.",
							traceId));
				});
	}

	/** COLLAB 구독 진입점 — presence 방송을 켠 채로 {@link #subscribe(Connection, UUID, boolean)}를 부른다. */
	private void subscribe(Connection connection, UUID threadId) {
		subscribe(connection, threadId, true);
	}

	/**
	* 방에 등록하고 그 방의 프레임을 이 커넥션 outbound에 끼워 넣는다. 참여자 스냅샷(#26)은 방송이
	* 아니라 이 구독의 값이라 방 버퍼 밖에서 맨 앞에 붙인다 — 클라이언트는 이것을 구독 완료로 읽는다.
	* presenceEnabled가 false면(DIRECT, 이슈 #162) presence 자체가 없는 방이라 스냅샷을 붙이지
	* 않는다 — 붙이면 클라이언트가 참가자 0명짜리 presence 상태를 만들어야 하는 의미 없는 부담이 된다.
	*
	* 강제 해지(evict)와 버퍼 넘침 신호는 방송·evict를 부른 스레드가 방 상태 잠금을 쥔 채 낸다. 그
	* 자리에서 곧장 leave를 부르면 순회 중인 연결 목록을 고치게 되므로 스레드를 옮겨 처리한다.
	*/
	private void subscribe(Connection connection, UUID threadId, boolean presenceEnabled) {
		RoomSessionRegistry.RoomMembership membership =
				roomSessionRegistry.join(threadId, connection.id(), connection.actor(), presenceEnabled);
		if (connection.isClosed()) {
			// 멤버십 조회가 도는 사이 커넥션이 끝났다 — 끝날 때 돈 leaveAll이 이 방을 못 봤을 수 있다.
			leaveRoom(connection, threadId);
			return;
		}

		Sinks.One<Void> overflow = Sinks.one();
		Flux<WsFrame> roomFrames = bufferForRoom(membership.frames(), overflow).takeUntilOther(membership.left());
		connection.attach(presenceEnabled ? roomFrames.startWith(membership.snapshot()) : roomFrames);

		membership.kicked()
				.publishOn(Schedulers.parallel())
				.doOnSuccess(ignored -> {
					log.debug("Unsubscribing evicted connection threadId={} connectionId={}", threadId,
							connection.id());
					endSubscription(connection, threadId, new ErrorFrame(threadId, "FORBIDDEN",
							"참여자 명단에서 제외되어 이 방 구독이 해지됐습니다.", newTraceId()));
				})
				.subscribe();
		overflow.asMono()
				.publishOn(Schedulers.parallel())
				.doOnSuccess(ignored -> {
					log.warn("Unsubscribing slow room consumer threadId={} connectionId={}", threadId,
							connection.id());
					endSubscription(connection, threadId, notSubscribed(threadId, newTraceId()));
				})
				.subscribe();
	}

	/** 서버가 이 방 구독만 푼다(참여자 제거·버퍼 넘침). 방에서 뺀 뒤 사유를 그 방 threadId로 알린다. */
	private void endSubscription(Connection connection, UUID threadId, ErrorFrame reason) {
		leaveRoom(connection, threadId);
		connection.send(reason);
	}

	private Mono<WsFrame> handleUnsubscribe(UUID threadId, Connection connection, String traceId) {
		if (threadId == null) {
			return Mono.just(malformed(null, traceId));
		}
		leaveRoom(connection, threadId);
		return Mono.empty();
	}

	/** 방의 마지막 연결이 빠지면 소켓이 끊길 때와 똑같이 그 방 세대의 AI 작업을 닫는다. */
	private void leaveRoom(Connection connection, UUID threadId) {
		roomSessionRegistry.leave(threadId, connection.id(), connection.actor())
				.ifPresent(generation -> threadMessageDispatcher.closeGeneration(threadId, generation));
	}

	/**
	* 입장 인가는 구독할 때 한 번뿐이라, 그 뒤에 참가자에서 빠진 사람이 같은 구독으로 계속 말할 수
	* 있다. 그래서 메시지마다 다시 확인한다. LOCKED·ARCHIVED로 바뀐 방도 같은 이유로 여기서
	* 재확인한다(#131) — 잠긴 뒤에도 기존 대화는 계속 읽히지만 새 메시지는 막는다. 연결은 닫지
	* 않는다 — 형식이 틀린 프레임을 에러 프레임만 돌려주고 넘어가는 것과 같은 처리다. 이미 걸린
	* 구독으로 방송이 계속 가는 것(받기)은 이슈 #135가 다룬다.
	*/
	private Mono<WsFrame> rejectIfLocked(ChatMessageCommand command, UUID roomGeneration, String traceId) {
		return threadMembershipService.isOpenForWriting(command.threadId())
				.flatMap(open -> open
						? dispatch(command, roomGeneration, traceId)
						: Mono.just(new ErrorFrame(command.threadId(), "THREAD_LOCKED",
								"잠기거나 보관된 방에는 메시지를 보낼 수 없습니다.", traceId)));
	}

	/**
	* 재확인 자체가 실패할 수도 있다(DB 장애 등) — 구독 시점 검사(handleSubscribe)가 같은 조회를
	* onErrorResume으로 감싸는 것과 같은 이유로, 여기서도 에러를 그대로 흘려보내지 않는다.
	* 흘려보내면 이 Mono가 합쳐지는 인바운드 처리 전체가 에러로 끝나 연결이 비정상 종료된다 —
	* "형식 오류는 연결을 유지한다"는 위 설계 의도와 반대가 된다.
	*/
	private Mono<WsFrame> dispatch(ChatMessageCommand command, UUID roomGeneration, String traceId) {
		try {
			return Mono.justOrEmpty(threadMessageDispatcher.dispatch(command, roomGeneration));
		} catch (RuntimeException error) {
			log.error("WebSocket room broadcast failed threadId={} traceId={}",
					command.threadId(), traceId, error);
			return Mono.just(new ErrorFrame(command.threadId(), "INTERNAL_ERROR",
					"메시지를 방송하지 못했습니다.", traceId));
		}
	}

	/** 커넥션 자체를 세우지 못한 실패라 threadId가 없다(커넥션 전역). */
	private Mono<Void> sendErrorAndClose(WebSocketSession session, String code, String message, String traceId) {
		ErrorFrame frame = new ErrorFrame(null, code, message, traceId);
		return session.send(Mono.just(session.textMessage(serialize(frame))))
				.then(Mono.defer(() -> session.close(CloseStatus.NORMAL)))
				.doOnError(error -> log.debug("Failed to send WebSocket error frame traceId={}", traceId, error))
				.onErrorResume(ignored -> Mono.empty());
	}

	private ErrorFrame malformed(UUID threadId, String traceId) {
		return new ErrorFrame(threadId, "MALFORMED_REQUEST", "WebSocket 프레임 형식이 올바르지 않습니다.", traceId);
	}

	/**
	* 이 커넥션에 그 방 구독이 없다(이슈 #161). 구독하지 않은 방에 보낸 발화에도, 버퍼가 넘쳐 서버가
	* 구독을 푼 때에도 같은 코드로 알린다 — 클라이언트는 둘 다 "다시 구독하고 따라잡는다"로 답하면 된다.
	*/
	private ErrorFrame notSubscribed(UUID threadId, String traceId) {
		return new ErrorFrame(threadId, "NOT_SUBSCRIBED", "이 방을 구독하고 있지 않습니다.", traceId);
	}

	private String serialize(WsFrame frame) {
		try {
			return objectMapper.writeValueAsString(frame);
		} catch (Exception error) {
			throw new IllegalStateException("WebSocket frame serialization failed", error);
		}
	}

	static Flux<WsFrame> bufferForRoom(Flux<WsFrame> frames, Sinks.One<Void> overflow) {
		return frames.onBackpressureBuffer(ROOM_BUFFER_SIZE,
				ignored -> overflow.tryEmitEmpty(), BufferOverflowStrategy.DROP_LATEST);
	}

	static String textPayload(WebSocketMessage message) {
		return message.getType() == WebSocketMessage.Type.TEXT ? message.getPayloadAsText() : null;
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

	/** 턴 식별자가 있으면 그 값을 traceId로 쓴다(이슈 #160) — 한 턴이 내는 프레임과 로그를 이어 볼 수 있게. */
	private static String traceIdOf(UUID turnId, String fallback) {
		return turnId == null ? fallback : turnId.toString();
	}

	/**
	* 커넥션 하나의 송신 쪽 상태(이슈 #161). 프레임이 들어오는 길은 둘이다 — 인바운드 응답·방별 통보가
	* 한 장씩 오는 frames와, 구독이 걸릴 때마다 방 스트림 하나가 통째로 붙는 rooms다. 방 스트림은 각자
	* 버퍼를 달고 오므로 합칠 때 동시성 제한을 두지 않는다.
	*
	* 두 sink에 넣는 쪽은 인바운드 처리·강제 해지·버퍼 넘침으로 스레드가 여럿이라 잠근다 — Sinks는
	* 동시에 넣으면 FAIL_NON_SERIALIZED로 조용히 버린다.
	*/
	private static final class Connection {

		private final UUID id;

		private final UUID userId;

		private final PresenceParticipant actor;

		private final Sinks.Many<WsFrame> frames = Sinks.many().unicast().onBackpressureBuffer();

		private final Sinks.Many<Flux<WsFrame>> rooms = Sinks.many().unicast().onBackpressureBuffer();

		private final AtomicBoolean closed = new AtomicBoolean();

		Connection(UUID id, UUID userId, PresenceParticipant actor) {
			this.id = id;
			this.userId = userId;
			this.actor = actor;
		}

		UUID id() {
			return id;
		}

		UUID userId() {
			return userId;
		}

		PresenceParticipant actor() {
			return actor;
		}

		synchronized void send(WsFrame frame) {
			frames.tryEmitNext(frame);
		}

		synchronized void attach(Flux<WsFrame> roomFrames) {
			rooms.tryEmitNext(roomFrames);
		}

		Flux<WsFrame> outbound() {
			return Flux.merge(frames.asFlux(), rooms.asFlux().flatMap(Function.identity(), Integer.MAX_VALUE));
		}

		void markClosed() {
			closed.set(true);
		}

		boolean isClosed() {
			return closed.get();
		}
	}

	/** type만 먼저 읽기 위한 최소 봉투 — 서버 전용 타입 선별(handleInbound 주석)에만 쓴다. */
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

}
