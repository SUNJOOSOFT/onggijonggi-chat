package com.onggijonggi.api.authz;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Class Name : DocumentScopeFilter.java
 * Description : AccessibleNodePaths(#324)를 문서 검색의 범위 조건으로 바꾼 값. 호출부가 셋을 다르게 다뤄야 해서 종류로
 *               구분한다 — UNRESTRICTED는 경로 조건을 걸지 않고, NONE은 검색을 건너뛰거나 결과를 비우며, PATHS는 그 경로에
 *               붙은 문서만 찾는다. nodePaths의 각 경로는 루트부터 그 노드까지의 노드 id 목록이다. 경로를 읽을 수 없거나
 *               비정상이면 NONE으로 닫는다. 검색 엔진의 필드 모양에는 묶이지 않고 노드 경로를 그대로 담는다.
 *
 *               방 문서 검색은 ThreadScopeFilter(접근 가능한 방 목록)만으로 범위를 좁히고, 이 값은 아직 실제 검색
 *               쿼리에 연결하지 않는다 — 검색 전에 방 접근이 이미 확인되어 청크 단위로 경로를 다시 거를 필요가
 *               없기 때문이다(이슈 #335). 워크스페이스 단위 자료실 검색을 만들 때 연결한다.
 */
public record DocumentScopeFilter(Kind kind, List<List<UUID>> nodePaths) {

	public enum Kind {
		UNRESTRICTED,
		NONE,
		PATHS
	}

	private static final DocumentScopeFilter UNRESTRICTED = new DocumentScopeFilter(Kind.UNRESTRICTED, List.of());
	private static final DocumentScopeFilter NONE = new DocumentScopeFilter(Kind.NONE, List.of());

	public static DocumentScopeFilter from(AccessibleNodePaths accessible) {
		if (accessible == null) return NONE;
		if (accessible.unrestricted()) return UNRESTRICTED;
		if (accessible.paths() == null) return NONE;
		List<List<UUID>> paths = accessible.paths().stream()
				.filter(path -> path != null && path.length > 0 && Arrays.stream(path).noneMatch(id -> id == null))
				.map(List::of)
				.distinct()
				.sorted(Comparator.comparing(DocumentScopeFilter::key))
				.toList();
		return paths.isEmpty() ? NONE : new DocumentScopeFilter(Kind.PATHS, paths);
	}

	/** 입력 순서와 무관하게 결과 순서를 고정하는 정렬 키. */
	private static String key(List<UUID> path) {
		return path.stream().map(UUID::toString).collect(Collectors.joining("/"));
	}
}
