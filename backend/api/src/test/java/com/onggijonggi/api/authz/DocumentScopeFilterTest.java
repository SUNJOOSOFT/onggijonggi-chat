package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Class Name : DocumentScopeFilterTest.java
 * Description : AccessibleNodePaths가 문서 검색 범위 조건으로 바뀌는 규칙을 검증한다 — 제한 없음은 조건을 안 걸고,
 *               볼 수 있는 노드가 없거나 경로를 읽을 수 없으면 NONE으로 닫고, 경로가 있으면 중복을 없애 순서를 고정한다.
 *               판정 구현(Casbin·DB)은 쓰지 않는다 — 입력 값만 만들어 넣는다.
 */
class DocumentScopeFilterTest {

	private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID HR = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID HR_LEAD = UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final UUID FIN = UUID.fromString("00000000-0000-0000-0000-000000000004");

	@Test
	void unrestrictedAddsNoPathCondition() {
		DocumentScopeFilter filter = DocumentScopeFilter.from(AccessibleNodePaths.noRestriction());

		assertThat(filter.kind()).isEqualTo(DocumentScopeFilter.Kind.UNRESTRICTED);
		assertThat(filter.nodePaths()).isEmpty();
	}

	@Test
	void emptyRestrictedListMatchesNothing() {
		DocumentScopeFilter filter = DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(List.of()));

		assertThat(filter.kind()).isEqualTo(DocumentScopeFilter.Kind.NONE);
		assertThat(filter.nodePaths()).isEmpty();
	}

	@Test
	void singlePathBecomesPathsFilter() {
		DocumentScopeFilter filter = DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(List.<UUID[]>of(new UUID[] {ROOT, HR})));

		assertThat(filter.kind()).isEqualTo(DocumentScopeFilter.Kind.PATHS);
		assertThat(filter.nodePaths()).containsExactly(List.of(ROOT, HR));
	}

	@Test
	void multiplePathsAreAllKept() {
		DocumentScopeFilter filter = DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(
				List.of(new UUID[] {ROOT, HR}, new UUID[] {ROOT, HR, HR_LEAD}, new UUID[] {ROOT, FIN})));

		assertThat(filter.kind()).isEqualTo(DocumentScopeFilter.Kind.PATHS);
		assertThat(filter.nodePaths()).containsExactlyInAnyOrder(List.of(ROOT, HR), List.of(ROOT, HR, HR_LEAD), List.of(ROOT, FIN));
	}

	@Test
	void duplicatePathsAreRemovedByContent() {
		DocumentScopeFilter filter = DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(
				List.of(new UUID[] {ROOT, HR}, new UUID[] {ROOT, HR})));

		assertThat(filter.nodePaths()).containsExactly(List.of(ROOT, HR));
	}

	@Test
	void orderDoesNotDependOnInputOrder() {
		DocumentScopeFilter forward = DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(
				List.of(new UUID[] {ROOT, HR}, new UUID[] {ROOT, FIN})));
		DocumentScopeFilter backward = DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(
				List.of(new UUID[] {ROOT, FIN}, new UUID[] {ROOT, HR})));

		assertThat(forward).isEqualTo(backward);
	}

	@Test
	void unreadableOrMalformedInputClosesToNone() {
		assertThat(DocumentScopeFilter.from(null).kind()).isEqualTo(DocumentScopeFilter.Kind.NONE);
		assertThat(DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(null)).kind()).isEqualTo(DocumentScopeFilter.Kind.NONE);
	}

	@Test
	void malformedPathsAreDroppedAndNothingLeftMeansNone() {
		List<UUID[]> onlyBad = new ArrayList<>();
		onlyBad.add(null);
		onlyBad.add(new UUID[0]);
		onlyBad.add(new UUID[] {ROOT, null});

		assertThat(DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(onlyBad)).kind())
				.isEqualTo(DocumentScopeFilter.Kind.NONE);

		List<UUID[]> mixed = new ArrayList<>(onlyBad);
		mixed.add(new UUID[] {ROOT, HR});
		DocumentScopeFilter filter = DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(mixed));

		assertThat(filter.kind()).isEqualTo(DocumentScopeFilter.Kind.PATHS);
		assertThat(filter.nodePaths()).containsExactly(List.of(ROOT, HR));
	}

	@Test
	void resultIsImmutableAndIndependentOfInputArrays() {
		UUID[] source = new UUID[] {ROOT, HR};
		DocumentScopeFilter filter = DocumentScopeFilter.from(AccessibleNodePaths.restrictedTo(List.<UUID[]>of(source)));

		source[1] = FIN;

		assertThat(filter.nodePaths()).containsExactly(List.of(ROOT, HR));
		assertThatThrownBy(() -> filter.nodePaths().add(List.of(ROOT))).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> filter.nodePaths().get(0).add(FIN)).isInstanceOf(UnsupportedOperationException.class);
	}
}
