package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.openai.errors.OpenAIServiceException;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : CollabMessageDispatcher.java
 * Description : 협업방 원문 방송과 {@code @AI} FIFO 실행을 같은 room generation 단위로 직렬화한다.
 */
@Component
public class CollabMessageDispatcher {

	private static final Logger log = LoggerFactory.getLogger(CollabMessageDispatcher.class);

	private final RoomSessionRegistry roomSessionRegistry;

	private final LlmChatStreamService llmChatStreamService;

	private final MsgPersistenceService msgPersistenceService;

	private final String modelId;

	private final Duration turnTimeout;

	private final int maxPendingPerRoom;

	private final int maxContextMessages;

	private final Scheduler deadlineScheduler;

	private final ConcurrentMap<RoomKey, RoomAiState> states = new ConcurrentHashMap<>();

	@Autowired
	public CollabMessageDispatcher(RoomSessionRegistry roomSessionRegistry,
			LlmChatStreamService llmChatStreamService,
			MsgPersistenceService msgPersistenceService,
			@Value("${app.collab.ai.model:${spring.ai.openai.chat.options.model}}") String modelId,
			@Value("${app.collab.ai.turn-timeout:120s}") Duration turnTimeout,
			@Value("${app.collab.ai.max-pending-per-room:20}") int maxPendingPerRoom,
			@Value("${app.collab.ai.max-context-messages:20}") int maxContextMessages) {
		this(roomSessionRegistry, llmChatStreamService, msgPersistenceService, modelId, turnTimeout,
				maxPendingPerRoom, maxContextMessages, Schedulers.parallel());
	}

	CollabMessageDispatcher(RoomSessionRegistry roomSessionRegistry, LlmChatStreamService llmChatStreamService,
			MsgPersistenceService msgPersistenceService, String modelId, Duration turnTimeout,
			int maxPendingPerRoom, int maxContextMessages, Scheduler deadlineScheduler) {
		if (modelId == null || modelId.isBlank()) {
			throw new IllegalArgumentException("app.collab.ai.model must not be blank");
		}
		if (turnTimeout == null || turnTimeout.isZero() || turnTimeout.isNegative()) {
			throw new IllegalArgumentException("app.collab.ai.turn-timeout must be positive");
		}
		if (maxPendingPerRoom < 0) {
			throw new IllegalArgumentException("app.collab.ai.max-pending-per-room must not be negative");
		}
		if (maxContextMessages < 0) {
			throw new IllegalArgumentException("app.collab.ai.max-context-messages must not be negative");
		}
		this.roomSessionRegistry = roomSessionRegistry;
		this.llmChatStreamService = llmChatStreamService;
		this.msgPersistenceService = msgPersistenceService;
		this.modelId = modelId;
		this.turnTimeout = turnTimeout;
		this.maxPendingPerRoom = maxPendingPerRoom;
		this.maxContextMessages = maxContextMessages;
		this.deadlineScheduler = deadlineScheduler;
	}

