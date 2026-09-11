package com.onggijonggi.api.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Class Name : RoomSessionRegistry.java
 * Description : 방 단위 연결과 프레임 방송을 in-memory로 관리한다. WebSocketSession은
 *               {@link CollabWebSocketHandler}가 소유하며, in-memory sink 기반이라 단일
 *               서버 프로세스만 지원한다. 다중 인스턴스는 pub/sub와 분산 순서 보장이 필요하다.
 *               입퇴장 방송(이슈 #25)도 여기서 낸다 — 누가 방에 있는지 아는 곳이 여기뿐이라,
 *               "이미 있던 사람에게만 알린다"는 판단을 멤버십 변경과 같은 잠금 안에서 해야 한다.
 *               퇴장 통보는 즉시 나가지 않고 짧은 유예를 둔다 — 그 안에 같은 사람이 재연결하면
 *               퇴장·입장 모두 취소한다(이슈 #198). 토큰 만료로 인한 순간 재연결이 잦아 이 유예가
 *               없으면 다른 참여자 화면에 입퇴장이 매번 깜빡인다.
 */
@Component
public class RoomSessionRegistry {

	private static final int WARMUP_BUFFER_SIZE = 1;

	private final ConcurrentMap<UUID, RoomState> rooms = new ConcurrentHashMap<>();

	private final Duration presenceLeaveDebounce;

	public RoomSessionRegistry(
			@Value("${app.collab.presence.leave-debounce:8s}") Duration presenceLeaveDebounce) {
		this.presenceLeaveDebounce = presenceLeaveDebounce;
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
	 */
	public RoomMembership join(UUID threadId, UUID connectionId, PresenceParticipant participant) {
		List<PresenceParticipant> participants = new ArrayList<>();
		Sinks.One<Void> kicked = Sinks.one();
		RoomState room = rooms.compute(threadId, (ignored, current) -> {
			RoomState joined = current == null ? new RoomState(presenceLeaveDebounce) : current;
			if (joined.add(connectionId, participant, kicked)) {
				joined.emitPresence(
						new PresenceJoinFrame(threadId, participant.subject(), participant.displayName()));
			}
			participants.addAll(joined.participants());
			return joined;
		});
		return new RoomMembership(room.generation(),
				new PresenceSnapshotFrame(threadId, List.copyOf(participants)), room.frames(), kicked.asMono());
	}

	/**
	 * 참가자 명단에서 그 subject를 빼는 일(#20의 remove·leaveSelf)이 이미 벌어진 뒤 호출된다 —
	 * 여기서는 그 사람이 지금 들고 있는 연결(탭이 여럿이면 전부)에 강제 종료 신호만 보낸다.
	 * 실제로 소켓을 닫는 것은 그 신호를 구독하는 {@link CollabWebSocketHandler} 몫이다(이슈 #135).
	 *
	 * @return 신호를 받은 연결이 하나라도 있으면 {@code true} — 이미 연결이 없던 사람(초대만
	 * 받고 접속한 적 없는 등)이면 {@code false}
	 */
	public boolean evict(UUID threadId, String subject) {
		RoomState room = rooms.get(threadId);
		return room != null && room.evict(subject);
	}

	/**
	 * 현재 방 세대에만 프레임을 방송한다.
	 *
	 * @return 방이 없거나 generation이 달라 stale이면 {@code false}; 현재 sink 고장은 예외로 전파한다.
	 */
	public boolean broadcastIfCurrent(UUID threadId, UUID roomGeneration, WsFrame frame) {
		RoomState room = rooms.get(threadId);
		if (room == null || !room.generation().equals(roomGeneration)) {
			return false;
		}
		return room.emitIfActive(frame);
	}

	/**
	 * 지금 그 방에 붙어 있는 사람에게만 의미가 있는 통지를 보낸다 — 아무도 듣고 있지 않으면
	 * 조용히 버린다(위 emitPresence와 같은 결). broadcastIfCurrent와 달리 room generation을
	 * 요구하지 않는다 — 배치(RiskCheckBatchService, 이슈 #28)처럼 특정 연결의 턴에 묶이지 않은
	 * 호출자는 그 값을 모른다.
	 */
	public void notifyIfListening(UUID threadId, WsFrame frame) {
		RoomState room = rooms.get(threadId);
		if (room != null) {
			room.emitPresence(frame);
		}
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
		return Optional.ofNullable(emptiedGeneration[0]);
	}

	/**
	 * 이 연결이 방에 대해 아는 전부. snapshot은 연결이 붙는 순간의 명단이고, frames는 그 뒤로
	 * 방에서 일어나는 일이다 — 둘을 이어 붙여 내보내는 것은 {@link CollabWebSocketHandler}가 한다.
	 * kicked는 이 연결 하나에만 오는 강제 종료 신호다(evict, 이슈 #135) — frames와 달리 방 전체가
	 * 아니라 이 connectionId로만 간다.
	 */
	public record RoomMembership(UUID generation, PresenceSnapshotFrame snapshot, Flux<WsFrame> frames,
			Mono<Void> kicked) {
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
		 * 통보를 무산시킨다(이슈 #198). */
		private final Map<String, Disposable> pendingLeaves = new HashMap<>();

		private final UUID generation = UUID.randomUUID();

		private final Duration leaveDebounce;

		private boolean active = true;

		private final Sinks.Many<WsFrame> frames = Sinks.many()
				.multicast()
				.onBackpressureBuffer(WARMUP_BUFFER_SIZE, false);

		RoomState(Duration leaveDebounce) {
			this.leaveDebounce = leaveDebounce;
		}

		/**
		 * 연결을 방에 넣고, 이 입장을 알려야 하는지 돌려준다. 알리려면 세 조건이 함께 성립해야
		 * 한다 — 그 사용자의 첫 연결이어야 하고(탭을 더 열거나 재연결한 것은 입장이 아니다),
		 * 들을 상대가 이미 방에 있어야 하고, 유예 중인 퇴장 통보로 상쇄되지 않아야 한다. 상쇄되면
		 * 그 퇴장 통보 자체가 무산되므로 입장도 알리지 않는다 — 화면엔 둘 다 보이지 않아야 한다.
		 */
		synchronized boolean add(UUID connectionId, PresenceParticipant participant, Sinks.One<Void> kicked) {
			boolean hadOthers = !connections.isEmpty();
			boolean userIsNew = !hasSubject(participant.subject());
			connections.put(connectionId, new ConnectionEntry(participant, kicked));
			if (userIsNew) {
				Disposable pendingLeave = pendingLeaves.remove(participant.subject());
				if (pendingLeave != null) {
					pendingLeave.dispose();
					return false;
				}
			}
			return hadOthers && userIsNew;
		}

		UUID generation() {
			return generation;
		}

		/** 지금 방에 있는 사용자를 입장 순서대로. 같은 사람의 연결이 여럿이어도 한 번만 센다 —
		 * 표시 이름이 달라도 subject가 같으면 한 사람이므로 먼저 들어온 쪽을 남긴다. */
		synchronized List<PresenceParticipant> participants() {
			Map<String, PresenceParticipant> bySubject = new LinkedHashMap<>();
			for (ConnectionEntry entry : connections.values()) {
				bySubject.putIfAbsent(entry.participant().subject(), entry.participant());
			}
			return List.copyOf(bySubject.values());
		}

		private boolean hasSubject(String subject) {
			return connections.values().stream()
					.anyMatch(entry -> entry.participant().subject().equals(subject));
		}

		/**
		 * 연결을 방에서 빼고 그 결과를 돌려준다. 방이 비면 sink를 종결하고 이 방을 비활성으로
		 * 표시한다 — 곧 버려질 방이고, 그 뒤 남은 세대로 들어오는 방송은 여기서 걸러야 한다.
		 */
		synchronized Departure remove(UUID connectionId) {
			ConnectionEntry removed = connections.remove(connectionId);
			if (connections.isEmpty()) {
				active = false;
				frames.tryEmitComplete();
				pendingLeaves.values().forEach(Disposable::dispose);
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
			String subject = participant.subject();
			Disposable previous = pendingLeaves.remove(subject);
			if (previous != null) {
				previous.dispose();
			}
			Disposable scheduled = Mono.delay(leaveDebounce)
					.subscribe(ignored -> confirmDeparture(threadId, subject, participant));
			pendingLeaves.put(subject, scheduled);
		}

		private synchronized void confirmDeparture(UUID threadId, String subject, PresenceParticipant participant) {
			if (pendingLeaves.remove(subject) == null || !active) {
				return;
			}
			emitPresence(new PresenceLeaveFrame(threadId, subject, participant.displayName()));
		}

		/**
		 * 그 subject가 지금 들고 있는 연결(탭이 여럿이면 전부) 각각의 kicked 신호를 완료시킨다.
		 * 실제 연결 제거는 하지 않는다 — 소켓이 닫히면 CollabWebSocketHandler가 평소처럼
		 * {@link RoomSessionRegistry#leave}를 부르며 자연히 빠진다.
		 */
		synchronized boolean evict(String subject) {
			boolean evictedAny = false;
			for (ConnectionEntry entry : connections.values()) {
				if (entry.participant().subject().equals(subject)) {
					entry.kicked().tryEmitEmpty();
					evictedAny = true;
				}
			}
			return evictedAny;
		}

		Flux<WsFrame> frames() {
			return frames.asFlux();
		}

		/**
		 * 입퇴장 통보 전용 emit. 지금 듣고 있는 구독자가 없으면 보내지 않는다 — presence는 그 순간
		 * 방에 붙어 있는 사람에게만 의미가 있고, 무엇보다 구독자가 없을 때 밀어 넣으면 warm-up
		 * 버퍼 한 칸을 차지해 뒤따라오는 첫 메시지를 밀어낸다(이슈 #102). 실패해도 던지지 않는다.
		 * 이 통보는 부가 정보이고, 특히 퇴장 경로에서 예외가 나면 세션 종료가 망가진다.
		 */
		synchronized void emitPresence(WsFrame frame) {
			if (frames.currentSubscriberCount() == 0) {
				return;
			}
			frames.tryEmitNext(frame);
		}

		synchronized boolean emitIfActive(WsFrame frame) {
			if (!active) {
				return false;
			}
			Sinks.EmitResult result = frames.tryEmitNext(frame);
			if (result.isFailure()) {
				throw new IllegalStateException("room frame emission failed: " + result);
			}
			return true;
		}

		/** kicked는 이 연결 하나만의 것이라 frames(sink 하나를 방 전체가 공유)와 섞이지 않는다. */
		private record ConnectionEntry(PresenceParticipant participant, Sinks.One<Void> kicked) {
		}
	}

}
