package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.api.authz.MemberAttributes;
import com.onggijonggi.api.authz.MemberAttributesUnavailableException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : RankedDisplayNames.java
 * Description : 03·CORE 협업방에 보일 이름. 이름(성+이름, PersonNames) 뒤에 그 사람의 직급을 붙인다(황정민 대리).
 *               팀·직급 속성(Casbin p2)이 없으면 이름만 쓴다 — 권한 기능이 꺼진 배포, 미배정자, 이름 없는 계정이 그렇다.
 *               속성을 읽을 수 없을 때(적재 전·장애)도 이름만 쓴다.
 *
 *               직급은 이름에 저장하지 않고 부를 때마다 속성에서 읽는다. 그래서 이력·명단은 다음 조회 때 바뀐 직급을
 *               보여주고, 이미 보낸 메시지도 지금 직급으로 보인다. WS 연결의 이름(말풍선·접속 중)은 연결할 때 정해져
 *               다시 연결해야 바뀐다.
 */
@Service
public class RankedDisplayNames {

	private final KeycloakAdminClient keycloakAdminClient;
	private final MemberAttributes members;

	public RankedDisplayNames(KeycloakAdminClient keycloakAdminClient, MemberAttributes members) {
		this.keycloakAdminClient = keycloakAdminClient;
		this.members = members;
	}

	/** 이미 가진 이름(토큰·검색 결과)에 직급을 붙인다. */
	public Mono<String> withRank(String subject, String name) {
		return Mono.fromCallable(() -> {
					try {
						return members.findBySubject(subject).stream().findFirst()
								.map(member -> name + " " + member.rank().label())
								.orElse(name);
					} catch (MemberAttributesUnavailableException unavailable) {
						return name;
					}
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** Keycloak에서 이름을 찾아 직급을 붙인다. 이름을 못 찾으면(탈퇴·장애) 비어 있고 직급도 붙이지 않는다. */
	public Mono<Optional<String>> displayName(String subject) {
		return keycloakAdminClient.displayName(subject)
				.flatMap(name -> name.isEmpty()
						? Mono.just(name)
						: withRank(subject, name.get()).map(Optional::of));
	}
}
