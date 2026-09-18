package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.AthKind;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.openai.errors.OpenAIServiceException;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadMessageDispatcher.java
 * Description : 협업방 원문 방송과 {@code @AI} FIFO 실행을 같은 room generation 단위로 직렬화한다.
 */
@Component
public class ThreadMessageDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ThreadMessageDispatcher.class);

	private final RoomSessionRegistry roomSessionRegistry;

	private final LlmChatStreamService llmChatStreamService;

	private final MsgPersistenceService msgPersistenceService;

	private final String modelId;

	private final Duration turnTimeout;

	private final int maxPendingPerRoom;

	private final int maxContextMessages;

	private final Scheduler deadlineScheduler;

	/**
	* 방 하나가 워커 앞에 쌓아둘 수 있는 메시지 수. 초과하면 그 자리에서 RATE_LIMITED로 거절한다
	* — 워커가 막혔을 때 메모리가 무한히 늘지 않게 하는 백프레셔다. AI 대기열 한도
	* (app.thread.ai.max-pending-per-room)와는 다른 층이다: 이쪽은 "방송을 기다리는 메시지",
	* 저쪽은 "LLM 실행을 기다리는 턴"이다.
	*/
	private static final int MAX_QUEUED_MESSAGES_PER_ROOM = 100;

	/**
	* 한 번에 예약하는 seq 개수(이슈 #190). 클수록 DB를 덜 부르지만 방이 닫힐 때 버려지는 구멍이
	* 커진다. 구멍은 따라잡기 정의상 무해하므로(빠진 번호를 기다리지 않는다) 왕복을 줄이는 쪽에
	* 무게를 둔다.
	*/
	private static final int SEQ_BLOCK_SIZE = 100;

	private final ConcurrentMap<RoomKey, RoomAiState> states = new ConcurrentHashMap<>();

	@Autowired
	public ThreadMessageDispatcher(RoomSessionRegistry roomSessionRegistry,
			LlmChatStreamService llmChatStreamService,
			MsgPersistenceService msgPersistenceService,
			@Value("${app.thread.ai.model:${spring.ai.openai.chat.options.model}}") String modelId,
			@Value("${app.thread.ai.turn-timeout:120s}") Duration turnTimeout,
			@Value("${app.thread.ai.max-pending-per-room:20}") int maxPendingPerRoom,
			@Value("${app.thread.ai.max-context-messages:20}") int maxContextMessages) {
		this(roomSessionRegistry, llmChatStreamService, msgPersistenceService, modelId, turnTimeout,
				maxPendingPerRoom, maxContextMessages, Schedulers.parallel());
	}

	ThreadMessageDispatcher(RoomSessionRegistry roomSessionRegistry, LlmChatStreamService llmChatStreamService,
			MsgPersistenceService msgPersistenceService, String modelId, Duration turnTimeout,
			int maxPendingPerRoom, int maxContextMessages, Scheduler deadlineScheduler) {
		if (modelId == null || modelId.isBlank()) {
			throw new IllegalArgumentException("app.thread.ai.model must not be blank");
		}
		if (turnTimeout == null || turnTimeout.isZero() || turnTimeout.isNegative()) {
			throw new IllegalArgumentException("app.thread.ai.turn-timeout must be positive");
		}
		if (maxPendingPerRoom < 0) {
			throw new IllegalArgumentException("app.thread.ai.max-pending-per-room must not be negative");
		}
		if (maxContextMessages < 0) {
			throw new IllegalArgumentException("app.thread.ai.max-context-messages must not be negative");
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

	/**
	* 메시지를 방 워커 큐에 넣는다. 방송·AI 턴 등록은 워커가 방 단위로 순서대로 처리한다(이슈 #190).
	*
	* 여기서 동기로 돌려주는 오류는 **큐에 넣기 전에 판정이 끝나는 것**뿐이다.
	* - MALFORMED_REQUEST: 파싱만으로 안다. 원문 방송은 그대로 하되 AI 턴은 만들지 않는다.
	* - RATE_LIMITED: 큐가 가득 찼다는 사실은 offer 시점에 정확하다.
	*
	* 반대로 방송 성공 여부와 AI 대기열 한도는 워커가 실행해봐야 알 수 있어, 동기로 흉내 내면
	* 근사치가 된다("됐다고 했는데 실제로는 거절"). 그쪽은 워커가 비동기로 알린다.
	*/
	public Optional<ErrorFrame> dispatch(ChatMessageCommand command, UUID roomGeneration) {
		ErrorFrame malformed;
		String prompt;
		if (command.kind() == ThrKind.DIRECT) {
			// DIRECT는 멘션 여부와 무관하게 모든 정상 발화를 AI에 전달한다 — 파서를 아예 타지 않는다.
			malformed = null;
			prompt = command.content();
		} else {
			AiMentionParser.MentionResult mention = AiMentionParser.parse(command.content());
			malformed = mention.mentioned() && mention.prompt().isBlank()
					? new ErrorFrame(command.threadId(), "MALFORMED_REQUEST",
							"@AI 뒤에 요청 내용을 입력해 주세요.", command.traceId())
					: null;
			// prompt가 null이면 워커는 방송·저장만 하고 AI 턴을 만들지 않는다 — 일반 발화이거나 빈 프롬프트다.
			prompt = malformed == null && mention.mentioned() ? mention.prompt() : null;
		}

		RoomKey key = new RoomKey(command.threadId(), roomGeneration);
		while (true) {
			RoomAiState state = states.computeIfAbsent(key, k -> new RoomAiState(k, command.kind()));

			synchronized (state) {
				if (state.closed) {
					// computeIfAbsent와 락 사이에 다른 스레드가 이 상태를 막 닫고 map에서 지웠다.
					// stale generation이 아니라 방금 지나간 종료이므로, map에서 현재(또는 새로 생긴)
					// 상태를 다시 얻어 이어간다.
					continue;
				}
				// 워커가 꺼내기 전에 도착한 취소도 받을 수 있게 큐에 넣는 순간 등록한다(cancel 주석).
				// 인바운드는 연결마다 순서대로 처리되므로, 같은 연결의 chat.cancel은 이 등록 뒤에 온다.
				QueuedMessage queued = new QueuedMessage(command, prompt, new AtomicBoolean());
				TurnRef ref = prompt == null ? null : TurnRef.of(command.turnId(), command.connectionId());
				if (ref != null) {
					state.inFlight.put(ref, new InFlightTurn(queued, false));
				}
				if (state.inbox.tryEmitNext(queued).isFailure()) {
					if (ref != null) {
						state.inFlight.remove(ref);
					}
					if (command.kind() == ThrKind.DIRECT && command.reservedTurn() != null) {
						persistDirectReservedAgentTerminal(command.reservedTurn(), queued.terminalPersisted(),
								MsgStatus.CANCELLED);
					}
					return Optional.of(new ErrorFrame(command.threadId(), "RATE_LIMITED",
							"이 방의 메시지 대기열이 가득 찼습니다.", command.traceId()));
				}
				if (prompt != null) {
					state.unprocessedAiTurns += 1;
				}
			}
			return Optional.ofNullable(malformed);
		}
	}

	/**
	* 워커가 큐에서 하나씩 꺼내 실행한다 — 방 단위로 직렬이므로 방송 순서가 여기서 확정된다.
	*
	* 방송이 실패하면(sink 고장) generation을 닫고 끝낸다. 이전에는 MESSAGE_DELIVERY_FAILED를
	* 호출자에게 동기로 돌려줬는데, 워커에는 그 사람의 연결로 가는 통로가 없다 — 방 sink는 방금
	* 고장난 그 sink다. closeGeneration(.., true)이 남은 대기 턴 취소를 한 번 알리는 것으로 갈음한다.
	*/
	private void process(RoomKey key, RoomAiState state, QueuedMessage queued) {
		ChatMessageCommand command = queued.command();
		boolean direct = command.kind() == ThrKind.DIRECT;
		ChatMessageCommand.ReservedTurn reserved = command.reservedTurn();
		// DIRECT는 bootstrap 또는 비동기 저장이 HUMAN msgId·seq를 이미 예약해뒀다(이슈 #162) —
		// 새로 만들면 이미 저장된 행과 어긋난다. COLLAB은 그대로 방송 시점에 새로 확정한다(이슈 #190).
		UUID msgId = direct ? reserved.humanMsgId() : UUID.randomUUID();
		long seq = direct ? reserved.humanSeq() : state.seqBlock.allocate();
		try {
			if (!roomSessionRegistry.broadcastIfCurrent(key.threadId(), key.roomGeneration(),
					new ChatMessageFrame(command.threadId(), msgId, command.clientMsgId(), command.turnId(), seq,
							command.fromSubject(), command.fromDisplayName(), command.content()))) {
				closeGeneration(key, state);
				return;
			}
		} catch (RuntimeException ignored) {
			closeGeneration(key, state, true);
			return;
		}

		if (queued.prompt() == null) {
			// DIRECT는 dispatch()가 항상 prompt를 채우므로 이 분기는 COLLAB 전용이다.
			persistHumanMessageAsync(msgId, seq, command);
			return;
		}

		Mono<List<Msg>> context = direct
				// HUMAN은 이미 DirectChatTurnService가 저장했다 — 문맥만 읽는다(중복 저장 방지).
				? fetchContextOnlyBlocking(command.threadId())
				: persistHumanMessageAndFetchContext(msgId, seq, command);
		PendingTurn pendingTurn = new PendingTurn(command.threadId(), key.roomGeneration(), queued.prompt(),
				command.traceId(), TurnRef.of(command.turnId(), command.connectionId()), command.model(), context,
				direct ? reserved : null, queued.terminalPersisted());
		ActiveTurn turnToStart = null;
		boolean admitted = false;
		// 등록 해제와 대기열 추가를 한 락 안에서 한다 — 사이가 벌어지면 그 틈에 온 취소가 어디서도
		// 턴을 못 찾는다. 대기·취소 통지도 락 안에서 보낸다: 락 밖이면 앞 턴이 막 끝나 이 턴이 먼저
		// 시작되고, 답변 프레임 뒤에 "대기 중"이 도착할 수 있다.
		synchronized (state) {
			// 여기까지 오면 더 이상 "워커가 꼺지 않은" 발화가 아니다 — 아래 어느 가지로 가든
			// 이 발화의 처리는 여기서 끝난다.
			state.unprocessedAiTurns -= 1;
			InFlightTurn inFlight = pendingTurn.ref() == null ? null : state.inFlight.remove(pendingTurn.ref());
			boolean cancelledBeforeQueue = inFlight != null && inFlight.cancelled();
			if (state.closed) {
				// 저장만 하고 끝낸다 — DIRECT는 예약해둔 PENDING AGENT가 영영 PENDING으로 남지
				// 않도록 CANCELLED로 정리한다.
				if (direct) {
					finishDirectReservedAgent(key, pendingTurn, MsgStatus.CANCELLED, ChatAnswerStatus.CANCELLED);
				}
			} else if (cancelledBeforeQueue) {
				if (direct) {
					finishDirectReservedAgent(key, pendingTurn, MsgStatus.CANCELLED, ChatAnswerStatus.CANCELLED);
				} else {
					broadcastQuietly(key, queuedFrame(pendingTurn, ChatQueuedStatus.CANCELLED));
				}
			} else if (state.active == null) {
				turnToStart = new ActiveTurn(pendingTurn, state.seqBlock);
				state.active = turnToStart;
				admitted = true;
			} else if (state.pending.size() >= maxPendingPerRoom) {
				broadcastQuietly(key, new ErrorFrame(key.threadId(), "RATE_LIMITED",
						"이 방의 AI 요청 대기열이 가득 찼습니다.", command.traceId()));
				if (direct) {
					finishDirectReservedAgent(key, pendingTurn, MsgStatus.DENIED, ChatAnswerStatus.DENIED);
				}
			} else {
				state.pending.addLast(pendingTurn);
				broadcastQuietly(key, queuedFrame(pendingTurn, ChatQueuedStatus.QUEUED));
				admitted = true;
			}
		}

		if (!admitted) {
			if (!direct) {
				// 턴은 없어도 발화는 이미 방송됐으니 이력에 남긴다. DIRECT는 이미 저장돼 있어 불필요하다.
				persistHumanMessageAsync(msgId, seq, command);
			}
			return;
		}
		// 문맥 조회는 사람 메시지 저장을 겸하므로 턴이 대기 중이어도 지금 시작한다(이슈 #100).
		pendingTurn.context().subscribe();
		if (turnToStart != null) {
			startTurn(key, state, turnToStart);
		}
	}

	/** DIRECT 전용 — HUMAN이 이미 저장돼 있으므로 최근 COMPLETE 문맥만 읽는다(이슈 #162). */
	private Mono<List<Msg>> fetchContextOnlyBlocking(UUID threadId) {
		return Mono
				.fromCallable(() -> msgPersistenceService.recentCompleteContextBlocking(threadId, maxContextMessages))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(e -> {
					log.error("DIRECT 문맥 조회 실패 threadId={}", threadId, e);
					return Mono.just(List.<Msg>of());
				})
				.cache();
	}

	/**
	* DIRECT의 예약된 PENDING AGENT를 CANCELLED·DENIED로 닫고 같은 상태의 `chat.answer`를 방
	* 전체에 방송한다(이슈 #162) — 큐 진입 전 취소·FIFO 초과·generation 조기 종료가 모두 이 경로를
	* 쓴다. CANCELLED는 사용자 취소 의미라 cancelBlocking(빈 본문)을, DENIED는 failBlocking을 쓴다.
	*/
	private void finishDirectReservedAgent(RoomKey key, PendingTurn turn, MsgStatus terminalStatus,
			ChatAnswerStatus answerStatus) {
		ChatMessageCommand.ReservedTurn reserved = turn.reservedTurn();
		persistDirectReservedAgentTerminal(reserved, turn.terminalPersisted(), terminalStatus);
		broadcastQuietly(key, new ChatAnswerFrame(key.threadId(), reserved.agentMsgId(), turn.turnId(),
				modelIdFor(turn), reserved.agentSeq(), "", List.of(), false, answerStatus));
	}

	/** 예약된 DIRECT AGENT의 terminal 전이는 큐·대기·활성 어느 경로에서도 한 번만 실행한다. */
	private void persistDirectReservedAgentTerminal(ChatMessageCommand.ReservedTurn reserved,
			AtomicBoolean terminalPersisted, MsgStatus terminalStatus) {
		if (!terminalPersisted.compareAndSet(false, true)) {
			return;
		}
		Mono.fromRunnable(() -> {
					if (terminalStatus == MsgStatus.CANCELLED) {
						msgPersistenceService.cancelBlocking(reserved.agentMsgId(), "");
					} else {
						msgPersistenceService.failBlocking(reserved.agentMsgId(), terminalStatus);
					}
				})
				.subscribeOn(Schedulers.boundedElastic())
				.doOnError(e -> log.error("DIRECT 예약 AGENT 종료 저장 실패 msgId={}", reserved.agentMsgId(), e))
				.onErrorComplete()
				.subscribe();
	}

	private static ChatQueuedFrame queuedFrame(PendingTurn turn, ChatQueuedStatus status) {
		return new ChatQueuedFrame(turn.threadId(), turn.turnId(), status);
	}

	/** 방송 실패가 이 경로를 더 망가뜨리지 않게 삼킨다 — 이미 통지 성격의 호출이다. */
	private void broadcastQuietly(RoomKey key, WsFrame frame) {
		try {
			roomSessionRegistry.broadcastIfCurrent(key.threadId(), key.roomGeneration(), frame);
		} catch (RuntimeException ignored) {
			// 통지는 한 번만 시도하고 재시도하지 않는다.
		}
	}

	/**
	* replay(이슈 #233)가 지금 이 방·세대에서 아직 살아있는 턴(진행 중이거나, FIFO 대기 중이거나,
	* 워커가 아직 꺼내지 않은)과 같은 AGENT 메시지를 가리키는지 확인한다. active만 보면 안 되는
	* 이유: DIRECT는 여러 탭이 같은 OWNER로 동시에 붙을 수 있고 턴이 큐잉되는 게 정상 동작이라
	* (cancel()의 pending·inFlight 처리와 같은 전제), 아직 시작 전인 정상 대기 턴을 여기서
	* "고아"로 잘못 판정하면 호출부가 그 msg를 FAILED로 닫고 중복 턴을 새로 시작한다 — 원래
	* 턴이 나중에 실제로 끝나도 msg_block_terminal_update 트리거가 그 저장을 막아 응답이
	* 조용히 유실된다.
	*
	* active면 지금까지 누적된 내용을, pending·inFlight면(아직 한 글자도 안 만들어졌다) 빈
	* 문자열을 돌려준다 — 셋 다 아니면 empty(호출부가 DB 상태로 마저 분기한다: 이미 끝났거나
	* 진짜 고아).
	*/
	Optional<String> activeTurnContentIfMatches(UUID threadId, UUID roomGeneration, UUID agentMsgId) {
		RoomAiState state = states.get(new RoomKey(threadId, roomGeneration));
		if (state == null) {
			return Optional.empty();
		}
		synchronized (state) {
			if (state.closed) {
				return Optional.empty();
			}
			if (state.active != null && agentMsgId.equals(state.active.msgId)) {
				return Optional.of(state.active.content.toString());
			}
			for (PendingTurn candidate : state.pending) {
				if (candidate.reservedTurn() != null && agentMsgId.equals(candidate.reservedTurn().agentMsgId())) {
					return Optional.of("");
				}
			}
			for (InFlightTurn inFlight : state.inFlight.values()) {
				ChatMessageCommand.ReservedTurn reserved = inFlight.queued().command().reservedTurn();
				if (reserved != null && agentMsgId.equals(reserved.agentMsgId())) {
					return Optional.of("");
				}
			}
			return Optional.empty();
		}
	}

	/** replay 응답 구성용(이슈 #233) — modelIdFor(PendingTurn)와 같은 기본값 규칙을 외부에 노출한다. */
	String resolveModelId(String requestedModel) {
		return requestedModel == null || requestedModel.isBlank() ? modelId : requestedModel;
	}

	/** 마지막 연결이 퇴장한 generation의 활성·대기 AI 작업을 즉시 취소한다. */
	public void closeGeneration(UUID threadId, UUID roomGeneration) {
		RoomKey key = new RoomKey(threadId, roomGeneration);
		RoomAiState state = states.get(key);
		if (state != null) {
			closeGeneration(key, state);
		}
	}

	/**
	* 턴 하나를 멈춘다(이슈 #160). COLLAB은 지목을 (turnId, 그 발화가 들어온 커넥션) 짝으로 한다.
	*
	* turnId는 클라이언트가 만든 값이라 그것만으로는 믿지 않는다 — "이 커넥션이 보낸 발화"의 턴에서만
	* 인정한다. 짝으로 찾으면 남이 부른 턴은 애초에 찾아지지 않는다 — 에코에서 남의 turnId를 읽어
	* 보내도 끊을 수 없고, 같은 값을 우연히 재사용해도 서로 간섭하지 않는다. 같은 사용자라도 다른
	* 탭(다른 커넥션)이 부른 턴은 멈출 수 없다. 그래서 FORBIDDEN이 따로 없고, 못 찾으면 조용히
	* 넘어간다(이미 끝난 턴의 취소와 같은 결과다).
	*
	* DIRECT는 turnId만으로 지목한다(이슈 #162) — 재연결·다른 탭의 같은 OWNER도 취소할 수 있어야
	* 하기 때문이다. 이 커넥션이 그 OWNER인지는 ThreadWebSocketHandler가 구독 인가에서 이미
	* 확인했으므로 여기서 다시 확인하지 않는다.
	*
	* 턴이 있을 수 있는 자리는 셋이다.
	* - 워커가 아직 꺼내지 않았다(inFlight): 표시만 남기고, 워커가 꺼낼 때 턴을 만들지 않는다.
	*   사람 발화 자체는 이미 받은 것이라 방송·저장한다. COLLAB은 chat.queued(cancelled)를,
	*   DIRECT는 예약 AGENT의 chat.answer(cancelled)를 보낸다.
	* - 기다리고 있다(pending): 큐에서 빼고 위와 같은 프레임을 보낸다.
	* - 실행 중이다(active): 구독을 끊고, 그때까지 생성된 내용을 CANCELLED로 저장한 뒤 답변
	*   프레임을 보낸다(COLLAB은 done, DIRECT는 cancelled). 화면은 이 프레임에서 스트림을 닫으므로
	*   없으면 중단한 답변이 계속 "생성 중"으로 남는다.
	*/
	public void cancel(UUID threadId, UUID roomGeneration, UUID turnId, UUID connectionId) {
		RoomKey key = new RoomKey(threadId, roomGeneration);
		RoomAiState state = states.get(key);
		if (turnId == null || state == null) {
			return;
		}
		boolean direct = state.kind == ThrKind.DIRECT;
		TurnRef ref = direct ? null : TurnRef.of(turnId, connectionId);
		if (!direct && ref == null) {
			return;
		}

		ActiveTurn activeTurn;
		synchronized (state) {
			if (state.closed) {
				return;
			}
			if (direct) {
				TurnRef inFlightRef = findInFlightByTurnId(state, turnId);
				if (inFlightRef != null) {
					state.inFlight.computeIfPresent(inFlightRef, (ignored, queued) -> queued.cancel());
					return;
				}
			} else if (state.inFlight.containsKey(ref)) {
				state.inFlight.computeIfPresent(ref, (ignored, queued) -> queued.cancel());
				return;
			}
			boolean activeMatches = direct
					? state.active != null && turnId.equals(state.active.turn.turnId())
					: state.active != null && ref.equals(state.active.turn.ref());
			if (!activeMatches) {
				for (PendingTurn candidate : state.pending) {
					boolean matches = direct ? turnId.equals(candidate.turnId()) : ref.equals(candidate.ref());
					if (matches) {
						state.pending.remove(candidate);
						if (direct) {
							finishDirectReservedAgent(key, candidate, MsgStatus.CANCELLED,
									ChatAnswerStatus.CANCELLED);
						} else {
							broadcastQuietly(key, queuedFrame(candidate, ChatQueuedStatus.CANCELLED));
						}
						return;
					}
				}
				return;
			}
			// state.active는 비우지 않는다 — advance()가 "끝난 턴이 아직 활성인가"로 경합을 거르므로,
			// 여기서 비우면 다음 대기 턴이 시작되지 않는다.
			activeTurn = state.active;
		}

		activeTurn.subscription.dispose();
		persistAgentCancellation(activeTurn);
		broadcastQuietly(key, new ChatAnswerFrame(threadId, activeTurn.msgId, activeTurn.turn.turnId(),
				modelIdFor(activeTurn.turn), activeTurn.seq, "", List.of(), false,
				direct ? ChatAnswerStatus.CANCELLED : ChatAnswerStatus.DONE));
		advance(key, state, activeTurn);
	}

	/** inFlight는 COLLAB처럼 (turnId, connectionId) 짝으로 키가 잡혀 있다 — DIRECT는 turnId만
	 * 훑어 다른 탭이 등록한 것도 찾는다(이슈 #162). */
	private static TurnRef findInFlightByTurnId(RoomAiState state, UUID turnId) {
		for (TurnRef ref : state.inFlight.keySet()) {
			if (ref.turnId().equals(turnId)) {
				return ref;
			}
		}
		return null;
	}

	@PreDestroy
	void closeAllGenerations() {
		states.forEach(this::closeGeneration);
	}

	/**
	* PENDING msg 생성은 LLM 스트림 시작을 지연시키지 않도록 별도로 fire-and-forget 구독한다
	* (activeTurn.pendingMsgId에 캐시된 Mono로 보관 — 완료/실패 저장 시점에 그 결과를 기다린다).
	* 문맥(turn.context())은 반대로 LLM 호출 자체의 입력이라 결과를 기다려야 한다 — 조회가 끝나야
	* 무엇을 보낼지 정해지므로, 여기서만 스트림 시작이 그만큼 지연된다(이슈 #100).
	* 델타는 개별 저장하지 않고 activeTurn.content에 누적해 턴이 끝났을 때 한 번에 완료 처리한다
	* (PersistingChatStreamService와 동일한 결).
	*
	* LLM 호출 직전에 abandoned()를 보는 이유(이슈 #205): context()는 .cache()된 Mono라, 아래
	* .subscribe()가 불릴 때 이미 완료돼 있으면 그 호출 안에서 flatMapMany까지 동기로 실행된다.
	* 그러면 아래 synchronized 블록의 닫힘 검사는 이미 나간 호출을 되돌리지 못한다 — dispose는 이후
	* 신호만 끊지, 벌어진 부작용을 취소하지는 못하기 때문이다.
	*
	* 비워 돌려줄 때 Flux.empty()가 아니라 never()인 것도 의도된 것이다. empty()면 아래 concatWith가
	* EmptyLlmOutputException을 만들어 handleTurnError로 가는데, 취소된 턴은 방이 살아 있어
	* MODEL_UNAVAILABLE 프레임이 실제로 방송된다 — 취소했는데 잠시 뒤 오류가 뜨는 화면이 된다.
	* never()는 아래 update()가 곧바로 dispose 한다(이미 dispose된 Swap은 새 값을 즉시 정리한다).
	*/
	private void startTurn(RoomKey key, RoomAiState state, ActiveTurn activeTurn) {
		activeTurn.pendingMsgId.subscribe();

		Disposable subscription = activeTurn.turn.context()
				.flatMapMany(context -> {
					if (abandoned(activeTurn)) {
						return Flux.<String>never();
					}
					return withTotalDeadline(Flux.defer(() -> llmChatStreamService.streamChat(
							new ChatStreamRequest(activeTurn.turn.threadId(), modelIdFor(activeTurn.turn),
									buildPromptMessages(context, activeTurn.turn.prompt())))));
				})
				.filter(delta -> !delta.isEmpty())
				.doOnNext(delta -> {
					activeTurn.content.append(delta);
					broadcastDelta(activeTurn, delta);
				})
				.concatWith(Flux.defer(() -> activeTurn.hasNonBlankOutput.get()
						? Flux.empty()
						: Flux.error(new EmptyLlmOutputException())))
				.subscribe(ignored -> {
				}, error -> handleTurnError(key, state, activeTurn, activeTurn.content.toString(), error),
						() -> handleTurnComplete(key, state, activeTurn, activeTurn.content.toString()));

		synchronized (state) {
			if (!state.closed && state.active == activeTurn) {
				activeTurn.subscription.update(subscription);
			} else {
				subscription.dispose();
			}
		}
	}

	/**
	* 이 턴이 이미 버려졌는지. 방 닫힘(closeGeneration)과 취소(cancel)가 공통으로 내리는 신호가
	* 이 Swap 하나라, 둘을 한 번에 본다. state.closed나 state.active로는 취소를 걸러낼 수 없다 —
	* cancel()은 다음 대기 턴이 시작되도록 state.active를 일부러 비우지 않기 때문이다.
	*/
	private static boolean abandoned(ActiveTurn activeTurn) {
		return activeTurn.subscription.isDisposed();
	}

	/** 발화가 모델을 지정하지 않았으면 서버 기본값(app.thread.ai.model)으로 돌아간다(이슈 #160). */
	private String modelIdFor(PendingTurn turn) {
		return resolveModelId(turn.model());
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
					new ChatAnswerFrame(activeTurn.turn.threadId(), activeTurn.msgId, activeTurn.turn.turnId(),
							modelIdFor(activeTurn.turn), activeTurn.seq, delta, List.of(), false,
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
					new ChatAnswerFrame(activeTurn.turn.threadId(), activeTurn.msgId, activeTurn.turn.turnId(),
							modelIdFor(activeTurn.turn), activeTurn.seq, "", List.of(), false, ChatAnswerStatus.DONE))) {
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
			nextTurn = new ActiveTurn(next, state.seqBlock);
			state.active = nextTurn;
		}
		startTurn(key, state, nextTurn);
	}

	private void closeGeneration(RoomKey key, RoomAiState state) {
		closeGeneration(key, state, false);
	}

	private void closeGeneration(RoomKey key, RoomAiState state, boolean notifyPendingCancellation) {
		ActiveTurn activeTurn;
		List<PendingTurn> pendingTurns;
		List<InFlightTurn> inFlightTurns;
		boolean waitingTurnsCancelled;
		synchronized (state) {
			if (state.closed) {
				return;
			}
			state.closed = true;
			states.remove(key, state);
			// pending만 보면 알림을 놓친다(이슈 #206). dispatch()는 inbox에 넣기만 하고 돌아오므로,
			// 워커가 아직 꺼내지 않은 @AI 발화는 pending에 없고 inFlight에만 있다. 그 상태에서 앞 턴이
			// 방송 실패로 방을 닫으면 그 발화는 조용히 사라지고 보낸 사람은 아무 설명도 못 받았다.
			waitingTurnsCancelled = !state.pending.isEmpty() || state.unprocessedAiTurns > 0;
			pendingTurns = new ArrayList<>(state.pending);
			inFlightTurns = new ArrayList<>(state.inFlight.values());
			state.pending.clear();
			state.inFlight.clear();
			activeTurn = state.active;
			state.active = null;
		}
		// 큐에 남은 메시지는 이 generation의 것이라 방송할 곳이 없다 — 워커째로 정리한다.
		state.inbox.tryEmitComplete();
		state.worker.dispose();
		if (activeTurn != null) {
			activeTurn.subscription.dispose();
			persistAgentCancellation(activeTurn);
		}
		if (state.kind == ThrKind.DIRECT) {
			pendingTurns.forEach(turn -> {
				if (turn.reservedTurn() != null) {
					persistDirectReservedAgentTerminal(turn.reservedTurn(), turn.terminalPersisted(),
							MsgStatus.CANCELLED);
				}
			});
			inFlightTurns.forEach(turn -> {
				ChatMessageCommand.ReservedTurn reserved = turn.queued().command().reservedTurn();
				if (reserved != null) {
					persistDirectReservedAgentTerminal(reserved, turn.queued().terminalPersisted(), MsgStatus.CANCELLED);
				}
			});
		}
		if (notifyPendingCancellation && waitingTurnsCancelled) {
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
	private void persistHumanMessageAsync(UUID msgId, long seq, ChatMessageCommand command) {
		Mono.fromCallable(() -> msgPersistenceService.persistHumanMessageBlocking(msgId, seq,
					command.threadId(), command.from(), command.content()))
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
	*
	* 여기서 구독하지 않는다(이슈 #160). 턴이 받아들여지지 않으면(시작 전 취소·대기열 초과) 문맥을
	* 읽을 이유가 없어 호출부가 persistHumanMessageAsync로 갈음한다 — 둘 중 하나만 구독해야 사람
	* 메시지가 두 번 저장되지 않는다.
	*/
	private Mono<List<Msg>> persistHumanMessageAndFetchContext(UUID msgId, long seq, ChatMessageCommand command) {
		return Mono
				.fromCallable(() -> msgPersistenceService.persistHumanMessageAndFetchContextBlocking(
						msgId, seq, command.threadId(), command.from(), command.content(), maxContextMessages))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(e -> {
					log.error("문맥 조회 및 HUMAN 메시지 저장 실패 threadId={} traceId={}", command.threadId(),
							command.traceId(), e);
					return Mono.just(List.<Msg>of());
				})
				.cache();
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

	/** 중단된 턴을 그때까지 생성된 내용과 함께 CANCELLED로 닫는다. 내용은 구독을 끊은 뒤 읽는다. */
	private void persistAgentCancellation(ActiveTurn activeTurn) {
		if (!activeTurn.terminalPersisted.compareAndSet(false, true)) {
			return;
		}
		String partialContent = activeTurn.content.toString();
		activeTurn.pendingMsgId
				.flatMap(msgId -> Mono.fromRunnable(() -> msgPersistenceService.cancelBlocking(msgId, partialContent))
						.subscribeOn(Schedulers.boundedElastic())
						.doOnError(e -> log.error("agent 메시지 취소 저장 실패 msgId={}", msgId, e)))
				.onErrorComplete()
				.subscribe();
	}

	private record RoomKey(UUID threadId, UUID roomGeneration) {
	}

	/**
	* 취소가 턴을 찾는 키(이슈 #160). turnId만으로는 믿지 않고 그 발화가 들어온 커넥션과 짝으로 쓴다 —
	* 이유는 cancel() 주석에 있다. 발화에 turnId가 없으면 null이고, 그 턴은 취소할 수 없다.
	*/
	private record TurnRef(UUID turnId, UUID connectionId) {

		static TurnRef of(UUID turnId, UUID connectionId) {
			return turnId == null ? null : new TurnRef(turnId, connectionId);
		}
	}

	/**
	* 방 하나의 seq 공급원. 블록을 예약해두고 메모리에서 꺼내 쓰다가, 다 쓰면 그때만 DB를 부른다.
	*
	* 워커(사람 메시지)와 턴 시작(AI 답변) 두 경로가 부르므로 잠근다 — 다만 정상 경로는 메모리
	* 연산뿐이라 잡는 시간이 거의 없고, DB를 부르는 것은 SEQ_BLOCK_SIZE건에 한 번이다.
	*/
	private final class SeqBlock {

		private final UUID threadId;

		private long next;

		private int remaining;

		SeqBlock(UUID threadId) {
			this.threadId = threadId;
		}

		synchronized long allocate() {
			if (remaining == 0) {
				next = msgPersistenceService.allocateSeqBlockBlocking(threadId, SEQ_BLOCK_SIZE);
				remaining = SEQ_BLOCK_SIZE;
			}
			remaining--;
			return next++;
		}
	}

	/** 워커가 꺼내 처리할 한 건. prompt가 null이면 AI 턴을 만들지 않는다(일반 발화·빈 프롬프트). */
	private record QueuedMessage(ChatMessageCommand command, String prompt, AtomicBoolean terminalPersisted) {
	}

	/** inbox에 들어간 예약 DIRECT 턴은 worker가 꺼내기 전에도 terminal 상태를 공유해야 한다. */
	private record InFlightTurn(QueuedMessage queued, boolean cancelled) {

		InFlightTurn cancel() {
			return new InFlightTurn(queued, true);
		}
	}

	/**
	* ref는 취소 지목 키다(이슈 #160). model이 null이면 서버 기본값을 쓴다. reservedTurn은 DIRECT만
	* 채운다 — `DirectChatTurnService`가 이미 만든 PENDING AGENT를 가리킨다(이슈 #162).
	*/
	private record PendingTurn(UUID threadId, UUID roomGeneration, String prompt, String traceId, TurnRef ref,
			String model, Mono<List<Msg>> context, ChatMessageCommand.ReservedTurn reservedTurn,
			AtomicBoolean terminalPersisted) {

		UUID turnId() {
			return ref == null ? null : ref.turnId();
		}
	}

	private final class ActiveTurn {

		private final PendingTurn turn;

		private final Disposable.Swap subscription = Disposables.swap();

		private final AtomicBoolean hasNonBlankOutput = new AtomicBoolean();

		private final Deque<String> leadingWhitespace = new ArrayDeque<>();

		/** 지금까지 생성된 답변. 스트림 스레드가 쓰고 취소하는 스레드가 읽어서 StringBuffer다(이슈 #160). */
		private final StringBuffer content = new StringBuffer();

		/**
		* PENDING 행 생성 결과(성공 시 msgId, 실패 시 empty)를 캐시해 여러 번 구독해도 한 번만
		* 실행한다 — startTurn()이 즉시 fire-and-forget으로 시작하고, 완료/실패 저장은 이 Mono가
		* 끝날 때까지 기다렸다가 msgId를 얻는다.
		*/
		private final Mono<UUID> pendingMsgId;

		/** 이 턴의 AGENT 메시지 id·seq. 턴이 시작되는 순간 확정되므로 답변이 대화에서 차지할
		 * 자리도 그때 정해진다 — 뒤따라 도착한 사람 메시지가 이 답변 앞으로 끼어들지 않는다. */
		private final UUID msgId;

		private final long seq;

		/** 완료/실패 저장이 이 턴에 대해 이미 한 번 시도됐는지 — 두 번째 시도는 조용히 건너뛴다. */
		private final AtomicBoolean terminalPersisted;

		ActiveTurn(PendingTurn turn, SeqBlock seqBlock) {
			this.turn = turn;
			this.terminalPersisted = turn.terminalPersisted();
			if (turn.reservedTurn() != null) {
				// DIRECT — PENDING AGENT는 DirectChatTurnService가 이미 만들어뒀다(이슈 #162).
				// 새로 만들지 않고 그 msgId·seq를 그대로 쓴다.
				this.msgId = turn.reservedTurn().agentMsgId();
				this.seq = turn.reservedTurn().agentSeq();
				this.pendingMsgId = Mono.just(this.msgId).cache();
			} else {
				this.msgId = UUID.randomUUID();
				this.seq = seqBlock.allocate();
				this.pendingMsgId = Mono.fromCallable(() -> msgPersistenceService.createPendingAgentMessageBlocking(
							this.msgId, this.seq, turn.threadId()))
						.subscribeOn(Schedulers.boundedElastic())
						.map(Msg::getId)
						.onErrorResume(e -> {
							log.error("PENDING agent 메시지 생성 실패 threadId={}", turn.threadId(), e);
							return Mono.empty();
						})
						.cache();
			}
		}
	}

	/**
	* 방 하나의 상태 — 방송을 기다리는 메시지 큐와 LLM 실행을 기다리는 AI 턴 FIFO를 함께 든다.
	*
	* 두 큐를 합치지 않는다(이슈 #190). AI 턴은 스트리밍이 끝날 때까지 살아 있고 일반 메시지는
	* 방송 한 번으로 끝나는데, 한 큐에 넣으면 긴 턴이 뒤따르는 짧은 메시지를 통째로 막는다.
	*/
	private final class RoomAiState {

		/** 방송을 기다리는 메시지. 가득 차면 tryEmitNext가 실패해 호출자가 RATE_LIMITED를 받는다. */
		private final Sinks.Many<QueuedMessage> inbox = Sinks.many().unicast()
				.onBackpressureBuffer(new ArrayBlockingQueue<QueuedMessage>(MAX_QUEUED_MESSAGES_PER_ROOM));

		private final Deque<PendingTurn> pending = new ArrayDeque<>();

		/**
		* 큐에 넣었지만 워커가 아직 꺼내지 않은 {@code @AI} 발화(이슈 #160). 값이 true면 그 사이에 취소됐다.
		* 워커가 꺼낼 때 지운다.
		*/
		private final Map<TurnRef, InFlightTurn> inFlight = new HashMap<>();

		/**
		* 큐에 넣었지만 워커가 아직 처리하지 않은 {@code @AI} 발화 수(이슈 #206). 방이 닫힐 때
		* "취소된 요청이 있었는가"를 판정하는 데 쓴다 — 그 발화는 pending에 아직 없기 때문이다.
		*
		* 위 inFlight로 갈음하지 않는 것은 그쪽이 turnId를 실은 발화만 담기 때문이다
		* (TurnRef.of는 turnId가 null이면 null). turnId 없는 {@code @AI} 발화도 유효한 계약이라
		* (frames.ts) 그쪽만 보면 같은 구멍이 남는다.
		*/
		private int unprocessedAiTurns;

		private final SeqBlock seqBlock;

		/** 방이 처음 만들어질 때의 발화가 정한 종류(이슈 #162) — cancel()이 DIRECT·COLLAB 인가
		 * 방식을 가르는 데 쓴다. 방 하나의 kind는 그 수명 내내 바뀌지 않는다. */
		private final ThrKind kind;

		/**
		* 이 방의 유일한 워커. prefetch 1로 두어 한 번에 한 건만 꺼내 처리한다 — 방송 순서가
		* 여기서 확정된다. 한 건이 실패해도 방 전체를 죽이지 않도록 예외를 삼킨다.
		*/
		private final Disposable worker;

		private ActiveTurn active;

		private boolean closed;

		RoomAiState(RoomKey key, ThrKind kind) {
			this.seqBlock = new SeqBlock(key.threadId());
			this.kind = kind;
			this.worker = inbox.asFlux()
					.publishOn(Schedulers.boundedElastic(), 1)
					.subscribe(queued -> {
						try {
							process(key, this, queued);
						} catch (RuntimeException error) {
							log.error("방 워커가 메시지를 처리하지 못했다 threadId={}", key.threadId(), error);
						}
					}, error -> log.error("방 워커가 비정상 종료했다 threadId={}", key.threadId(), error));
		}
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
