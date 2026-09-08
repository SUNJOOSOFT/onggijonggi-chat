package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.onggijonggi.api.auth.WsSubProtocolBearerTokenConverter;
import com.onggijonggi.api.auth.UserIdentityService;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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

	private static final Set<String> SERVER_ONLY_TYPES = Set.of("chat.answer", "presence.join", "presence.leave", "error");

	private final ObjectMapper objectMapper;

	private final RoomSessionRegistry roomSessionRegistry;

	private final CollabMessageDispatcher collabMessageDispatcher;

	private final UserIdentityService userIdentityService;

	private final ThreadMembershipService threadMembershipService;

	public CollabWebSocketHandler(ObjectMapper objectMapper, RoomSessionRegistry roomSessionRegistry,
			CollabMessageDispatcher collabMessageDispatcher, UserIdentityService userIdentityService,
			ThreadMembershipService threadMembershipService) {
		this.objectMapper = objectMapper;
		this.roomSessionRegistry = roomSessionRegistry;
		this.collabMessageDispatcher = collabMessageDispatcher;
		this.userIdentityService = userIdentityService;
		this.threadMembershipService = threadMembershipService;
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
				.defaultIfEmpty(new SessionInfo("EMPTY", null))
				.flatMap(info -> userIdentityService.resolveOrProvision(info.subject())
						.onErrorMap(UserProvisioningFailure::new)
						.flatMap(userId -> admitOrReject(session, threadId, userId, info.tokenExpiresAt()))
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
			Instant tokenExpiresAt) {
		return threadMembershipService.isActiveParticipant(threadId, userId)
				.onErrorMap(MembershipLookupFailure::new)
				.flatMap(participant -> participant
						? handleRoomSession(session, threadId, userId, tokenExpiresAt)
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
			Instant tokenExpiresAt) {
		UUID connectionId = UUID.randomUUID();
		Sinks.One<Void> inboundDone = Sinks.one();
		Sinks.One<Void> outboundOverflow = Sinks.one();
		RoomSessionRegistry.RoomMembership membership = roomSessionRegistry.join(threadId, connectionId, userId);

		Flux<WsFrame> roomFrames = bufferForConnection(
				membership.frames(), outboundOverflow);

		Flux<WsFrame> inboundResponses = session.receive()
				.concatMap(message -> handleInbound(message, threadId, userId, membership.generation()))
				.doFinally(ignored -> inboundDone.tryEmitEmpty());

		Flux<WebSocketMessage> outbound = Flux.merge(roomFrames, inboundResponses)
				.takeUntilOther(inboundDone.asMono())
				.map(frame -> session.textMessage(serialize(frame)));

		Mono<Void> messageLoop = session.send(outbound).then(session.close(CloseStatus.NORMAL));
		Mono<Void> tokenExpiry = tokenExpiresAt == null
				? Mono.never()
				: Mono.delay(durationUntil(tokenExpiresAt)).then(session.close(TOKEN_EXPIRED));
		Mono<Void> slowConsumer = outboundOverflow.asMono()
				.doOnSuccess(ignored -> log.warn(
						"Closing slow WebSocket consumer threadId={} connectionId={}", threadId, connectionId))
				.then(session.close(SLOW_CONSUMER));

		return Mono.firstWithSignal(messageLoop, tokenExpiry, slowConsumer)
				.doFinally(ignored -> roomSessionRegistry.leave(threadId, connectionId, userId)
						.ifPresent(generation -> collabMessageDispatcher.closeGeneration(threadId, generation)));
	}

	private Mono<WsFrame> handleInbound(WebSocketMessage message, UUID threadId, UUID userId,
			UUID roomGeneration) {
		String traceId = newTraceId();
		String payload = textPayload(message);
		if (payload == null) {
			return Mono.just(malformed(threadId, traceId));
		}

		InboundMessage inbound;
		try {
			inbound = objectMapper.readValue(payload, InboundMessage.class);
		} catch (Exception error) {
			log.debug("Malformed WebSocket frame threadId={} traceId={}", threadId, traceId, error);
			return Mono.just(malformed(threadId, traceId));
		}
		if (SERVER_ONLY_TYPES.contains(inbound.type())) {
			return Mono.empty();
		}
		if (!"chat.message".equals(inbound.type()) || inbound.content() == null || inbound.content().isBlank()) {
			return Mono.just(malformed(threadId, traceId));
		}

		ChatMessageCommand command = new ChatMessageCommand(threadId, userId, inbound.content(), traceId);
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
			return new SessionInfo(jwt.getSubject(), jwt.getExpiresAt());
		}
		return new SessionInfo(principal.getName(), null);
	}

	private static String newTraceId() {
		return UUID.randomUUID().toString();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record InboundMessage(String type, String content) {
	}

	private record SessionInfo(String subject, Instant tokenExpiresAt) {
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
