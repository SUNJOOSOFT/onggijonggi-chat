package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.chat.RoomSessionRegistry;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Class Name : RbacPolicyRefreshTest.java
 * Description : 커밋 뒤 Casbin 반영이 실패하면 그 Tenant만 막고 재시도로 풀리는지, 구독 해제는 판정이 켜졌을 때만 하는지 검증한다.
 */
class RbacPolicyRefreshTest {
	private final CasbinRuleLoader loader = mock(CasbinRuleLoader.class);
	private final ThrRepository threads = mock(ThrRepository.class);
	private final RoomSessionRegistry rooms = mock(RoomSessionRegistry.class);
	private final RbacProperties properties = new RbacProperties();
	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
	private final Clock clock = new Clock() {
		@Override public ZoneId getZone() { return ZoneId.of("UTC"); }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now.get(); }
	};
	private final RbacPolicyRefresh refresh = new RbacPolicyRefresh(loader, properties, threads, rooms, clock);

	@Test
	void reloadFailureBlocksOnlyChangedTenantUntilRetrySucceeds() {
		UUID affected = UUID.randomUUID();
		UUID unrelated = UUID.randomUUID();
		doThrow(new IllegalStateException("offline")).doNothing().when(loader).reload();
		refresh.publish(affected, Set.of(), null);
		assertThat(refresh.isBlocked(affected)).isTrue();
		assertThat(refresh.isBlocked(unrelated)).isFalse();
		refresh.retryFailed();
		assertThat(refresh.isBlocked(affected)).isTrue();
		now.set(now.get().plusSeconds(1));
		refresh.retryFailed();
		assertThat(refresh.isBlocked(affected)).isFalse();
	}

	@Test
	void cutoverSwitchControlsRoomEvictionButNotPolicyReload() {
		UUID tenantId = UUID.randomUUID();
		UUID nodeId = UUID.randomUUID();
		UUID roomId = UUID.randomUUID();
		Thr thread = Thr.collab(UUID.randomUUID(), "room");
		when(threads.findByWorkspaceNodeIdAndKind(nodeId, ThrKind.COLLAB)).thenReturn(List.of(thread));
		refresh.publish(tenantId, Set.of(nodeId), null);
		verify(loader).reload();
		// 절체 전에는 어떤 방도 끊지 않는다 — 특정 ID가 아니라 모든 호출이 없어야 한다.
		verify(rooms, never()).evictCollabRoom(any());
		properties.setEnforce(true);
		refresh.publish(tenantId, Set.of(nodeId), null);
		verify(rooms).evictCollabRoom(thread.getId());
	}
}
