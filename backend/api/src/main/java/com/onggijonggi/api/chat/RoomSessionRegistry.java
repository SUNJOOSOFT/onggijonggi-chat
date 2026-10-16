package com.onggijonggi.api.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : RoomSessionRegistry.java
 * Description : 방 단위 연결과 프레임 방송을 in-memory로 관리한다. WebSocketSession은
 *               {@link ThreadWebSocketHandler}가 소유하며, in-memory sink 기반이라 단일
 *               서버 프로세스만 지원한다. 다중 인스턴스는 pub/sub와 분산 순서 보장이 필요하다.
 *               입퇴장 방송(이슈 #25)도 여기서 낸다 — 누가 방에 있는지 아는 곳이 여기뿐이라,
 *               "이미 있던 사람에게만 알린다"는 판단을 멤버십 변경과 같은 잠금 안에서 해야 한다.
 *               퇴장 통보는 즉시 나가지 않고 짧은 유예를 둔다 — 그 안에 같은 사람이 재연결하면
 *               퇴장·입장 모두 취소한다(이슈 #198). 토큰 만료로 인한 순간 재연결이 잦아 이 유예가
 *               없으면 다른 참여자 화면에 입퇴장이 매번 깜빡인다.
 *
 *               커넥션 하나가 여러 방에 들 수 있다(이슈 #161) — 방→커넥션 맵(rooms)과 함께
 *               커넥션→방 역방향 인덱스를 들고, 연결이 끊길 때 그 커넥션이 든 모든 방에서 뺀다.
 *
 *               <b>이 클래스는 락을 쥔 채로, 또 호출한 스레드에서 sink에 내보내지 않는다(이슈 #405).</b>
 *               내보내기(방 프레임·입퇴장 통보·방 완료·연결별 left·kicked)는 모두 방별 대기열에 넣기만
 *               하고, 방마다 한 번에 하나씩 도는 비우기 스레드(drainScheduler)가 아무 락도 쥐지 않은 채
 *               내보낸다. 내보내는 순간 구독자 코드가 같은 스레드에서 돌기 때문이다 — 닫히는 연결의 종료
 *               처리(leaveAll → leave)가 그 자리에서 돌며 이 클래스의 락을 다시 잡거나, 방송을 부른 쪽
 *               (dispatcher)이 쥔 락 안에서 남의 코드가 돌아 서로 다른 순서로 락을 잡는 교착이 났다.
 *               그래서 방송 결과는 "대기열에 넣었다"까지만 알려 주고, 실제 전송 실패는 비우기 스레드가 그 방·세대를
 *               {@link BroadcastFailureListener}로 알린다(dispatcher가 그 세대의 AI 작업을 닫는다).
 */
@Component
public class RoomSessionRegistry {

	private static final Logger log = LoggerFactory.getLogger(RoomSessionRegistry.class);

	private static final int WARMUP_BUFFER_SIZE = 1;

	/** 방 하나의 대기열에 쌓일 수 있는 방송 수. 넘치면 그 방송이 예외를 받는다(이슈 #405). */
	static final int MAX_PENDING = 1024;

	/** 방 하나의 대기열에 쌓일 수 있는 입퇴장·입력 중 통보 수. 방송과 따로 센다 — 입력 중 알림이 쌓여 방송 자리를 먹지 않게. */
	static final int MAX_PENDING_NOTICES = 256;

	/** 비우기 스레드가 한 번에 내보내는 최대 개수 — 넘으면 다음 차례로 넘겨 스레드 하나를 오래 붙잡지 않는다. */
	static final int DRAIN_BATCH = 64;

	private final ConcurrentMap<UUID, RoomState> rooms = new ConcurrentHashMap<>();

	/** 커넥션이 지금 든 방들(이슈 #161). 끊긴 커넥션을 모든 방에서 빼는 {@link #leaveAll}만 쓴다. */
	private final ConcurrentMap<UUID, Set<UUID>> roomsByConnection = new ConcurrentHashMap<>();

	private final Duration presenceLeaveDebounce;

	/** 방별 대기열을 비우는 스케줄러. 방 하나는 한 번에 한 스레드만 비운다(RoomState.drain). */
	private final Scheduler drainScheduler;

	/** 비우는 중 방송이 sink에 들어가지 못했을 때 알릴 곳. 받는 쪽(dispatcher)이 그 세대의 AI 작업을 닫는다. */
	private volatile BroadcastFailureListener broadcastFailureListener = (threadId, roomGeneration) -> {
	};

	@Autowired
	public RoomSessionRegistry(
			@Value("${app.collab.presence.leave-debounce:8s}") Duration presenceLeaveDebounce) {
		this(presenceLeaveDebounce, Schedulers.boundedElastic());
	}

	RoomSessionRegistry(Duration presenceLeaveDebounce, Scheduler drainScheduler) {
		this.presenceLeaveDebounce = presenceLeaveDebounce;
		this.drainScheduler = drainScheduler;
	}

	/**
	 * 비우는 중 방송이 sink에 들어가지 못하면 그 방·세대로 알릴 곳을 정한다(이슈 #405). 내보내기가 비동기라 실패를 방송한
	 * 쪽에 예외로 돌려줄 수 없다 — 다음 방송에 대신 던지면 실패와 무관한 사람의 발화·답변이 그 결과를 떠안는다. 알림은
	 * 비우기 스레드에서, registry의 어떤 잠금도 쥐지 않은 채 온다.
	 */
	public void onBroadcastFailure(BroadcastFailureListener listener) {
		this.broadcastFailureListener = listener;
	}

	/** 비우는 중 방송 실패를 받는 쪽. */
	@FunctionalInterface
	public interface BroadcastFailureListener {

		void broadcastFailed(UUID threadId, UUID roomGeneration);
	}

	/**
	 * 연결을 방에 등록하고 그 방의 세대와 프레임 스트림, 그리고 그 연결에만 보낼 참여자
	 * 스냅샷을 돌려준다. 그 사용자의 첫 연결이고 이미 있던 참여자가 하나라도 있으면 그들에게
	 * 입장을 알린다 — 첫 연결이면 알릴 상대가 없어 아무것도 내지 않는다. 이 구분은 취향이 아니라
	 * 필요다: sink의 warm-up 버퍼는 한 칸뿐이라, 빈 방에 프레임을 밀어 넣으면 그 자리가 채워져
	 * 뒤이어 오는 첫 메시지가 밀려난다(이슈 #102).
	 *
	 * 스냅샷은 방송이 아니라 이 연결의 값이라 sink를 거치지 않는다 — warm-up 버퍼를 건드리지
	 * 않는다는 뜻이고, 그래서 방의 첫 입장자에게도 그대로 내려보낼 수 있다. 명단은 멤버십을
	 * 바꾼 잠금 안에서 뜬다 — 밖에서 뜨면 그 사이에 들어온 사람이 명단에도 없고 입장 통보도
	 * 못 받는 연결이 생긴다.
	 *
	 * COLLAB 호출부는 그대로 이 3-인자 오버로드를 쓴다(presence 방송 유지).
	 */
	public RoomMembership join(UUID threadId, UUID connectionId, PresenceParticipant participant) {
		return join(threadId, connectionId, participant, true);
	}

	/**
	 * DIRECT 전용 진입점(이슈 #162) — presenceEnabled가 false면 이 방은 만들어지는 순간부터
	 * presence.join·presence.snapshot·presence.leave를 전혀 방송하지 않는다. 방 하나의
	 * presence 여부는 그 방을 처음 만든 join()이 정하고 수명 내내 바뀌지 않는다 — 같은 방에
	 * 두 값이 섞이면 어떤 탭은 알림을 받고 어떤 탭은 못 받는 모순이 생긴다.
	 */
	public RoomMembership join(UUID threadId, UUID connectionId, PresenceParticipant participant,
			boolean presenceEnabled) {
		List<PresenceParticipant> participants = new ArrayList<>();
		Sinks.One<Void> kicked = Sinks.one();
		Sinks.One<Void> left = Sinks.one();
		RoomState room = rooms.compute(threadId, (ignored, current) -> {
			RoomState joined = current == null
					? new RoomState(threadId, presenceLeaveDebounce, presenceEnabled, drainScheduler,
							this::reportBroadcastFailure)
					: current;
			if (joined.add(connectionId, participant, kicked, left) && joined.presenceEnabled) {
				// 잠금 안에서는 대기열에 넣기만 한다 — 명단과 입장 통보의 순서는 여기서 정해지고, 내보내기는 비우기 스레드가 한다.
				joined.offerNotice(
						new PresenceJoinFrame(threadId, participant.subject(), participant.displayName()));
			}
			participants.addAll(joined.participants());
			return joined;
		});
		roomsByConnection.computeIfAbsent(connectionId, ignored -> ConcurrentHashMap.newKeySet()).add(threadId);
		return new RoomMembership(room.generation(),
				new PresenceSnapshotFrame(threadId, List.copyOf(participants)), room.frames(), kicked.asMono(),
				left.asMono());
	}

	/**
	 * 이 커넥션이 그 방에 들어 있으면 방의 현재 세대를 돌려준다(이슈 #161). 발화·취소가 어느 세대로
	 * 들어갈지를 여기서 정한다 — 경로가 방을 고정하던 때는 연결 수명 내내 한 값이었지만, 이제는 구독이
	 * 해지됐다가 다시 걸리면 세대가 바뀔 수 있다.
	 */
	public Optional<UUID> generationFor(UUID threadId, UUID connectionId) {
		RoomState room = rooms.get(threadId);
		return room != null && room.contains(connectionId) ? Optional.of(room.generation()) : Optional.empty();
	}

	/**
	 * 참가자 명단에서 그 subject를 빼는 일(#20의 remove·leaveSelf)이 이미 벌어진 뒤 호출된다 —
	 * 여기서는 그 사람이 지금 이 방에 걸어 둔 구독(탭이 여럿이면 전부)에 강제 해지 신호만 보낸다.
	 * 실제로 구독을 푸는 것은 그 신호를 구독하는 {@link ThreadWebSocketHandler} 몫이다(이슈 #135).
	 * 커넥션은 닫지 않는다 — 한 커넥션이 여러 방을 나르므로 다른 방까지 끊긴다(이슈 #161).
	 *
	 * @return 신호를 받은 연결이 하나라도 있으면 {@code true} — 이미 연결이 없던 사람(초대만
	 * 받고 접속한 적 없는 등)이면 {@code false}
	 */
	public boolean evict(UUID threadId, String subject) {
		RoomState room = rooms.get(threadId);
		return room != null && room.evict(subject);
	}

	/** 그 사람을 모든 협업방 구독에서 뺀다(권한 회수, #299). 1:1 방은 presence가 꺼져 있어 건드리지 않는다. */
	public boolean evictCollabSubscriptions(String subject) {
		boolean evictedAny = false;
		for (RoomState room : rooms.values()) {
			evictedAny |= room.evictIfPresenceEnabled(subject);
		}
		return evictedAny;
	}

	/** 한 협업방의 구독을 모두 해제한다. WebSocket 연결은 끊지 않는다 — 다시 구독하면 최신 권한으로 판정한다. */
	public boolean evictCollabRoom(UUID threadId) {
		RoomState room = rooms.get(threadId);
		return room != null && room.evictAllIfPresenceEnabled();
	}

	/**
	 * 현재 방 세대에만 프레임을 방송한다. 방의 대기열에 넣고 바로 돌아온다 — 실제 전송은 비우기 스레드가
	 * 한다(이슈 #405). 방이 이미 비활성이면 넣지 않는다 — 활성 판정과 넣기가 같은 잠금 안이라 방 완료
	 * 신호 뒤에 프레임이 들어가지 않는다.
	 *
	 * @return 대기열에 넣었으면 {@code true}. 방이 없거나 generation이 달라 stale이거나 비활성이면 {@code false}.
	 * @throws IllegalStateException 방송 대기열이 꽉 차 이 프레임을 넣지 못한 경우 — 호출자가 그 세대의 AI 작업을 닫는다.
	 *         넣은 뒤 sink에 들어가지 못한 실패는 여기서 던지지 않고 {@link #onBroadcastFailure}로 알린다
	 */
	public boolean broadcastIfCurrent(UUID threadId, UUID roomGeneration, WsFrame frame) {
		RoomState room = rooms.get(threadId);
		if (room == null || !room.generation().equals(roomGeneration)) {
			return false;
		}
		return room.offerBroadcast(frame);
	}

	/**
	 * 지금 그 방에 붙어 있는 사람에게만 의미가 있는 통지를 보낸다 — 아무도 듣고 있지 않으면
	 * 조용히 버린다(입퇴장 통보와 같은 결). broadcastIfCurrent와 달리 room generation을
	 * 요구하지 않는다 — 배치(RiskCheckBatchService, 이슈 #28)처럼 특정 연결의 턴에 묶이지 않은
	 * 호출자는 그 값을 모른다.
	 */
	public void notifyIfListening(UUID threadId, WsFrame frame) {
		RoomState room = rooms.get(threadId);
		if (room != null) {
			room.offerListeningNotice(frame);
		}
	}

	/**
	 * 그 세대의 화면 힌트(도구 진행 표시 등, 이슈 #397)를 보낸다. 방송처럼 현재 세대에만 넣지만 입퇴장·입력 중 통보와
	 * 같은 부가 통보로 다룬다 — 듣는 사람이 없거나 통보 상한을 넘으면 버리고, 전송이 실패해도 그 세대를 닫지 않는다.
	 * 힌트 하나 때문에 답변이 끊기거나 방송 자리를 먹지 않게 하려는 것이다. 같은 대기열이라 방송과의 순서는 지켜진다.
	 */
	public void notifyIfCurrent(UUID threadId, UUID roomGeneration, WsFrame frame) {
		RoomState room = rooms.get(threadId);
		if (room != null && room.generation().equals(roomGeneration)) {
			room.offerActiveNotice(frame);
		}
	}

	/**
	 * 입력 중 알림을 방에 낸다. 그 커넥션이 이 방을 구독 중이고 협업방(presence가 켜진 방)일 때만
	 * 보낸다 — 1:1 방에는 알릴 사람이 없다. presence처럼 부가 정보라 듣는 사람이 없으면 버리고
	 * 실패해도 던지지 않는다. 입력한 본인에게 돌아가지 않게 거르는 것은 구독 쪽(ThreadWebSocketHandler) 몫이다.
	 *
	 * @return 대기열에 넣었으면 {@code true}
	 */
	public boolean notifyTyping(UUID connectionId, ChatTypingFrame frame) {
		RoomState room = rooms.get(frame.threadId());
		if (room == null || !room.presenceEnabled || !room.contains(connectionId)) {
			return false;
		}
		room.offerNotice(frame);
		return true;
	}

	/**
	 * 연결을 방에서 빼고, 그 사용자의 마지막 연결이었다면 유예 시간 뒤 남은 참여자에게 퇴장을
	 * 알린다(이슈 #198) — 그 유예 안에 같은 사람이 재연결하면 이 퇴장 통보는 나가지 않는다.
	 * 같은 사용자의 다른 연결이 남아 있으면 그 사람은 아직 방에 있으므로 알리지 않는다. 방의
	 * 마지막 연결이었다면 방이 사라지므로 역시 알리지 않는다 — 받을 사람도 없고, 사라진 방에
	 * 방송하면 그 프레임은 아무 데도 가지 않는다.
	 *
	 * @return 마지막 연결이 퇴장해 방이 완전히 비면 그 방 세대를, 아니면 빈 Optional을 반환한다.
	 */
	public Optional<UUID> leave(UUID threadId, UUID connectionId, PresenceParticipant participant) {
		UUID[] emptiedGeneration = new UUID[1];
		rooms.computeIfPresent(threadId, (ignored, current) -> {
			Departure departure = current.remove(connectionId);
			if (departure == Departure.ROOM_EMPTY) {
				emptiedGeneration[0] = current.generation();
				return null;
			}
			if (departure == Departure.USER_GONE) {
				current.scheduleDeparture(threadId, participant);
			}
			return current;
		});
		roomsByConnection.computeIfPresent(connectionId, (ignored, threadIds) -> {
			threadIds.remove(threadId);
			return threadIds.isEmpty() ? null : threadIds;
		});
		return Optional.ofNullable(emptiedGeneration[0]);
	}

	/**
	 * 끊긴 커넥션을 그 커넥션이 든 모든 방에서 뺀다(이슈 #161). 방마다 {@link #leave}와 같은 판정을
	 * 거친다 — 입퇴장 유예·방 비움이 방 단위로 따로 갈린다.
	 *
	 * @return 이 퇴장으로 완전히 빈 방의 threadId → 그 방 세대. 호출자가 그 세대의 AI 작업을 닫는다.
	 */
	public Map<UUID, UUID> leaveAll(UUID connectionId, PresenceParticipant participant) {
		Set<UUID> threadIds = roomsByConnection.remove(connectionId);
		if (threadIds == null) {
			return Map.of();
		}
		Map<UUID, UUID> emptiedGenerations = new HashMap<>();
		for (UUID threadId : threadIds) {
			leave(threadId, connectionId, participant)
					.ifPresent(generation -> emptiedGenerations.put(threadId, generation));
		}
		return emptiedGenerations;
	}

	private void reportBroadcastFailure(UUID threadId, UUID roomGeneration) {
		try {
			broadcastFailureListener.broadcastFailed(threadId, roomGeneration);
		} catch (RuntimeException error) {
			log.warn("방송 실패 알림 처리 중 오류 threadId={} generation={}", threadId, roomGeneration, error);
		}
	}

	/**
	 * 그 방의 대기열이 비었고 비우는 중도 아니면 true(방이 없어도 true). 내보내기가 비동기라(이슈 #405)
	 * 테스트가 "아무것도 오지 않았다"를 확인하기 전에 다 나갔는지 기다리는 데 쓴다.
	 */
	boolean idle(UUID threadId) {
		RoomState room = rooms.get(threadId);
		return room == null || room.idle();
	}

	/**
	 * 이 연결이 방에 대해 아는 전부. snapshot은 연결이 붙는 순간의 명단이고, frames는 그 뒤로
	 * 방에서 일어나는 일이다 — 둘을 이어 붙여 내보내는 것은 {@link ThreadWebSocketHandler}가 한다.
	 * kicked는 이 연결 하나에만 오는 강제 해지 신호다(evict, 이슈 #135) — frames와 달리 방 전체가
	 * 아니라 이 connectionId로만 간다. left는 이 연결이 방에서 빠지는 순간 완료된다(이슈 #161) —
	 * 커넥션이 살아 있어도 구독이 풀리면 frames를 그만 받아야 하는데, 방에 남은 사람이 있으면 frames
	 * 자체는 끝나지 않는다.
	 */
	public record RoomMembership(UUID generation, PresenceSnapshotFrame snapshot, Flux<WsFrame> frames,
			Mono<Void> kicked, Mono<Void> left) {
	}

	/**
	 * 연결 하나가 빠진 뒤 방이 어떤 상태가 됐는지. 퇴장 통보를 낼지가 여기서 갈린다. 방송이
	 * 나르는 값은 connectionId가 아니라 사람이라, 연결 단위로만 세면 탭을 하나 더 열었다 닫은
	 * 사람이 나간 것으로 보인다(이슈 #25 리뷰).
	 */
	private enum Departure {

		/** 방의 마지막 연결이었다. 방을 지우며, 알릴 상대도 없다. */
		ROOM_EMPTY,

		/** 그 사용자의 마지막 연결이었다. 남은 참여자에게 퇴장을 알린다. */
		USER_GONE,

		/** 같은 사용자의 다른 연결이 남아 있다. 아직 방에 있으므로 퇴장이 아니다. */
		STILL_CONNECTED

	}

	private static final class RoomState {

		/** connectionId에서 그 연결을 연 사람과, 그 연결만을 위한 강제 종료 신호(evict, 이슈 #135)로.
		 * 한 사용자가 탭·재연결로 여러 연결을 가질 수 있다. 스냅샷이 입장 순서대로 나가야 해서
		 * 순서를 지키는 구현을 쓴다.
		 *
		 * 같은 사람인지는 subject로만 판정한다 — 표시 이름은 Keycloak에서 바뀔 수 있어, 값 전체를
		 * 비교하면 이름이 바뀐 사이에 연 두 번째 탭이 남남으로 보인다(이슈 #130). */
		private final Map<UUID, ConnectionEntry> connections = new LinkedHashMap<>();

		/** subject별로 예정된 퇴장 통보 하나. 같은 사람이 유예 안에 재연결하면 여기서 꺼내 dispose해
		 * 통보를 무산시킨다(이슈 #198). connections에서는 연결이 끊기는 즉시 빠지므로, 유예 중인
		 * 사람을 {@link #participants}에서도 보여주려면 참가자 값을 따로 들고 있어야 한다 — 안
		 * 그러면 그 사이에 들어온 사람의 스냅샷에서만 이 사람이 사라져, 기존 참여자 화면과 어긋난다. */
		private final Map<String, PendingDeparture> pendingLeaves = new LinkedHashMap<>();

		private final UUID generation = UUID.randomUUID();

		private final Duration leaveDebounce;

		/** DIRECT 방은 이 방이 처음 만들어질 때부터 false다(이슈 #162) — join·leave 모두 이 값을
		 * 보고 presence 프레임 자체를 만들지 않는다. */
		private final boolean presenceEnabled;

		private boolean active = true;

		private final Sinks.Many<WsFrame> frames = Sinks.many()
				.multicast()
				.onBackpressureBuffer(WARMUP_BUFFER_SIZE, false);

		/** 내보낼 것들의 방별 대기열(이슈 #405). 넣기는 아무 스레드나, 비우기는 한 번에 하나(wip)만 한다. */
		private final Queue<Outgoing> outbox = new ConcurrentLinkedQueue<>();

		/** 비우기 차례 수 — 0에서 1이 될 때만 비우기를 예약해, 방 하나를 두 스레드가 함께 비우지 않는다. */
		private final AtomicInteger wip = new AtomicInteger();

		/** 대기열에 든 방송 수(상한 계산용). 방 완료·left·kicked는 세지 않는다 — 항상 넣는다. */
		private final AtomicInteger pendingBroadcasts = new AtomicInteger();

		/** 대기열에 든 부가 통보 수(상한 계산용). */
		private final AtomicInteger pendingNotices = new AtomicInteger();

		private final UUID threadId;

		private final Scheduler drainScheduler;

		/** 방송 하나가 sink에 들어가지 못하면 이 방·세대로 알린다 — 실패를 그 방송의 주인(그 세대의 AI 작업)이 받게 한다. */
		private final BroadcastFailureListener failureReporter;

		RoomState(UUID threadId, Duration leaveDebounce, boolean presenceEnabled, Scheduler drainScheduler,
				BroadcastFailureListener failureReporter) {
			this.threadId = threadId;
			this.leaveDebounce = leaveDebounce;
			this.presenceEnabled = presenceEnabled;
			this.drainScheduler = drainScheduler;
			this.failureReporter = failureReporter;
		}

		/**
		 * 연결을 방에 넣고, 이 입장을 알려야 하는지 돌려준다. 알리려면 세 조건이 함께 성립해야
		 * 한다 — 그 사용자의 첫 연결이어야 하고(탭을 더 열거나 재연결한 것은 입장이 아니다),
		 * 들을 상대가 이미 방에 있어야 하고, 유예 중인 퇴장 통보로 상쇄되지 않아야 한다. 상쇄되면
		 * 그 퇴장 통보 자체가 무산되므로 입장도 알리지 않는다 — 화면엔 둘 다 보이지 않아야 한다.
		 */
		synchronized boolean add(UUID connectionId, PresenceParticipant participant, Sinks.One<Void> kicked,
				Sinks.One<Void> left) {
			boolean hadOthers = !connections.isEmpty();
			boolean userIsNew = !hasSubject(participant.subject());
			connections.put(connectionId, new ConnectionEntry(participant, kicked, left));
			if (userIsNew) {
				PendingDeparture pendingLeave = pendingLeaves.remove(participant.subject());
				if (pendingLeave != null) {
					pendingLeave.schedule().dispose();
					return false;
				}
			}
			return hadOthers && userIsNew;
		}

		UUID generation() {
			return generation;
		}

		synchronized boolean contains(UUID connectionId) {
			return connections.containsKey(connectionId);
		}

		/**
		 * 지금 방에 있는 사용자를 입장 순서대로. 같은 사람의 연결이 여럿이어도 한 번만 센다 —
		 * 표시 이름이 달라도 subject가 같으면 한 사람이므로 먼저 들어온 쪽을 남긴다. 퇴장 유예
		 * 중인 사람도 아직 나간 것으로 확정되지 않았으므로 포함한다(이슈 #198) — 빼면 그 유예
		 * 동안 새로 들어온 사람만 그 사람을 못 보는 채로 남는다.
		 */
		synchronized List<PresenceParticipant> participants() {
			Map<String, PresenceParticipant> bySubject = new LinkedHashMap<>();
			for (ConnectionEntry entry : connections.values()) {
				bySubject.putIfAbsent(entry.participant().subject(), entry.participant());
			}
			for (PendingDeparture pending : pendingLeaves.values()) {
				bySubject.putIfAbsent(pending.participant().subject(), pending.participant());
			}
			return List.copyOf(bySubject.values());
		}

		private boolean hasSubject(String subject) {
			return connections.values().stream()
					.anyMatch(entry -> entry.participant().subject().equals(subject));
		}

		/**
		 * 연결을 방에서 빼고 그 결과를 돌려준다. 방이 비면 sink 종결을 넣고 이 방을 비활성으로
		 * 표시한다 — 곧 버려질 방이고, 그 뒤 남은 세대로 들어오는 방송은 여기서 걸러야 한다.
		 * 그 연결의 left와 방 종결은 대기열로 간다 — 그보다 먼저 넣은 프레임이 다 나간 뒤에 나간다.
		 */
		synchronized Departure remove(UUID connectionId) {
			ConnectionEntry removed = connections.remove(connectionId);
			if (removed != null) {
				offer(new Signal(removed.left()));
			}
			if (connections.isEmpty()) {
				active = false;
				offer(Complete.INSTANCE);
				pendingLeaves.values().forEach(pending -> pending.schedule().dispose());
				pendingLeaves.clear();
				return Departure.ROOM_EMPTY;
			}
			if (removed == null) {
				return Departure.STILL_CONNECTED;
			}
			return hasSubject(removed.participant().subject()) ? Departure.STILL_CONNECTED : Departure.USER_GONE;
		}

		/**
		 * 퇴장 통보를 바로 내지 않고 {@link #leaveDebounce}만큼 미룬다. 그사이 같은 subject가
		 * {@link #add}로 돌아오면 이 스케줄은 dispose되어 콜백이 아예 돌지 않는다 — 유예가 끝나
		 * 실제로 도는 콜백은 그동안 재연결이 없었다는 뜻이라 다시 확인할 필요가 없다.
		 */
		synchronized void scheduleDeparture(UUID threadId, PresenceParticipant participant) {
			if (!presenceEnabled) {
				return;
			}
			String subject = participant.subject();
			PendingDeparture previous = pendingLeaves.remove(subject);
			if (previous != null) {
				previous.schedule().dispose();
			}
			Disposable scheduled = Mono.delay(leaveDebounce)
					.subscribe(ignored -> confirmDeparture(threadId, subject));
			pendingLeaves.put(subject, new PendingDeparture(scheduled, participant));
		}

		private synchronized void confirmDeparture(UUID threadId, String subject) {
			PendingDeparture pending = pendingLeaves.remove(subject);
			if (pending == null || !active) {
				return;
			}
			offerNotice(new PresenceLeaveFrame(threadId, subject, pending.participant().displayName()));
		}

		/** 유예 중인 퇴장 하나 — 취소용 스케줄과, 그동안 {@link #participants}가 계속 보여줘야
		 * 할 참가자 값을 함께 들고 있는다. */
		private record PendingDeparture(Disposable schedule, PresenceParticipant participant) {
		}

		/**
		 * 그 subject가 지금 들고 있는 연결(탭이 여럿이면 전부) 각각에 kicked 신호를 넣는다. 실제 연결
		 * 제거는 하지 않는다 — 신호를 받은 ThreadWebSocketHandler가 그 구독을 풀며
		 * {@link RoomSessionRegistry#leave}를 불러 자연히 빠진다.
		 */
		synchronized boolean evict(String subject) {
			boolean evictedAny = false;
			for (ConnectionEntry entry : connections.values()) {
				if (entry.participant().subject().equals(subject)) {
					offer(new Signal(entry.kicked()));
					evictedAny = true;
				}
			}
			return evictedAny;
		}

		synchronized boolean evictIfPresenceEnabled(String subject) {
			return presenceEnabled && evict(subject);
		}

		synchronized boolean evictAllIfPresenceEnabled() {
			if (!presenceEnabled) return false;
			for (ConnectionEntry entry : connections.values()) offer(new Signal(entry.kicked()));
			return !connections.isEmpty();
		}

		Flux<WsFrame> frames() {
			return frames.asFlux();
		}

		/** 넣을 때마다 wip가 오르고 비운 만큼 내려가므로, 0이면 대기열이 비었고 비우는 스레드도 없다. */
		boolean idle() {
			return wip.get() == 0;
		}

		/**
		 * 입퇴장·입력 중 같은 부가 통보를 넣는다. 지금 듣고 있는 구독자가 없으면 보내지 않는데, 그 판정은
		 * 내보내는 순간(비우기 스레드)에 한다 — 구독자가 없을 때 밀어 넣으면 warm-up 버퍼 한 칸을 차지해
		 * 뒤따라오는 첫 메시지를 밀어낸다(이슈 #102). 실패해도 던지지 않고, 통보 상한(MAX_PENDING_NOTICES)을 넘으면 버린다 —
		 * 부가 정보이고, 특히 퇴장 경로에서 예외가 나면 세션 종료가 망가진다. 버려도 다음 입퇴장·재연결 스냅샷이 맞춘다.
		 */
		synchronized void offerNotice(WsFrame frame) {
			if (!offer(new Notice(frame, true))) {
				log.debug("방 통보 대기열이 차서 버린다 threadId={} type={}", threadId, frame.getClass().getSimpleName());
			}
		}

		/** 방이 활성일 때만 부가 통보를 넣는다 — 방 완료 신호 뒤에 들어가지 않게, 활성 판정과 넣기를 같은 잠금 안에서 한다. */
		synchronized void offerActiveNotice(WsFrame frame) {
			if (active) {
				offerNotice(frame);
			}
		}

		/**
		 * {@link RoomSessionRegistry#notifyIfListening}의 통보(참여자 변경·위험 검사 결과 등)를 넣는다. 듣는 사람이 없으면
		 * 보내지 않는 것은 부가 통보와 같지만, 상한으로 버리지 않는다 — 참여자 변경은 흐름에 남는 입퇴장 줄을 실어 와,
		 * 버리면 새로고침 전까지 그 줄이 안 보인다. 드물게 나는 통보라 상한 없이 넣어도 쌓이지 않는다.
		 */
		synchronized void offerListeningNotice(WsFrame frame) {
			offer(new Notice(frame, false));
		}

		/**
		 * 방송 프레임을 넣는다. 활성 판정과 넣기를 같은 잠금 안에서 해, 방 완료 신호 뒤에 프레임이 들어가지
		 * 않게 한다.
		 */
		synchronized boolean offerBroadcast(WsFrame frame) {
			if (!active) {
				return false;
			}
			if (!offer(new Broadcast(frame))) {
				throw new IllegalStateException("room frame emission failed: queue full");
			}
			return true;
		}

		/**
		 * 대기열에 넣고, 비우는 스레드가 없으면 비우기를 예약한다. 기다리지 않고 남의 코드도 부르지 않으므로
		 * 잠금 안에서 불러도 된다. 방송·통보는 각자의 상한을 넘으면 넣지 않고 false를 돌려준다 — 방 완료·left·kicked는
		 * 버리면 연결이 방에서 영영 안 빠지거나 방이 안 닫히므로 항상 넣는다.
		 *
		 * 부르는 곳은 모두 이 방 상태의 잠금 안이다 — 넣기·세기·예약이 다른 넣기와 섞이지 않는다.
		 */
		private boolean offer(Outgoing item) {
			AtomicInteger counter = counterFor(item);
			if (counter != null && counter.incrementAndGet() > limitFor(item)) {
				counter.decrementAndGet();
				return false;
			}
			outbox.offer(item);
			if (wip.getAndIncrement() == 0) {
				try {
					drainScheduler.schedule(this::drain);
				} catch (RejectedExecutionException rejected) {
					// 스케줄러가 받지 않으면(종료 중·작업 상한) 비우는 스레드가 없는 채로 wip만 남아 이 방이 영영 안 비워진다.
					// 0으로 돌려 다음 넣기가 다시 예약하게 한다 — 잠금 안이라 다른 넣기가 끼어들지 않고, 비우는 스레드도 없다.
					wip.set(0);
					log.warn("방 대기열 비우기를 예약하지 못했다 — 다음 넣기 때 다시 예약한다", rejected);
				}
			}
			return true;
		}

		private AtomicInteger counterFor(Outgoing item) {
			if (item instanceof Broadcast) {
				return pendingBroadcasts;
			}
			return item instanceof Notice notice && notice.droppable() ? pendingNotices : null;
		}

		private static int limitFor(Outgoing item) {
			return item instanceof Broadcast ? MAX_PENDING : MAX_PENDING_NOTICES;
		}

		/**
		 * 대기열을 넣은 순서대로 내보낸다. 한 번에 한 스레드만 돈다(wip) — 방 안 순서가 지켜지고 sink에 동시에
		 * 내보내지 않는다. 이 스레드는 registry의 어떤 잠금도 쥐지 않는다: 내보내는 순간 구독자 코드(닫히는
		 * 연결의 leaveAll 등)가 여기서 돌아도 잠금을 처음부터 잡는다. {@link #DRAIN_BATCH}개를 넘게 내보내면
		 * 다음 차례로 넘긴다 — 그동안 wip가 0이 아니므로 다른 스레드가 비우기를 시작하지 않는다.
		 */
		private void drain() {
			int missed = 1;
			int sent = 0;
			do {
				Outgoing item;
				while ((item = outbox.poll()) != null) {
					AtomicInteger counter = counterFor(item);
					if (counter != null) {
						counter.decrementAndGet();
					}
					deliver(item);
					if (++sent >= DRAIN_BATCH) {
						try {
							drainScheduler.schedule(this::drain);
							return;
						} catch (RejectedExecutionException rejected) {
							// 다음 차례를 맡길 수 없으면 이 스레드가 계속 비운다 — 여기서 돌아가면 wip가 남아 방이 멈춘다.
							sent = 0;
						}
					}
				}
				missed = wip.addAndGet(-missed);
			} while (missed != 0);
		}

		private void deliver(Outgoing item) {
			try {
				if (item instanceof Broadcast broadcast) {
					Sinks.EmitResult result = frames.tryEmitNext(broadcast.frame());
					if (result.isFailure()) {
						log.warn("방 프레임 전송 실패 threadId={} generation={} result={}", threadId, generation, result);
						failureReporter.broadcastFailed(threadId, generation);
					}
				} else if (item instanceof Notice notice) {
					if (frames.currentSubscriberCount() > 0) {
						frames.tryEmitNext(notice.frame());
					}
				} else if (item instanceof Signal signal) {
					signal.sink().tryEmitEmpty();
				} else {
					frames.tryEmitComplete();
				}
			} catch (RuntimeException error) {
				// 구독자 코드가 던진 것이 비우기를 멈추게 두지 않는다 — 남은 left·완료가 못 나가면 연결이 방에 남는다.
				log.warn("방 대기열 전송 중 오류", error);
				if (item instanceof Broadcast) {
					failureReporter.broadcastFailed(threadId, generation);
				}
			}
		}

		/** kicked·left는 이 연결 하나만의 것이라 frames(sink 하나를 방 전체가 공유)와 섞이지 않는다. */
		private record ConnectionEntry(PresenceParticipant participant, Sinks.One<Void> kicked,
				Sinks.One<Void> left) {
		}
	}

	/** 방 대기열에 드는 것(이슈 #405). */
	private sealed interface Outgoing permits Broadcast, Notice, Complete, Signal {
	}

	/** 방송 프레임 — 전송이 실패하면 그 방·세대를 실패 알림으로 보낸다. */
	private record Broadcast(WsFrame frame) implements Outgoing {
	}

	/** 통보(입퇴장·입력 중·notifyIfListening) — 듣는 사람이 없으면 버리고, 실패해도 무시한다. droppable이면 통보 상한을 센다. */
	private record Notice(WsFrame frame, boolean droppable) implements Outgoing {
	}

	/** 방 sink 종결 — 방의 마지막 연결이 빠졌다. */
	private enum Complete implements Outgoing {
		INSTANCE
	}

	/** 연결 하나에만 가는 신호(left·kicked). */
	private record Signal(Sinks.One<Void> sink) implements Outgoing {
	}

}
