package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Class Name : ThreadScopeFilterTest.java
 * Description : 접근 가능한 방 목록이 문서 검색의 방 조건으로 바뀌는 규칙을 검증한다 — 제한 없음은 조건을 안 걸고, 목록이 없거나
 *               비면 NONE으로 닫고, 있으면 중복을 없애 순서를 고정한다. 방 접근 판정(참여 행·Casbin·DB)은 쓰지 않는다 —
 *               입력 값만 만들어 넣는다.
 */
class ThreadScopeFilterTest {

	private static final UUID THREAD_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID THREAD_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID THREAD_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	@Test
	void unrestrictedAddsNoThreadCondition() {
		ThreadScopeFilter filter = ThreadScopeFilter.unrestricted();

		assertThat(filter.kind()).isEqualTo(ThreadScopeFilter.Kind.UNRESTRICTED);
		assertThat(filter.threadIds()).isEmpty();
	}

	@Test
	void emptyListMatchesNothing() {
		ThreadScopeFilter filter = ThreadScopeFilter.of(List.of());

		assertThat(filter.kind()).isEqualTo(ThreadScopeFilter.Kind.NONE);
		assertThat(filter.threadIds()).isEmpty();
	}

	@Test
	void singleThreadBecomesThreadsFilter() {
		ThreadScopeFilter filter = ThreadScopeFilter.of(List.of(THREAD_A));

		assertThat(filter.kind()).isEqualTo(ThreadScopeFilter.Kind.THREADS);
		assertThat(filter.threadIds()).containsExactly(THREAD_A);
	}

	@Test
	void multipleThreadsAreAllKept() {
		ThreadScopeFilter filter = ThreadScopeFilter.of(Set.of(THREAD_A, THREAD_B, THREAD_C));

		assertThat(filter.kind()).isEqualTo(ThreadScopeFilter.Kind.THREADS);
		assertThat(filter.threadIds()).containsExactly(THREAD_A, THREAD_B, THREAD_C);
	}

	@Test
	void duplicateThreadsAreRemoved() {
		ThreadScopeFilter filter = ThreadScopeFilter.of(List.of(THREAD_A, THREAD_A, THREAD_B));

		assertThat(filter.threadIds()).containsExactly(THREAD_A, THREAD_B);
	}

	@Test
	void orderDoesNotDependOnInputOrder() {
		ThreadScopeFilter forward = ThreadScopeFilter.of(List.of(THREAD_A, THREAD_B, THREAD_C));
		ThreadScopeFilter backward = ThreadScopeFilter.of(List.of(THREAD_C, THREAD_B, THREAD_A));

		assertThat(forward).isEqualTo(backward);
	}

	@Test
	void unreadableInputClosesToNone() {
		assertThat(ThreadScopeFilter.of(null).kind()).isEqualTo(ThreadScopeFilter.Kind.NONE);
	}

	@Test
	void nullElementsAreDroppedAndNothingLeftMeansNone() {
		List<UUID> onlyNull = new ArrayList<>();
		onlyNull.add(null);

		assertThat(ThreadScopeFilter.of(onlyNull).kind()).isEqualTo(ThreadScopeFilter.Kind.NONE);

		List<UUID> mixed = new ArrayList<>(onlyNull);
		mixed.add(THREAD_A);
		ThreadScopeFilter filter = ThreadScopeFilter.of(mixed);

		assertThat(filter.kind()).isEqualTo(ThreadScopeFilter.Kind.THREADS);
		assertThat(filter.threadIds()).containsExactly(THREAD_A);
	}

	@Test
	void resultIsImmutableAndIndependentOfInputList() {
		List<UUID> source = new ArrayList<>(List.of(THREAD_A, THREAD_B));
		ThreadScopeFilter filter = ThreadScopeFilter.of(source);

		source.set(1, THREAD_C);
		source.add(THREAD_C);

		assertThat(filter.threadIds()).containsExactly(THREAD_A, THREAD_B);
		assertThatThrownBy(() -> filter.threadIds().add(THREAD_C)).isInstanceOf(UnsupportedOperationException.class);
	}
}