	/** 원문을 먼저 방송하고, {@code @AI} 발화만 현재 방 세대의 FIFO에 등록한다. */
	public Optional<ErrorFrame> dispatch(ChatMessageCommand command, UUID roomGeneration) {
		RoomKey key = new RoomKey(command.threadId(), roomGeneration);

		while (true) {
			RoomAiState state = states.computeIfAbsent(key, ignored -> new RoomAiState());
			ActiveTurn turnToStart = null;
			onStateFetchedForTesting();

			synchronized (state) {
				if (state.closed) {
					// computeIfAbsent와 락 사이에 다른 스레드의 sink 고장 처리가 이 상태를 막 닫고
					// map에서 지웠다. stale generation이 아니라 방금 지나간 종료이므로 조용히
					// 버리지 않고 map에서 현재(또는 새로 생긴) 상태를 다시 얻어 이어간다.
					continue;
				}
				try {
					if (!roomSessionRegistry.broadcastIfCurrent(command.threadId(), roomGeneration,
							new ChatMessageFrame(command.threadId(), command.fromSubject(),
									command.fromDisplayName(), command.content()))) {
						closeGeneration(key, state);
						return Optional.empty();
					}
				} catch (RuntimeException ignored) {
					closeGeneration(key, state, true);
					return Optional.of(messageDeliveryFailed(command));
				}

				AiMentionParser.MentionResult mention = AiMentionParser.parse(command.content());
				if (!mention.mentioned()) {
					persistHumanMessageAsync(command);
					return Optional.empty();
				}
				if (mention.prompt().isBlank()) {
					persistHumanMessageAsync(command);
					return Optional.of(new ErrorFrame(command.threadId(), "MALFORMED_REQUEST",
							"@AI 뒤에 요청 내용을 입력해 주세요.", command.traceId()));
				}

				PendingTurn pendingTurn = new PendingTurn(command.threadId(), roomGeneration, mention.prompt(),
						command.traceId(), persistHumanMessageAndFetchContextAsync(command));
				if (state.active != null) {
					if (state.pending.size() >= maxPendingPerRoom) {
						return Optional.of(new ErrorFrame(command.threadId(), "RATE_LIMITED",
								"이 방의 AI 요청 대기열이 가득 찼습니다.", command.traceId()));
					}
					state.pending.addLast(pendingTurn);
					return Optional.empty();
				}

				turnToStart = new ActiveTurn(pendingTurn);
				state.active = turnToStart;
			}

			startTurn(key, state, turnToStart);
			return Optional.empty();
		}
	}

	/** 마지막 연결이 퇴장한 generation의 활성·대기 AI 작업을 즉시 취소한다. */
	public void closeGeneration(UUID threadId, UUID roomGeneration) {
		RoomKey key = new RoomKey(threadId, roomGeneration);
		RoomAiState state = states.get(key);
		if (state != null) {
			closeGeneration(key, state);
		}
	}

	@PreDestroy
	void closeAllGenerations() {
		states.forEach(this::closeGeneration);
	}

	/**
	 * dispatch()가 상태를 얻은 직후, 락을 잡기 전에 개입할 수 있는 테스트 전용 훅이다. 운영 경로는
	 * 아무 것도 하지 않는다 — computeIfAbsent와 synchronized 사이의 경합 창을 결정론적으로
	 * 재현하기 위해서만 테스트 하위 클래스가 재정의한다.
	 */
	void onStateFetchedForTesting() {
	}

	/**
	* PENDING msg 생성은 LLM 스트림 시작을 지연시키지 않도록 별도로 fire-and-forget 구독한다
	* (activeTurn.pendingMsgId에 캐시된 Mono로 보관 — 완료/실패 저장 시점에 그 결과를 기다린다).
	* 문맥(turn.context())은 반대로 LLM 호출 자체의 입력이라 결과를 기다려야 한다 — 조회가 끝나야
	* 무엇을 보낼지 정해지므로, 여기서만 스트림 시작이 그만큼 지연된다(이슈 #100).
	* 델타는 개별 저장하지 않고 buffer에 누적해 턴이 끝났을 때 한 번에 완료 처리한다
	* (PersistingChatStreamService와 동일한 결).
	*/
	private void startTurn(RoomKey key, RoomAiState state, ActiveTurn activeTurn) {
		StringBuilder buffer = new StringBuilder();
		activeTurn.pendingMsgId.subscribe();

		Disposable subscription = activeTurn.turn.context()
				.flatMapMany(context -> withTotalDeadline(Flux.defer(() -> llmChatStreamService.streamChat(
						new ChatStreamRequest(activeTurn.turn.threadId(), modelId,
								buildPromptMessages(context, activeTurn.turn.prompt()))))))
				.filter(delta -> !delta.isEmpty())
				.doOnNext(delta -> {
					buffer.append(delta);
					broadcastDelta(activeTurn, delta);
				})
				.concatWith(Flux.defer(() -> activeTurn.hasNonBlankOutput.get()
						? Flux.empty()
						: Flux.error(new EmptyLlmOutputException())))
				.subscribe(ignored -> {
				}, error -> handleTurnError(key, state, activeTurn, buffer.toString(), error),
						() -> handleTurnComplete(key, state, activeTurn, buffer.toString()));

		synchronized (state) {
			if (!state.closed && state.active == activeTurn) {
				activeTurn.subscription.update(subscription);
			} else {
				subscription.dispose();
			}
		}
	}

