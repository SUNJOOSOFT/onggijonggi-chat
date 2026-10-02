package com.onggijonggi.api.auth.keycloak;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.onggijonggi.api.auth.PersonNames;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Class Name : KeycloakAdminClient.java
 * Description : 다른 사용자의 표시 이름 조회(이슈 #128)와 실존 확인(이슈 #127)을 Keycloak Admin
 *               API로 한다. 요청의 JWT는
 *               호출자 본인의 claim만 담고 있어, 협업 스레드의 다른 참가자 이름은 이 경로로만 얻을 수
 *               있다. BFF 전용 관리 클라이언트의 서비스 계정(client_credentials)으로 admin 토큰을 받고, 만료
 *               30초 전까지는 재사용한다(#326). 로그인 클라이언트는 secret이 프론트에도 있어 관리 권한을 붙이지 않는다.
 *               다시 시도해도 풀리지 않는 실패(토큰 발급 거부, Admin API 403)는 KeycloakAdminUnavailableException으로
 *               바꿔 화면이 설정 문제로 안내하게 한다.
 *
 *               표시 이름도 짧은 TTL로 캐시한다(이슈 #200). 호출부(협업 스레드 목록·참여자 관리·
 *               메시지 이력)가 화면을 그릴 때마다 사람 수만큼 Admin API를 두드리던 것을 줄이기
 *               위함이다. 정본은 그대로 Keycloak이고 DB에 미러하지 않는다 — TTL이 지나면 다시
 *               묻는다.
 */
@Component
public class KeycloakAdminClient {

	private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

	private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(30);

	/** 설정 문제 분류(rejectConfigurationFailure)가 보는 경로 표지. 요청 주소를 바꾸면 여기도 맞춘다. */
	private static final String TOKEN_PATH_SUFFIX = "/protocol/openid-connect/token";
	private static final String ADMIN_PATH_MARKER = "/admin/realms/";

	/**
	 * 설정 문제로 거부된 토큰 요청을 기억하는 시간. 그동안은 Keycloak에 다시 묻지 않고 같은 예외를 낸다 — 이름 목록처럼 한 화면이
	 * 사람 수만큼 부르면 요청마다 거부되는 토큰 요청과 Keycloak 로그인 오류 이벤트가 쌓인다. 설정을 고치면 BFF를 다시 만들므로
	 * 그때 사라진다.
	 */
	private static final Duration REJECTION_MEMORY = Duration.ofSeconds(30);

	/**
	* 캐시가 무한히 자라지 않게 하는 상한. realm의 사용자 수가 자연스러운 경계지만, 그 수를
	* 신뢰해 상한을 두지 않으면 오래 뜬 서버에서 조용히 새는 자리가 된다. 넘치면 만료된 것을
	* 먼저 버리고, 그래도 넘치면 통째로 비운다 — 비워도 다음 조회가 다시 채우므로 안전하다.
	*/
	private static final int MAX_CACHED_NAMES = 10_000;

	private final WebClient webClient;
	private final String realm;
	private final String clientId;
	private final String clientSecret;
	private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
	private final AtomicReference<RememberedRejection> rejectedToken = new AtomicReference<>();

	private final Map<String, CachedName> cachedNames = new ConcurrentHashMap<>();

	private final Duration displayNameTtl;

	/** 표시 이름이 설정 문제로 비고 있음을 이미 경고했는지. 같은 상태에서는 다시 남기지 않고, 조회가 성공하면 풀린다. */
	private final AtomicBoolean displayNameProblemLogged = new AtomicBoolean();

	public KeycloakAdminClient(WebClient.Builder webClientBuilder,
			@Value("${app.keycloak.internal-url}") String internalUrl,
			@Value("${app.keycloak.realm}") String realm,
			@Value("${app.keycloak.admin.client-id}") String clientId,
			@Value("${app.keycloak.admin.client-secret}") String clientSecret,
			@Value("${app.keycloak.admin.display-name-ttl:5m}") Duration displayNameTtl) {
		// admin event 상세처럼 큰 응답이 기본 한도(256KB)에 걸려 한 건이 수집 전체를 막지 않게 넉넉히 둔다.
		this.webClient = webClientBuilder.baseUrl(internalUrl)
				.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
				.filter(configurationFailureFilter())
				.build();
		this.realm = realm;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.displayNameTtl = displayNameTtl;
	}

	/**
	 * subject(AppUser.keycloakSubj, JWT sub 클레임과 같은 값)로 표시 이름을 조회한다. app_user.id(내부
	 * UUID)와는 다른 값이라 호출부가 미리 keycloakSubj로 바꿔서 넘겨야 한다. 탈퇴로 못 찾은 경우뿐
	 * 아니라 토큰 발급 실패·타임아웃·5xx 등 Admin API 쪽 오류 전부를 빈 Optional로 삼킨다 — 표시
	 * 이름 하나 못 가져온 것 때문에 호출부의 스레드 목록 조회 전체가 죽으면 안 된다.
	 *
	 * 결과는 displayNameTtl 동안 캐시한다(이슈 #200). 다만 <b>답이 확정된 것만</b> 담는다 —
	 * 조회 성공과 404(탈퇴 등 그 subject가 없음)는 다시 물어도 답이 같으므로 캐시하고, 토큰 발급
	 * 실패·타임아웃·5xx는 캐시하지 않는다. 장애까지 캐시하면 Keycloak이 잠깐 흔들린 것이 TTL 내내
	 * "이름 없음"으로 굳는다.
	 */
	public Mono<Optional<String>> displayName(String subject) {
		CachedName cached = cachedNames.get(subject);
		if (cached != null && cached.isValidAt(Instant.now())) {
			return Mono.just(cached.value());
		}
		return adminToken()
				.flatMap(token -> lookupUser(subject, token))
				.doOnNext(name -> {
					cacheName(subject, name);
					displayNameProblemLogged.set(false);
				})
				.onErrorResume(WebClientResponseException.NotFound.class, ignored -> {
					// 탈퇴처럼 확정적인 "없음"은 캐시해도 된다 — 장애와 달리 다시 물어도 답이 같고,
					// 캐시하지 않으면 떠난 사람의 옛 메시지가 볼 때마다 조회를 한 번씩 더 부른다.
					cacheName(subject, Optional.empty());
					return Mono.just(Optional.empty());
				})
				.onErrorResume(WebClientException.class, ignored -> Mono.just(Optional.empty()))
				.onErrorResume(KeycloakAdminUnavailableException.class, problem -> {
					// 화면 하나를 깨지 않으려 빈 값으로 삼키되, 이름이 모두 비는 원인을 찾을 수 있게 상태가 바뀔 때 한 번 남긴다.
					if (displayNameProblemLogged.compareAndSet(false, true)) {
						log.warn("표시 이름을 조회하지 못해 빈 이름으로 보인다: {}", problem.summary());
					}
					return Mono.just(Optional.empty());
				});
	}

	private void cacheName(String subject, Optional<String> name) {
		if (cachedNames.size() >= MAX_CACHED_NAMES) {
			Instant now = Instant.now();
			cachedNames.values().removeIf(entry -> !entry.isValidAt(now));
			if (cachedNames.size() >= MAX_CACHED_NAMES) {
				cachedNames.clear();
			}
		}
		cachedNames.put(subject, new CachedName(name, Instant.now().plus(displayNameTtl)));
	}

	/**
	 * subject가 Keycloak에 실재하는 계정인지 확인한다(이슈 #127의 초대 검증).
	 *
	 * displayName()과 달리 <b>오류를 삼키지 않는다.</b> 표시 이름은 못 가져와도 화면에 이름 하나
	 * 안 뜨고 말지만, 실존 검증에서 오류를 "없는 계정"으로 뭉뚱그리면 Admin API가 잠깐 흔들릴 때
	 * 정상 초대가 거부된다. 404만 "없음"(false)이고 나머지 오류는 그대로 전파해 호출부가 5xx로
	 * 답하게 한다 — 초대자가 다시 시도할 수 있어야 한다.
	 *
	 * 404를 삼키는 자리가 유저 조회 <b>안쪽</b>인 것이 중요하다. 체인 전체에 걸면 토큰
	 * 엔드포인트의 404(realm 오설정·Keycloak 라우팅 변경)까지 "계정 없음"으로 둔갑해, 위에서
	 * 막으려던 바로 그 일이 벌어진다.
	 *
	 * 실존 판정은 본문이 아니라 상태 코드로 한다 — 응답 본문에 기대면 본문이 비어 오는 경우
	 * 빈 Mono가 되어, 호출부의 flatMap이 아예 실행되지 않는다(초대 행 없이 204).
	 */
	public Mono<Boolean> exists(String subject) {
		return adminToken()
				.flatMap(token -> webClient.get()
						.uri("/admin/realms/{realm}/users/{id}", realm, subject)
						.headers(headers -> headers.setBearerAuth(token))
						.retrieve()
						.toBodilessEntity()
						.thenReturn(true)
						.onErrorResume(WebClientResponseException.NotFound.class,
								ignored -> Mono.just(false)));
	}

	/**
	 * realm의 사람 계정 목록(권한 관리 화면). 서비스 계정(service-account-*)은 사람이 아니라 뺀다.
	 * 이름은 한국식으로 성+이름을 붙이고, 둘 다 없으면 username을 쓴다. 오류는 전파한다.
	 *
	 * @param max 최대 결과 수. 상한은 호출부가 정한다
	 */
	public Mono<List<KeycloakPerson>> listPeople(int max) {
		return adminToken()
				.flatMapMany(token -> webClient.get()
						.uri(builder -> builder.path("/admin/realms/{realm}/users")
								.queryParam("first", 0)
								.queryParam("max", max)
								.queryParam("briefRepresentation", false)
								.build(realm))
						.headers(headers -> headers.setBearerAuth(token))
						.retrieve()
						.bodyToFlux(PersonRepresentation.class))
				.filter(user -> user.username() != null && !user.username().startsWith("service-account-"))
				.map(user -> new KeycloakPerson(user.id(), user.username(), personName(user), user.email(),
						!Boolean.FALSE.equals(user.enabled())))
				.collectList();
	}

	private static String personName(PersonRepresentation user) {
		String name = PersonNames.fullName(user.lastName(), user.firstName());
		return name == null ? user.username() : name;
	}

	/**
	 * 이름·이메일·username으로 계정을 찾는다(이슈 #172의 초대 대상 검색).
	 *
	 * 초대창이 subject(UUID)를 손으로 받던 것을 대체한다 — 사람이 그 값을 알 방법이 앱 안에
	 * 없었다. 결과의 subject는 화면이 그대로 초대 API에 넘기고, 사람은 표시 이름만 본다.
	 *
	 * 표시 이름은 displayName()과 같은 규약(성+이름, 둘 다 없으면 username — PersonNames)을 쓴다.
	 *
	 * 오류는 displayName()처럼 삼키지 않고 전파한다. 검색이 빈 결과와 장애를 구분하지 못하면
	 * "그런 사람 없음"으로 보여 초대자가 헛물을 켠다 — exists()와 같은 판단이다.
	 *
	 * 화면은 이름을 성+이름으로 붙여 보여주는데(황정민), Keycloak은 칸마다 따로 부분 일치로 찾아 붙여 쓴 검색어가
	 * 어느 칸에도 맞지 않는다. 그래서 검색어 그대로 한 번 묻고, 앞 한 글자·두 글자(복성)를 성으로 보고 뗀 나머지로
	 * 더 묻는다(fullNameRemainders). 더 물어 찾은 사람은 성+이름에 검색어가 들어 있을 때만 남긴다 — "정민"으로
	 * 물으면 김정민도 오기 때문이다. 같은 사람은 한 번만, 먼저 찾은 순서로 max개까지 돌려준다.
	 *
	 * @param query 부분 일치 검색어. Keycloak이 username·email·firstName·lastName을 함께 본다
	 * @param max 최대 결과 수. 상한은 호출부가 정한다
	 */
	public Mono<List<KeycloakUserSummary>> search(String query, int max) {
		return adminToken()
				.flatMapMany(token -> Flux.concat(
						searchOnce(token, query, max),
						Flux.fromIterable(fullNameRemainders(query))
								.concatMap(remainder -> searchOnce(token, remainder, max))
								.filter(user -> {
									String fullName = PersonNames.fullName(user.lastName(), user.firstName());
									return fullName != null && fullName.contains(query);
								})))
				.distinct(PersonRepresentation::id)
				.take(max)
				.map(user -> new KeycloakUserSummary(user.id(), personName(user)))
				.collectList();
	}

	private Flux<PersonRepresentation> searchOnce(String token, String query, int max) {
		return webClient.get()
				.uri(builder -> builder.path("/admin/realms/{realm}/users")
						.queryParam("search", query)
						.queryParam("max", max)
						.queryParam("briefRepresentation", true)
						.build(realm))
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToFlux(PersonRepresentation.class);
	}

	/** 붙여 쓴 성+이름에서 성(앞 한 글자, 복성이면 두 글자)을 뗀 나머지. 띄어 쓴 검색어는 성+이름이 아니라 보고 비워 둔다. */
	static List<String> fullNameRemainders(String query) {
		if (query.length() < 2 || query.chars().anyMatch(Character::isWhitespace)) {
			return List.of();
		}
		return query.length() < 3 ? List.of(query.substring(1)) : List.of(query.substring(1), query.substring(2));
	}

	/**
	 * 절체 사전 검증용으로 Keycloak에서 활성 사용자 subject를 페이지 단위로 모두 읽는다.
	 * 서비스 계정은 조직 배정 대상이 아니므로 제외한다. Tenant는 DB 소속에서 판정하며,
	 * 로컬 사용자를 만들거나 token claim을 바꾸지 않는다.
	 */
	public Mono<List<String>> listEnabledUserSubjects() {
		return adminToken().flatMapMany(token -> enabledUsers(token, 0))
				.filter(user -> user.username() == null || !user.username().startsWith("service-account-"))
				.map(AdminUserRepresentation::id)
				.collectList();
	}

	private reactor.core.publisher.Flux<AdminUserRepresentation> enabledUsers(String token, int first) {
		return webClient.get()
				.uri(builder -> builder.path("/admin/realms/{realm}/users")
						.queryParam("enabled", true)
						.queryParam("briefRepresentation", true)
						.queryParam("first", first)
						.queryParam("max", 100)
						.build(realm))
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToFlux(AdminUserRepresentation.class)
				.collectList()
				.flatMapMany(page -> page.size() == 100
						? reactor.core.publisher.Flux.fromIterable(page).concatWith(enabledUsers(token, first + 100))
						: reactor.core.publisher.Flux.fromIterable(page));
	}

	// ------------------------------------------------------------------ 권한 변경 감사 수집(#304)
	// 아래는 모두 오류를 전파한다 — 수집기가 실패를 수집 상태에 남기고 다음 주기에 다시 시도한다. 삼키면 기록이 조용히 빈다.

	/**
	 * admin event를 모두 읽는다. dateFrom이 있으면 그 날짜(yyyy-MM-dd)부터다 — Keycloak의 기간 필터가 날짜 단위라
	 * 호출부가 이미 본 이벤트도 다시 온다(멱등 키로 거른다). 순서는 Keycloak이 정하므로 호출부가 정렬한다.
	 */
	public Mono<List<KeycloakAdminEvent>> adminEvents(java.time.LocalDate dateFrom) {
		return adminToken().flatMapMany(token -> pages(token, KeycloakAdminEvent.class, 0, (builder, first) -> {
			builder.path("/admin/realms/{realm}/admin-events").queryParam("first", first).queryParam("max", PAGE);
			if (dateFrom != null) builder.queryParam("dateFrom", dateFrom.toString());
			return builder.build(realm);
		})).collectList();
	}

	/** realm의 이벤트 설정(admin event 저장·상세 여부). `view-events`가 필요하다. */
	public Mono<KeycloakEventsConfig> eventsConfig() {
		return adminToken().flatMap(token -> webClient.get()
				.uri("/admin/realms/{realm}/events/config", realm)
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToMono(KeycloakEventsConfig.class));
	}

	/** realm 역할을 직접 가진 사용자 id. */
	public Mono<List<String>> realmRoleUserIds(String role) {
		return adminToken().flatMapMany(token -> pages(token, AdminUserRepresentation.class, 0, (builder, first) -> builder
				.path("/admin/realms/{realm}/roles/{role}/users").queryParam("first", first).queryParam("max", PAGE)
				.build(realm, role))).map(AdminUserRepresentation::id).collectList();
	}

	/** realm 역할을 직접 가진 그룹. */
	public Mono<List<KeycloakGroup>> realmRoleGroups(String role) {
		return adminToken().flatMapMany(token -> pages(token, KeycloakGroup.class, 0, (builder, first) -> builder
				.path("/admin/realms/{realm}/roles/{role}/groups").queryParam("first", first).queryParam("max", PAGE)
				.build(realm, role))).collectList();
	}

	/** 그룹의 직접 구성원 id. 하위 그룹 구성원은 subGroups로 따로 내려가 모은다. */
	public Mono<List<String>> groupMemberIds(String groupId) {
		return adminToken().flatMapMany(token -> pages(token, AdminUserRepresentation.class, 0, (builder, first) -> builder
				.path("/admin/realms/{realm}/groups/{group}/members").queryParam("first", first).queryParam("max", PAGE)
				.queryParam("briefRepresentation", true).build(realm, groupId))).map(AdminUserRepresentation::id).collectList();
	}

	/** 바로 아래 하위 그룹. 하위 그룹 구성원도 상위 그룹의 역할을 물려받는다. */
	public Mono<List<KeycloakGroup>> subGroups(String groupId) {
		return adminToken().flatMapMany(token -> pages(token, KeycloakGroup.class, 0, (builder, first) -> builder
				.path("/admin/realms/{realm}/groups/{group}/children").queryParam("first", first).queryParam("max", PAGE)
				.build(realm, groupId))).collectList();
	}

	/** realm 역할 전체(복합 여부 포함). */
	public Mono<List<KeycloakRole>> realmRoles() {
		return adminToken().flatMapMany(token -> pages(token, KeycloakRole.class, 0, (builder, first) -> builder
				.path("/admin/realms/{realm}/roles").queryParam("first", first).queryParam("max", PAGE)
				.queryParam("briefRepresentation", false).build(realm))).collectList();
	}

	/** 복합 역할이 품은 역할(realm·클라이언트 모두). */
	public Mono<List<KeycloakRole>> composites(String role) {
		return adminToken().flatMap(token -> webClient.get()
				.uri("/admin/realms/{realm}/roles/{role}/composites", realm, role)
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToFlux(KeycloakRole.class)
				.collectList());
	}

	/** realm의 모든 계정(서비스 계정 포함)과 활성 여부. 기준선이 실효 보유자와 비활성 계정을 찾을 때 쓴다. */
	public Mono<List<KeycloakUser>> users() {
		return adminToken().flatMapMany(token -> pages(token, KeycloakUser.class, 0, (builder, first) -> builder
				.path("/admin/realms/{realm}/users").queryParam("first", first).queryParam("max", PAGE)
				.queryParam("briefRepresentation", true).build(realm))).collectList();
	}

	/** 사용자의 실효 realm 역할 이름 — 직접·그룹·복합(realm·클라이언트 경유)·기본 역할을 모두 펼친 결과다. */
	public Mono<List<String>> effectiveRealmRoleNames(String userId) {
		return adminToken().flatMap(token -> webClient.get()
				.uri("/admin/realms/{realm}/users/{id}/role-mappings/realm/composite", realm, userId)
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToFlux(KeycloakRole.class)
				.map(KeycloakRole::name)
				.collectList());
	}

	/**
	 * 이 클라이언트 서비스 계정이 가진 realm-management 역할에서 realm-management의 내부 id를 얻는다. 클라이언트 목록 조회
	 * (view-clients)는 모든 confidential client의 secret까지 읽히는 권한이라 쓰지 않는다. view-users로 된다.
	 */
	public Mono<Optional<String>> realmManagementUuid() {
		return adminToken().flatMap(token -> webClient.get()
				.uri(builder -> builder.path("/admin/realms/{realm}/users").queryParam("username", "service-account-" + clientId)
						.queryParam("exact", true).queryParam("briefRepresentation", true).build(realm))
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToFlux(AdminUserRepresentation.class)
				.next()
				.flatMap(serviceAccount -> webClient.get()
						.uri("/admin/realms/{realm}/users/{id}/role-mappings", realm, serviceAccount.id())
						.headers(headers -> headers.setBearerAuth(token))
						.retrieve()
						.bodyToMono(RoleMappings.class))
				.map(mappings -> Optional.ofNullable(mappings.clientMappings())
						.map(clients -> clients.get("realm-management")).map(ClientMapping::id))
				.defaultIfEmpty(Optional.empty()));
	}

	/** 사용자에게 한 클라이언트의 역할이 직접 붙은 것(그룹·복합 경유 제외). */
	public Mono<List<String>> directClientRoleNames(String userId, String clientUuid) {
		return adminToken().flatMap(token -> webClient.get()
				.uri("/admin/realms/{realm}/users/{id}/role-mappings/clients/{client}", realm, userId, clientUuid)
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToFlux(KeycloakRole.class)
				.map(KeycloakRole::name)
				.collectList());
	}

	/** 사용자의 한 클라이언트에 대한 실효 역할 이름. */
	public Mono<List<String>> effectiveClientRoleNames(String userId, String clientUuid) {
		return adminToken().flatMap(token -> webClient.get()
				.uri("/admin/realms/{realm}/users/{id}/role-mappings/clients/{client}/composite", realm, userId, clientUuid)
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToFlux(KeycloakRole.class)
				.map(KeycloakRole::name)
				.collectList());
	}

	/**
	 * 다시 시도해도 풀리지 않는 응답을 설정 문제로 바꾼다(#326) — 토큰 엔드포인트의 400·401(클라이언트 id·secret이 틀리거나
	 * 서비스 계정이 꺼짐)과 Admin API의 403(역할 누락). Admin API의 401은 Keycloak이 토큰을 먼저 무효화한 일시 상황이라
	 * 그대로 둔다. 응답 본문은 버린다 — Keycloak 오류 설명을 예외·로그에 싣지 않는다.
	 */
	private ExchangeFilterFunction configurationFailureFilter() {
		return (request, next) -> next.exchange(request).flatMap(response -> {
			// Admin API 401은 Keycloak이 토큰을 먼저 무효화한 것이다(재시작 등). 403은 토큰에 담긴 역할이 모자란 것이라, 운영자가
			// 역할을 고쳐도 캐시한 토큰은 만료(기본 5분)까지 옛 역할 그대로다(실측). 둘 다 이 요청은 실패하고, 다음 요청이 새 토큰을
			// 받게 캐시를 비운다 — 감사 수집기만 비우면 수집을 끈 배포는 토큰 만료까지 계속 실패한다.
			int status = response.statusCode().value();
			if ((status == 401 || status == 403) && request.url().getPath().contains(ADMIN_PATH_MARKER)) invalidateToken();
			return rejectConfigurationFailure(request, response);
		});
	}

	static Mono<ClientResponse> rejectConfigurationFailure(ClientRequest request, ClientResponse response) {
		int status = response.statusCode().value();
		String path = request.url().getPath();
		KeycloakAdminUnavailableException.Reason reason = null;
		if (path.endsWith(TOKEN_PATH_SUFFIX) && (status == 400 || status == 401)) {
			reason = KeycloakAdminUnavailableException.Reason.TOKEN_REJECTED;
		} else if (path.contains(ADMIN_PATH_MARKER) && status == 403) {
			reason = KeycloakAdminUnavailableException.Reason.FORBIDDEN;
		}
		if (reason == null) return Mono.just(response);
		return response.releaseBody().then(Mono.error(new KeycloakAdminUnavailableException(reason, status, path)));
	}

	/** Keycloak이 재시작 등으로 토큰을 먼저 무효화했을 때(401) 다음 호출이 새 토큰을 받게 한다. */
	public void invalidateToken() {
		cachedToken.set(null);
	}

	private static final int PAGE = 100;

	/** first/max로 페이지를 끝까지 읽는다. 한 페이지가 가득 차면 다음 페이지를 더 묻는다. */
	private <T> Flux<T> pages(String token, Class<T> type, int first,
			java.util.function.BiFunction<org.springframework.web.util.UriBuilder, Integer, java.net.URI> uri) {
		return webClient.get()
				.uri(builder -> uri.apply(builder, first))
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToFlux(type)
				.collectList()
				.flatMapMany(page -> page.size() == PAGE
						? Flux.fromIterable(page).concatWith(pages(token, type, first + PAGE, uri))
						: Flux.fromIterable(page));
	}

	/** admin event 한 건. representation은 상세가 켜져 있을 때만 오는 JSON 문자열이다. id가 없는 버전이 있을 수 있다. */
	public record KeycloakAdminEvent(String id, long time, AuthDetails authDetails, String operationType,
			String resourceType, String resourcePath, String representation) {

		/** 행위자. ipAddress는 받지만 저장하지 않는다. */
		public record AuthDetails(String realmId, String clientId, String userId, String ipAddress) {
		}
	}

	/** realm 이벤트 설정 중 감사에 필요한 값. */
	public record KeycloakEventsConfig(Boolean adminEventsEnabled, Boolean adminEventsDetailsEnabled) {
	}

	public record KeycloakGroup(String id, String name, String path) {
	}

	public record KeycloakUser(String id, String username, Boolean enabled) {
	}

	private record RoleMappings(Map<String, ClientMapping> clientMappings) {
	}

	private record ClientMapping(String id, String client) {
	}

	public record KeycloakRole(String id, String name, Boolean composite, Boolean clientRole, String containerId) {
	}

	private Mono<Optional<String>> lookupUser(String subject, String token) {
		return webClient.get()
				.uri("/admin/realms/{realm}/users/{id}", realm, subject)
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToMono(PersonRepresentation.class)
				.map(user -> Optional.ofNullable(personName(user)));
	}

	private Mono<String> adminToken() {
		CachedToken cached = cachedToken.get();
		if (cached != null && cached.isValidAt(Instant.now())) {
			return Mono.just(cached.value());
		}
		RememberedRejection rejected = rejectedToken.get();
		if (rejected != null && rejected.isValidAt(Instant.now())) {
			return Mono.error(rejected.rejection().again());
		}
		return fetchToken()
				.doOnNext(token -> {
					cachedToken.set(token);
					rejectedToken.set(null);
				})
				.doOnError(KeycloakAdminUnavailableException.class, rejection -> rejectedToken
						.set(new RememberedRejection(rejection, Instant.now().plus(REJECTION_MEMORY))))
				.map(CachedToken::value);
	}

	private record RememberedRejection(KeycloakAdminUnavailableException rejection, Instant until) {
		boolean isValidAt(Instant now) {
			return now.isBefore(until);
		}
	}

	private Mono<CachedToken> fetchToken() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");
		form.add("client_id", clientId);
		form.add("client_secret", clientSecret);
		return webClient.post()
				.uri("/realms/{realm}" + TOKEN_PATH_SUFFIX, realm)
				.body(BodyInserters.fromFormData(form))
				.retrieve()
				.bodyToMono(TokenResponse.class)
				.map(response -> new CachedToken(response.accessToken(),
						Instant.now().plusSeconds(response.expiresIn()).minus(EXPIRY_SAFETY_MARGIN)));
	}

	private record CachedName(Optional<String> value, Instant expiresAt) {
		boolean isValidAt(Instant now) {
			return now.isBefore(expiresAt);
		}
	}

	private record CachedToken(String value, Instant expiresAt) {
		boolean isValidAt(Instant now) {
			return now.isBefore(expiresAt);
		}
	}

	/** 응답 중 access_token·expires_in만 쓴다(나머지는 무시한다). */
	private record TokenResponse(@JsonProperty("access_token") String accessToken,
			@JsonProperty("expires_in") long expiresIn) {
	}

	/** 사람 계정 응답. briefRepresentation이어도 id·username·이름·이메일은 온다. 표시 이름은 personName()으로 만든다. */
	private record PersonRepresentation(String id, String username, String firstName, String lastName, String email,
			Boolean enabled) {
	}

	/** 사용자 식별자와 서비스 계정 구분에 필요한 이름만 읽는 간략 표현. */
	private record AdminUserRepresentation(String id, String username) {
	}

}
