package com.onggijonggi.api.authz;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Class Name : ThreadScopeFilter.java
 * Description : 방에 고정한 문서를 그 방의 참여자에게만 보이게 하는 검색 범위 조건. DocumentScopeFilter(노드 경로)와 함께
 *               걸어 둘 다 통과한 문서만 찾는다 — 서로 대체재가 아니다. UNRESTRICTED는 방 조건을 걸지 않고, NONE은
 *               검색을 건너뛰거나 결과를 비우며, THREADS는 그 방에 붙은 문서만 찾는다. 어떤 방이 접근 가능한지는 이 값을 만드는
 *               쪽이 정한다 — 이 클래스는 받은 목록을 정규화만 한다. 목록을 읽을 수 없거나 비정상이면 NONE으로 닫는다.
 *               검색 엔진의 필드 모양에는 묶이지 않고 thread id를 그대로 담는다.
 */
public record ThreadScopeFilter(Kind kind, List<UUID> threadIds) {

	public enum Kind {
		UNRESTRICTED,
		NONE,
		THREADS
	}

	private static final ThreadScopeFilter UNRESTRICTED = new ThreadScopeFilter(Kind.UNRESTRICTED, List.of());
	private static final ThreadScopeFilter NONE = new ThreadScopeFilter(Kind.NONE, List.of());

	/** 방 조건을 걸지 않는다. 호출부가 방 단위 제한이 필요 없다고 판단한 경우에만 쓴다. */
	public static ThreadScopeFilter unrestricted() {
		return UNRESTRICTED;
	}

	/** 목록이 없거나 쓸 수 있는 방이 하나도 없으면 UNRESTRICTED가 아니라 NONE으로 닫는다 — 읽지 못한 목록이 전체 공개로 번지지 않게 한다. */
	public static ThreadScopeFilter of(Collection<UUID> accessibleThreadIds) {
		if (accessibleThreadIds == null) return NONE;
		List<UUID> ids = accessibleThreadIds.stream()
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.toList();
		return ids.isEmpty() ? NONE : new ThreadScopeFilter(Kind.THREADS, ids);
	}
}
