package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.auth.keycloak.KeycloakUserSummary;
import com.onggijonggi.api.authz.MemberAttribute;
import com.onggijonggi.api.authz.MemberAttributes;
import com.onggijonggi.api.authz.MemberAttributesUnavailableException;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : PeopleSearch.java
 * Description : 03·CORE 초대 후보를 이름·직급·팀으로 찾는다. 검색어를 띄어쓰기로 나눠 단어마다 직급 이름(대리 등)인지,
 *               팀 이름(인사팀 등)인지 정확히 맞춰 보고, 어느 쪽도 아닌 단어는 이름으로 본다.
 *               <ul>
 *               <li>이름이 있으면 Keycloak에서 이름으로 찾고(KeycloakAdminClient.search), 직급·팀이 있으면 그 배정으로 거른다.</li>
 *               <li>이름이 없으면 사람 속성(Casbin p2)에서 그 직급·팀인 사람을 모두 찾아 Keycloak에서 이름만 붙인다.</li>
 *               </ul>
 *               직급·팀은 Keycloak에 없고 Casbin에만 있어서 Keycloak 검색만으로는 찾을 수 없다. 직급·팀 조건이 있는데 속성을
 *               읽을 수 없으면(적재 전·장애) 503이다. 직급·팀은 정확히 같을
 *               때만 조건으로 본다 — "과"를 과장으로 보면 이름 검색과 섞인다.
 *
 *               결과는 게으른 Flux다. 호출부가 참여 여부·워크스페이스로 거른 뒤 개수를 자르므로, 이름 조회는 실제로
 *               쓰이는 사람까지만 나간다. 속성은 조직 규모(수백 명)라 한 번에 읽어 메모리에서 거른다.
 */
@Service
public class PeopleSearch {

	private final KeycloakAdminClient keycloakAdminClient;
	private final MemberAttributes members;
	private final OrgUnitRepository orgUnits;

	public PeopleSearch(KeycloakAdminClient keycloakAdminClient, MemberAttributes members,
			OrgUnitRepository orgUnits) {
		this.keycloakAdminClient = keycloakAdminClient;
		this.members = members;
		this.orgUnits = orgUnits;
	}

	/** 검색어를 단어별로 가른 결과. rank·team이 null이면 그 조건이 없다. */
	record Criteria(String name, Rank rank, UUID team) {
	}

	/**
	* @param keycloakMax 이름으로 Keycloak에 물을 때의 상한
	*/
	public Flux<KeycloakUserSummary> search(String query, int keycloakMax) {
		return Mono.fromCallable(() -> criteriaOf(query))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(criteria -> criteria.name().isEmpty()
						? byAssignment(criteria)
						: byName(criteria, keycloakMax));
	}

	Criteria criteriaOf(String query) {
		List<OrgUnit> activeTeams = orgUnits.findAll().stream()
				.filter(unit -> unit.getStatus() == OrgUnitStatus.ACTIVE)
				.toList();
		Rank rank = null;
		UUID team = null;
		List<String> nameWords = new ArrayList<>();
		for (String word : query.trim().split("\\s+")) {
			Optional<Rank> asRank = Arrays.stream(Rank.values()).filter(value -> value.label().equals(word)).findFirst();
			Optional<OrgUnit> asTeam = activeTeams.stream().filter(unit -> unit.getName().equals(word)).findFirst();
			if (rank == null && asRank.isPresent()) {
				rank = asRank.get();
			} else if (team == null && asTeam.isPresent()) {
				team = asTeam.get().getId();
			} else {
				nameWords.add(word);
			}
		}
		// "황 정민"처럼 띄어 쓴 이름도 붙여서 찾는다 — 화면의 이름이 붙여 쓴 성+이름이다.
		return new Criteria(String.join("", nameWords), rank, team);
	}

	private Flux<KeycloakUserSummary> byName(Criteria criteria, int keycloakMax) {
		Flux<KeycloakUserSummary> found = keycloakAdminClient.search(criteria.name(), keycloakMax)
				.flatMapMany(Flux::fromIterable);
		if (criteria.rank() == null && criteria.team() == null) {
			return found;
		}
		return Mono.fromCallable(() -> matchingSubjects(criteria))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(subjects -> found.filter(user -> subjects.contains(user.subject())));
	}

	private Flux<KeycloakUserSummary> byAssignment(Criteria criteria) {
		return Mono.fromCallable(() -> List.copyOf(matchingSubjects(criteria)))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(Flux::fromIterable)
				// 이름을 못 찾는 사람(탈퇴·장애)은 후보에서 뺀다 — 이름 없는 후보는 고를 수가 없다.
				.concatMap(subject -> keycloakAdminClient.displayName(subject)
						.flatMap(name -> Mono.justOrEmpty(name.map(value -> new KeycloakUserSummary(subject, value)))));
	}

	private Set<String> matchingSubjects(Criteria criteria) {
		List<MemberAttribute> all;
		try {
			all = members.findAll();
		} catch (MemberAttributesUnavailableException unavailable) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		}
		return all.stream()
				.filter(member -> criteria.rank() == null || member.rank() == criteria.rank())
				.filter(member -> criteria.team() == null || member.orgUnitId().equals(criteria.team()))
				.map(MemberAttribute::subject)
				.collect(Collectors.toCollection(java.util.LinkedHashSet::new));
	}
}
