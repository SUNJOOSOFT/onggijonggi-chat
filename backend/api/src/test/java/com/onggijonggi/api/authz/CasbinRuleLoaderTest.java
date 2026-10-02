package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.chat.CollabAuthorizationRevoker;
import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.RankGrantRepository;
import com.onggijonggi.common.authz.WorkspaceGrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Class Name : CasbinRuleLoaderTest.java
 * Description : 적재할 때마다 사람 속성을 다시 읽고, 직전 적재와 비교해 속성이 바뀌었거나 빠진 사람만 협업방 권한을 거두는지,
 *               기동 뒤 첫 적재와 실패한 적재는 거두지 않는지, 실패 뒤에는 잠시 다시 시도하지 않는지, casbin 프로필이 꺼져
 *               있으면 아무것도 하지 않는지 확인한다. casbin-server와의 실제 적재는 CasbinServerContainerTest가 본다.
 */
class CasbinRuleLoaderTest {

	private static final UUID TENANT = UUID.randomUUID();
	private static final UUID HR = UUID.randomUUID();
	private static final UUID FIN = UUID.randomUUID();

	private final CasbinProperties properties = new CasbinProperties();
	private final CasbinClient client = mock(CasbinClient.class);
	private final WorkspaceGrantRepository workspaceGrants = mock(WorkspaceGrantRepository.class);
	private final RankGrantRepository rankGrants = mock(RankGrantRepository.class);
	private final CollabAuthorizationRevoker revoker = mock(CollabAuthorizationRevoker.class);
	private final AtomicReference<List<MemberAttribute>> file = new AtomicReference<>(List.of());
	private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T00:00:00Z"));
	private CasbinRuleLoader loader;

	@BeforeEach
	void setUp() {
		properties.setAddress("casbin:50051");
		when(workspaceGrants.findAll()).thenReturn(List.of());
		when(rankGrants.findAll()).thenReturn(List.of());
		MemberAttributeSource source = () -> {
			if (failure.get() != null) throw failure.get();
			return new MemberAttributeSource.Load(file.get(), List.of(), "hash");
		};
		@SuppressWarnings("unchecked")
		ObjectProvider<CollabAuthorizationRevoker> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(revoker);
		Clock clock = new Clock() {
			@Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return now.get(); }
		};
		loader = new CasbinRuleLoader(properties, client, workspaceGrants, rankGrants, source, provider, clock);
	}

	@Test
	void theFirstLoadPutsMembersInAndRevokesNobody() {
		file.set(List.of(kim(HR, Rank.K)));

		loader.reload();

		verify(client).load(anyString(), anyList(), eq(List.of(kim(HR, Rank.K))));
		verifyNoInteractions(revoker);
	}

	@Test
	void aReloadRevokesOnlyPeopleWhoseAttributesChangedOrWhoLeft() {
		file.set(List.of(kim(HR, Rank.K), member("sub-lee", HR, Rank.S), member("sub-park", FIN, Rank.B)));
		loader.reload();

		// 김: 재무팀으로 이동, 이: 그대로, 박: 파일에서 빠짐, 최: 새로 들어옴.
		file.set(List.of(kim(FIN, Rank.K), member("sub-lee", HR, Rank.S), member("sub-choi", FIN, Rank.S)));
		loader.reload();

		verify(revoker).revoke("sub-kim");
		verify(revoker).revoke("sub-park");
		verify(revoker, never()).revoke("sub-lee");
		verify(revoker, never()).revoke("sub-choi");
	}

	@Test
	void onlyARankChangeAlsoRevokes() {
		file.set(List.of(kim(HR, Rank.K)));
		loader.reload();
		file.set(List.of(kim(HR, Rank.D)));
		loader.reload();

		verify(revoker).revoke("sub-kim");
	}

	@Test
	void aFailedReloadKeepsThePreviousStateAndRevokesNobody() {
		file.set(List.of(kim(HR, Rank.K)));
		loader.reload();
		failure.set(new MemberAttributeSourceException(List.of("2번째 줄: 없는 팀이다: nowhere")));

		assertThatThrownBy(loader::reload).isInstanceOf(MemberAttributeSourceException.class);
		verifyNoInteractions(revoker);

		// 고친 뒤에는 실패 전 상태와 비교한다 — 실패한 적재가 비교 기준을 지우지 않는다.
		failure.set(null);
		file.set(List.of(kim(FIN, Rank.K)));
		loader.reload();
		verify(revoker).revoke("sub-kim");
	}

	@Test
	void aRevokeFailureDoesNotFailTheLoad() {
		file.set(List.of(kim(HR, Rank.K)));
		loader.reload();
		doThrow(new IllegalStateException("registry")).when(revoker).revoke("sub-kim");
		file.set(List.of());

		loader.reload();

		verify(client, times(2)).load(anyString(), anyList(), anyList());
	}

	@Test
	void afterAFailedLoadDecisionsDoNotRetryForAWhile() {
		failure.set(new IllegalStateException("keycloak down"));

		loader.ensureLoaded();
		loader.ensureLoaded();
		verify(client, never()).load(anyString(), anyList(), anyList());

		failure.set(null);
		loader.ensureLoaded();
		verify(client, never()).load(anyString(), anyList(), anyList());

		now.set(now.get().plus(CasbinRuleLoader.RETRY_AFTER_FAILURE));
		loader.ensureLoaded();
		verify(client).load(anyString(), anyList(), anyList());
	}

	@Test
	void withoutTheCasbinProfileNothingIsLoaded() {
		properties.setAddress("");

		loader.ensureLoaded();

		assertThat(loader.isActive()).isFalse();
		verify(client, never()).load(any(), any(), any());
	}

	private static MemberAttribute kim(UUID team, Rank rank) {
		return member("sub-kim", team, rank);
	}

	private static MemberAttribute member(String subject, UUID team, Rank rank) {
		return new MemberAttribute(subject, TENANT, team, rank);
	}
}