	/** 저장된 이력의 HUMAN/AGENT를 user/assistant로 매핑하고, 이번 멘션의 발화를 마지막에 붙인다. */
	private static List<ChatMessage> buildPromptMessages(List<Msg> context, String prompt) {
		List<ChatMessage> messages = new ArrayList<>(context.size() + 1);
		for (Msg msg : context) {
			String role = msg.getAthKind() == AthKind.HUMAN ? "user"
					: msg.getAthKind() == AthKind.SYSTEM ? "system" : "assistant";
			messages.add(new ChatMessage(role, msg.getContent()));
		}
		messages.add(new ChatMessage("user", prompt));
		return messages;
	}

	private Flux<String> withTotalDeadline(Flux<String> source) {
		return Flux.defer(() -> {
			AtomicBoolean sourceCompleted = new AtomicBoolean();
			return source.doOnComplete(() -> sourceCompleted.set(true))
					.take(turnTimeout, deadlineScheduler)
					.concatWith(Flux.defer(() -> sourceCompleted.get()
							? Flux.empty()
							: Flux.error(new TurnTimeoutException())));
		});
	}

	private void broadcastDelta(ActiveTurn activeTurn, String delta) {
		if (delta.isBlank() && !activeTurn.hasNonBlankOutput.get()) {
			activeTurn.leadingWhitespace.addLast(delta);
			return;
		}
		if (!activeTurn.hasNonBlankOutput.getAndSet(true)) {
			while (!activeTurn.leadingWhitespace.isEmpty()) {
				broadcastStreamingFrame(activeTurn, activeTurn.leadingWhitespace.removeFirst());
			}
		}
		broadcastStreamingFrame(activeTurn, delta);
	}

	private void broadcastStreamingFrame(ActiveTurn activeTurn, String delta) {
		try {
			if (!roomSessionRegistry.broadcastIfCurrent(activeTurn.turn.threadId(), activeTurn.turn.roomGeneration(),
					new ChatAnswerFrame(activeTurn.turn.threadId(), delta, List.of(), false,
							ChatAnswerStatus.STREAMING))) {
				throw new StaleGenerationException();
			}
		} catch (StaleGenerationException error) {
			throw error;
		} catch (RuntimeException error) {
			throw new RoomBroadcastFailureException(error);
		}
	}

	/**
	* DONE 프레임 방송이 실패해도 응답 생성 자체는 이미 끝난 뒤라, closeGeneration()의 CANCELLED
	* 처리보다 먼저 COMPLETE로 저장한다 — 그러지 않으면 스트리밍으로 이미 전달된 답변이 DB에는
	* 빈 CANCELLED로 남아 새로고침 후 사라져 보인다. persistAgentCompletion()의 CAS 가드가 이후
	* closeGeneration()의 CANCELLED 시도를 무시하게 만든다.
	*/
	private void handleTurnComplete(RoomKey key, RoomAiState state, ActiveTurn activeTurn, String content) {
		try {
			if (!roomSessionRegistry.broadcastIfCurrent(activeTurn.turn.threadId(), activeTurn.turn.roomGeneration(),
					new ChatAnswerFrame(activeTurn.turn.threadId(), "", List.of(), false, ChatAnswerStatus.DONE))) {
				persistAgentCompletion(activeTurn, content);
				closeGeneration(key, state);
				return;
			}
		} catch (RuntimeException ignored) {
			persistAgentCompletion(activeTurn, content);
			closeGeneration(key, state, true);
			return;
		}
		persistAgentCompletion(activeTurn, content);
		advance(key, state, activeTurn);
	}

