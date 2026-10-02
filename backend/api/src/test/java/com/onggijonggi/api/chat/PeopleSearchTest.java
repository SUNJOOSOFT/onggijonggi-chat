package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.authz.MemberAttribute;
import com.onggijonggi.api.authz.MemberAttributes;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakUserSummary;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Class Name : PeopleSearchTest.java
 * Description : 초대 후보 검색어를 이름·직급·팀으로 가르고, 이름이 없으면 배정에서, 있으면 Keycloak에서 찾아 직급·팀으로
 *               거르는지 검증한다.
 */
class PeopleSearchTest {

	private static final UUID TENANT = UUID.randomUUID();

	private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
	private final MemberAttributes members = mock(MemberAttributes.class);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final PeopleSearch search = new PeopleSearch(keycloak, members, orgUnits);

	private OrgUnit hr;
	private OrgUnit legal;

	@BeforeEach
	void setUp() {
		hr = new OrgUnit(TENANT, "hr", "인사팀", OrgUnitStatus.ACTIVE);
		legal = new OrgUnit(TENANT, "legal", "법무팀", OrgUnitStatus.ACTIVE);
		when(orgUnits.findAll()).thenReturn(List.of(hr, legal));
		when(members.findAll()).thenReturn(List.of(
				new MemberAttribute("sub-song", TENANT, hr.getId(), Rank.TL),
				new MemberAttribute("sub-hwang", TENANT, hr.getId(), Rank.D),
				new MemberAttribute("sub-yoon", TENANT, legal.getId(), Rank.D)));
		name("sub-song", "송강호");
		name("sub-hwang", "황정민");
		name("sub-yoon", "윤여정");
	}

	/** 초대 검색은 Keycloak 관리 연결 설정 문제(#326)를 "그런 사람 없음"으로 바꾸지 않고 그대로 올린다. */
	@Test
	void aKeycloakConfigurationProblemIsNotAnEmptyResult() {
		when(keycloak.search(any(), anyInt())).thenReturn(Mono.error(new com.onggijonggi.api.auth.keycloak
				.KeycloakAdminUnavailableException(com.onggijonggi.api.auth.keycloak.KeycloakAdminUnavailableException.Reason.FORBIDDEN,
						403, "/admin/realms/app-realm/users")));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> search.search("송강호", 10).collectList().block())
				.isInstanceOf(com.onggijonggi.api.auth.keycloak.KeycloakAdminUnavailableException.class);
	}

	@Test
	void rankAloneFindsEveryoneWithThatRank() {
		assertThat(subjects("대리")).containsExactlyInAnyOrder("sub-hwang", "sub-yoon");
		verify(keycloak, never()).search(any(), anyInt());
	}

	@Test
	void teamAloneFindsEveryoneInThatTeam() {
		assertThat(subjects("인사팀")).containsExactlyInAnyOrder("sub-song", "sub-hwang");
	}

	@Test
	void teamAndRankTogetherNarrowBoth() {
		assertThat(subjects("인사팀 대리")).containsExactly("sub-hwang");
	}

	/** 이름이 있으면 Keycloak에서 이름으로 찾고, 붙은 직급으로 거른다. */
	@Test
	void nameWithRankSearchesByNameThenFiltersByRank() {
		when(keycloak.search("정민", 20)).thenReturn(Mono.just(List.of(
				new KeycloakUserSummary("sub-hwang", "황정민"),
				new KeycloakUserSummary("sub-other", "김정민"))));

		assertThat(search.search("정민 대리", 20).collectList().block())
				.containsExactly(new KeycloakUserSummary("sub-hwang", "황정민"));
	}

	/** 직급·팀 이름과 정확히 같지 않으면 이름으로 본다 — "대"는 대리가 아니다. */
	@Test
	void partialRankIsTreatedAsAName() {
		when(keycloak.search("대", 20)).thenReturn(Mono.just(List.of()));

		assertThat(search.criteriaOf("대")).isEqualTo(new PeopleSearch.Criteria("대", null, null));
		assertThat(subjects("대")).isEmpty();
	}

	/** 띄어 쓴 이름은 붙여서 찾는다 — 화면의 이름이 붙여 쓴 성+이름이다. */
	@Test
	void spacedNameIsJoined() {
		assertThat(search.criteriaOf("황 정민 대리")).isEqualTo(new PeopleSearch.Criteria("황정민", Rank.D, null));
	}

	/** 이름을 못 찾는 사람(탈퇴 등)은 후보에서 뺀다. */
	@Test
	void assignedPeopleWithoutANameAreLeftOut() {
		when(keycloak.displayName("sub-yoon")).thenReturn(Mono.just(Optional.empty()));

		assertThat(subjects("대리")).containsExactly("sub-hwang");
	}

	private void name(String subject, String name) {
		when(keycloak.displayName(subject)).thenReturn(Mono.just(Optional.of(name)));
	}

	private List<String> subjects(String query) {
		return search.search(query, 20).map(KeycloakUserSummary::subject).collectList().block();
	}
}
