package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.Rank;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Class Name : RankedDisplayNamesTest.java
 * Description : 협업방 표시 이름에 직급이 붙는 규칙을 검증한다 — 배정이 있으면 "이름 직급", 없으면 이름만, 이름을 못
 *               찾으면 직급도 붙이지 않는다.
 */
class RankedDisplayNamesTest {

	private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final RankedDisplayNames names = new RankedDisplayNames(keycloak, members);

	@Test
	void appendsTheRankLabelWhenTheSubjectIsAssigned() {
		when(members.findBySubject("sub-hwang")).thenReturn(List.of(assignment("sub-hwang", Rank.D)));

		assertThat(names.withRank("sub-hwang", "황정민").block()).isEqualTo("황정민 대리");
	}

	@Test
	void keepsTheNameAloneWhenUnassigned() {
		when(members.findBySubject("sub-ma")).thenReturn(List.of());

		assertThat(names.withRank("sub-ma", "마동석").block()).isEqualTo("마동석");
	}

	@Test
	void looksUpTheNameInKeycloakThenAppendsTheRank() {
		when(keycloak.displayName("sub-song")).thenReturn(Mono.just(Optional.of("송강호")));
		when(members.findBySubject("sub-song")).thenReturn(List.of(assignment("sub-song", Rank.TL)));

		assertThat(names.displayName("sub-song").block()).contains("송강호 팀장");
	}

	/** 탈퇴·장애로 이름을 못 찾으면 비어 있다 — 직급만 덩그러니 붙은 이름을 만들지 않는다. */
	@Test
	void staysEmptyWithoutLookingUpTheRankWhenTheNameIsMissing() {
		when(keycloak.displayName("sub-gone")).thenReturn(Mono.just(Optional.empty()));

		assertThat(names.displayName("sub-gone").block()).isEmpty();
		verifyNoInteractions(members);
	}

	private static OrgUnitMember assignment(String subject, Rank rank) {
		return new OrgUnitMember(UUID.randomUUID(), UUID.randomUUID(), subject, rank);
	}
}