	private void handleTurnError(RoomKey key, RoomAiState state, ActiveTurn activeTurn, String content,
			Throwable error) {
		if (error instanceof StaleGenerationException) {
			closeGeneration(key, state);
			return;
		}
		if (error instanceof RoomBroadcastFailureException) {
			closeGeneration(key, state, true);
			return;
		}

		String code = error instanceof OpenAIServiceException || error instanceof TurnTimeoutException
				|| error instanceof EmptyLlmOutputException ? "MODEL_UNAVAILABLE" : "INTERNAL_ERROR";
		String message = "MODEL_UNAVAILABLE".equals(code) ? "모델을 호출할 수 없습니다." : "AI 응답 처리 중 오류가 발생했습니다.";
		try {
			if (!roomSessionRegistry.broadcastIfCurrent(activeTurn.turn.threadId(), activeTurn.turn.roomGeneration(),
					new ErrorFrame(activeTurn.turn.threadId(), code, message, activeTurn.turn.traceId()))) {
				closeGeneration(key, state);
				return;
			}
		} catch (RuntimeException ignored) {
			closeGeneration(key, state, true);
			return;
		}
		persistAgentFailure(activeTurn, MsgStatus.FAILED);
		advance(key, state, activeTurn);
	}

	private void advance(RoomKey key, RoomAiState state, ActiveTurn finishedTurn) {
		ActiveTurn nextTurn = null;
		synchronized (state) {
			if (state.closed || state.active != finishedTurn) {
				return;
			}
			PendingTurn next = state.pending.pollFirst();
			if (next == null) {
				state.active = null;
				return;
			}
			nextTurn = new ActiveTurn(next);
			state.active = nextTurn;
		}
		startTurn(key, state, nextTurn);
	}

	private void closeGeneration(RoomKey key, RoomAiState state) {
		closeGeneration(key, state, false);
	}

	private void closeGeneration(RoomKey key, RoomAiState state, boolean notifyPendingCancellation) {
		ActiveTurn activeTurn;
		boolean pendingTurnsCancelled;
		synchronized (state) {
			if (state.closed) {
				return;
			}
			state.closed = true;
			states.remove(key, state);
			pendingTurnsCancelled = !state.pending.isEmpty();
			state.pending.clear();
			activeTurn = state.active;
			state.active = null;
		}
		if (activeTurn != null) {
			activeTurn.subscription.dispose();
			persistAgentFailure(activeTurn, MsgStatus.CANCELLED);
		}
		if (notifyPendingCancellation && pendingTurnsCancelled) {
			try {
				roomSessionRegistry.broadcastIfCurrent(key.threadId(), key.roomGeneration(),
						new ErrorFrame(key.threadId(), "MESSAGE_DELIVERY_FAILED",
								"대기 중인 AI 요청이 취소되었습니다.", UUID.randomUUID().toString()));
			} catch (RuntimeException ignored) {
				// The notification uses the failed broadcast path once and is intentionally not retried.
			}
		}
	}

	private static ErrorFrame messageDeliveryFailed(ChatMessageCommand command) {
		return new ErrorFrame(command.threadId(), "MESSAGE_DELIVERY_FAILED", "메시지를 전달하지 못했습니다.",
				command.traceId());
	}

	/**
	* 방송이 이미 끝난 뒤 fire-and-forget으로 저장한다 — Mono.fromCallable(...).subscribeOn(...)은
	* 스케줄만 하고 즉시 반환되므로, 이 메서드를 synchronized(state) 블록 안에서 불러도 락을
	* 블로킹하지 않는다. 저장 실패는 로그만 남기고 삼킨다(채팅 자체를 막지 않는다).
	*/
	private void persistHumanMessageAsync(ChatMessageCommand command) {
		Mono.fromCallable(() -> msgPersistenceService.persistHumanMessageBlocking(command.threadId(),
					command.from(), command.content()))
				.subscribeOn(Schedulers.boundedElastic())
				.doOnError(e -> log.error("HUMAN 메시지 저장 실패 threadId={} traceId={}", command.threadId(),
						command.traceId(), e))
				.onErrorComplete()
				.subscribe();
	}

	/**
	* {@code @AI} 멘션 발화는 문맥 조회 결과가 LLM 호출의 입력이 되므로, 결과를 캐시해 startTurn()이
	* 기다렸다가 쓸 수 있게 한다(persistHumanMessageAsync와 달리 fire-and-forget이 아니다). 조회+저장
	* 순서는 MsgPersistenceService 쪽에서 한 트랜잭션으로 보장한다 — 이 발화 자신이 문맥에 중복으로
	* 끼지 않도록.
	*/
	private Mono<List<Msg>> persistHumanMessageAndFetchContextAsync(ChatMessageCommand command) {
		Mono<List<Msg>> context = Mono
				.fromCallable(() -> msgPersistenceService.persistHumanMessageAndFetchContextBlocking(
						command.threadId(), command.from(), command.content(), maxContextMessages))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(e -> {
					log.error("문맥 조회 및 HUMAN 메시지 저장 실패 threadId={} traceId={}", command.threadId(),
							command.traceId(), e);
					return Mono.just(List.<Msg>of());
				})
				.cache();
		context.subscribe();
		return context;
	}

