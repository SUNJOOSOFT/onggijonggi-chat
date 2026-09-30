package com.onggijonggi.api.chat;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Class Name : CollabAuthorizationRevoker.java
 * Description : 03·CORE 사람의 조직·직급이 바뀌거나 계정이 비활성화된 뒤, 그 사람이 들고 있던 협업방 권한을 바로 거둔다.
 *               협업방 구독을 모두 해제하고, 그 사람이 시작한 협업방 AI 턴(진행 중·대기 중)만 취소한다. 다시 구독하면
 *               최신 DB 권한으로 판정한다. 1:1(DIRECT)은 워크스페이스 소속이 아니라 소유자 계약으로 지키므로 건드리지 않는다.
 */
@Component
public class CollabAuthorizationRevoker {

	/** 비동기 인가 결과가 join/dispatch에 등록되기 전에 회수될 수 있어 subject별 세대와 등록을 직렬화한다.
	 * 동일한 subject의 다른 String 인스턴스에서도 세대가 유지되도록 key를 강하게 보유한다. */
	private final Map<String, SubjectGate> subjectGates = new HashMap<>();

	private final RoomSessionRegistry roomSessionRegistry;
	private final ThreadMessageDispatcher threadMessageDispatcher;

	public CollabAuthorizationRevoker(RoomSessionRegistry roomSessionRegistry,
			ThreadMessageDispatcher threadMessageDispatcher) {
		this.roomSessionRegistry = roomSessionRegistry;
		this.threadMessageDispatcher = threadMessageDispatcher;
	}

	public void revoke(String subject) {
		SubjectGate gate = gateFor(subject);
		synchronized (gate) {
			gate.epoch++;
			roomSessionRegistry.evictCollabSubscriptions(subject);
			threadMessageDispatcher.cancelCollabTurnsFrom(subject);
		}
	}

	long epoch(String subject) {
		SubjectGate gate = gateFor(subject);
		synchronized (gate) {
			return gate.epoch;
		}
	}

	<T> GuardedResult<T> ifCurrent(String subject, long expectedEpoch, Supplier<T> action) {
		SubjectGate gate = gateFor(subject);
		synchronized (gate) {
			if (gate.epoch != expectedEpoch) return new GuardedResult<>(false, null);
			return new GuardedResult<>(true, action.get());
		}
	}

	private SubjectGate gateFor(String subject) {
		synchronized (subjectGates) {
			return subjectGates.computeIfAbsent(subject, ignored -> new SubjectGate());
		}
	}

	record GuardedResult<T>(boolean current, T value) {
	}

	private static final class SubjectGate {
		private long epoch;
	}
}