	/**
	* PENDING 생성이 실패해 pendingMsgId가 비어있으면(empty) 완료 저장도 조용히 건너뛴다.
	* terminalPersisted CAS로 같은 턴에 대해 완료/실패 저장이 두 번 이상 시도되는 것을 막는다 —
	* completed_at·status 전이는 한 번만 유효하고, DB 트리거도 두 번째 UPDATE를 거부한다.
	*/
	private void persistAgentCompletion(ActiveTurn activeTurn, String content) {
		if (!activeTurn.terminalPersisted.compareAndSet(false, true)) {
			return;
		}
		activeTurn.pendingMsgId
				.flatMap(msgId -> Mono.fromRunnable(() -> msgPersistenceService.completeBlocking(msgId, content))
						.subscribeOn(Schedulers.boundedElastic())
						.doOnError(e -> log.error("agent 메시지 완료 저장 실패 msgId={}", msgId, e)))
				.onErrorComplete()
				.subscribe();
	}

	private void persistAgentFailure(ActiveTurn activeTurn, MsgStatus terminalStatus) {
		if (!activeTurn.terminalPersisted.compareAndSet(false, true)) {
			return;
		}
		activeTurn.pendingMsgId
				.flatMap(msgId -> Mono.fromRunnable(() -> msgPersistenceService.failBlocking(msgId, terminalStatus))
						.subscribeOn(Schedulers.boundedElastic())
						.doOnError(e -> log.error("agent 메시지 실패 저장 실패 msgId={}", msgId, e)))
				.onErrorComplete()
				.subscribe();
	}

	private record RoomKey(UUID threadId, UUID roomGeneration) {
	}

	private record PendingTurn(UUID threadId, UUID roomGeneration, String prompt, String traceId,
			Mono<List<Msg>> context) {
	}

	private final class ActiveTurn {

		private final PendingTurn turn;

		private final Disposable.Swap subscription = Disposables.swap();

		private final AtomicBoolean hasNonBlankOutput = new AtomicBoolean();

		private final Deque<String> leadingWhitespace = new ArrayDeque<>();

		/**
		* PENDING 행 생성 결과(성공 시 msgId, 실패 시 empty)를 캐시해 여러 번 구독해도 한 번만
		* 실행한다 — startTurn()이 즉시 fire-and-forget으로 시작하고, 완료/실패 저장은 이 Mono가
		* 끝날 때까지 기다렸다가 msgId를 얻는다.
		*/
		private final Mono<UUID> pendingMsgId;

		/** 완료/실패 저장이 이 턴에 대해 이미 한 번 시도됐는지 — 두 번째 시도는 조용히 건너뛴다. */
		private final AtomicBoolean terminalPersisted = new AtomicBoolean();

		ActiveTurn(PendingTurn turn) {
			this.turn = turn;
			this.pendingMsgId = Mono.fromCallable(() -> msgPersistenceService.createPendingAgentMessageBlocking(
						turn.threadId()))
					.subscribeOn(Schedulers.boundedElastic())
					.map(Msg::getId)
					.onErrorResume(e -> {
						log.error("PENDING agent 메시지 생성 실패 threadId={}", turn.threadId(), e);
						return Mono.empty();
					})
					.cache();
		}
	}

	private static final class RoomAiState {

		private final Deque<PendingTurn> pending = new ArrayDeque<>();

		private ActiveTurn active;

		private boolean closed;
	}

	private static final class TurnTimeoutException extends RuntimeException {
	}

	private static final class EmptyLlmOutputException extends RuntimeException {
	}

	private static final class StaleGenerationException extends RuntimeException {
	}

	private static final class RoomBroadcastFailureException extends RuntimeException {

		RoomBroadcastFailureException(Throwable cause) {
			super(cause);
		}
	}

}
