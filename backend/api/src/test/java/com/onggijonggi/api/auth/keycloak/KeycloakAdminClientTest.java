package com.onggijonggi.api.auth.keycloak;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class Name : KeycloakAdminClientTest.java
 * Description : 토큰 발급·재사용과 사용자 조회 응답 해석만 검증한다. WebClient의 exchangeFunction을
 *               가짜로 물려 요청 경로별로 분기하므로 실행 중인 Keycloak이 필요 없다.
 */
class KeycloakAdminClientTest {

	private static final String INTERNAL_URL = "http://keycloak:8080";

	private static final String REALM = "app-realm";

	private static final String CLIENT_ID = "ogjg-client";

	private static final String CLIENT_SECRET = "test-secret";

	private static final String SUBJECT = "b3f2a6b0-3f0e-4a9a-9e0a-2f6c3d1e9a11";

	private final AtomicInteger tokenRequests = new AtomicInteger();

	private final AtomicReference<ClientRequest> lastUserRequest = new AtomicReference<>();

	/** 캐시가 실제로 HTTP를 아꼈는지 보려면 조회 횟수를 세야 한다(이슈 #200). */
	private final AtomicInteger userRequests = new AtomicInteger();
	/** 사용자 조회 응답에 덧붙일 이름 필드(JSON 조각). 비어 있으면 이름 없는 계정이다. */
	private String userNames = "";

	private KeycloakAdminClient clientReturning(String username, long expiresInSeconds) {
		return clientRespondingWith(HttpStatus.NOT_FOUND, username, expiresInSeconds);
	}

	private KeycloakAdminClient clientRespondingWith(HttpStatus userLookupFailureStatus, String username,
			long expiresInSeconds) {
		return clientRespondingWith(userLookupFailureStatus, username, expiresInSeconds, Duration.ofMinutes(5));
	}

	private KeycloakAdminClient clientRespondingWith(HttpStatus userLookupFailureStatus, String username,
			long expiresInSeconds, Duration displayNameTtl) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().toString().endsWith("/protocol/openid-connect/token")) {
				tokenRequests.incrementAndGet();
				return Mono.just(jsonResponse("""
						{ "access_token": "admin-token", "expires_in": %d, "token_type": "Bearer" }
						""".formatted(expiresInSeconds)));
			}
			lastUserRequest.set(request);
			userRequests.incrementAndGet();
			if (username == null) {
				return Mono.just(ClientResponse.create(userLookupFailureStatus).build());
			}
			return Mono.just(jsonResponse("""
					{ "id": "%s", "username": "%s"%s }
					""".formatted(SUBJECT, username, userNames)));
		});
		return new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET,
				displayNameTtl);
	}

	private ClientResponse jsonResponse(String body) {
		return ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.body(body)
				.build();
	}

	/** 같은 사람을 다시 물으면 Admin API를 타지 않는다 — 이 캐시의 목적 자체다(이슈 #200). */
	@Test
	void reusesCachedDisplayNameWithoutCallingAdminApiAgain() {
		KeycloakAdminClient client = clientReturning("sujin", 60);

		StepVerifier.create(client.displayName(SUBJECT)).expectNext(Optional.of("sujin")).verifyComplete();
		StepVerifier.create(client.displayName(SUBJECT)).expectNext(Optional.of("sujin")).verifyComplete();

		assertThat(userRequests.get()).isEqualTo(1);
	}

	/** TTL이 지나면 다시 묻는다 — 정본은 Keycloak이고 DB에 미러하지 않는다는 방침 그대로다. */
	@Test
	void asksAgainAfterTheTtlHasPassed() {
		KeycloakAdminClient client = clientRespondingWith(HttpStatus.NOT_FOUND, "sujin", 60, Duration.ZERO);

		StepVerifier.create(client.displayName(SUBJECT)).expectNext(Optional.of("sujin")).verifyComplete();
		StepVerifier.create(client.displayName(SUBJECT)).expectNext(Optional.of("sujin")).verifyComplete();

		assertThat(userRequests.get()).isEqualTo(2);
	}

	/**
	* 장애로 비어버린 결과는 캐시하지 않는다. 캐시하면 Keycloak이 잠깐 흔들린 것이 TTL 내내
	* "이름 없음"으로 굳는다 — 다음 호출이 다시 시도해야 한다(이슈 #200).
	*/
	@Test
	void doesNotCacheEmptyResultsThatCameFromAnOutage() {
		KeycloakAdminClient client = clientRespondingWith(HttpStatus.INTERNAL_SERVER_ERROR, null, 60);

		StepVerifier.create(client.displayName(SUBJECT)).expectNext(Optional.empty()).verifyComplete();
		StepVerifier.create(client.displayName(SUBJECT)).expectNext(Optional.empty()).verifyComplete();

		assertThat(userRequests.get()).isEqualTo(2);
	}

	/**
	* 404는 반대다 — 탈퇴처럼 답이 확정된 경우라 다시 물어도 같다. 캐시하지 않으면 떠난 사람의
	* 옛 메시지를 볼 때마다 조회가 한 번씩 더 나간다.
	*/
	@Test
	void cachesTheDefinitiveAbsenceOfAUser() {
		KeycloakAdminClient client = clientReturning(null, 60);

		StepVerifier.create(client.displayName(SUBJECT)).expectNext(Optional.empty()).verifyComplete();
		StepVerifier.create(client.displayName(SUBJECT)).expectNext(Optional.empty()).verifyComplete();

		assertThat(userRequests.get()).isEqualTo(1);
	}

	/** 성+이름이 있으면 붙여서 쓴다(PersonNames) — 토큰의 family_name·given_name과 같은 규칙이다. */
	@Test
	void resolvesDisplayNameFromFamilyAndGivenName() {
		userNames = ", \"lastName\": \"황\", \"firstName\": \"정민\"";
		KeycloakAdminClient client = clientReturning("demo3", 60);

		StepVerifier.create(client.displayName(SUBJECT))
				.expectNext(Optional.of("황정민"))
				.verifyComplete();
	}

	/** 이름이 없는 계정은 username으로 물러난다. */
	@Test
	void resolvesDisplayNameFromUsername() {
		KeycloakAdminClient client = clientReturning("sujin", 60);

		StepVerifier.create(client.displayName(SUBJECT))
				.expectNext(Optional.of("sujin"))
				.verifyComplete();

		ClientRequest request = lastUserRequest.get();
		assertThat(request.method()).isEqualTo(HttpMethod.GET);
		assertThat(request.url()).hasToString(INTERNAL_URL + "/admin/realms/" + REALM + "/users/" + SUBJECT);
		assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer admin-token");
	}

	/** 탈퇴 등으로 Keycloak에 그 subject가 없으면 예외가 아니라 빈 값 — 화면이 대체 문구를 정한다. */
	@Test
	void returnsEmptyWhenUserNotFound() {
		KeycloakAdminClient client = clientReturning(null, 60);

		StepVerifier.create(client.displayName(SUBJECT))
				.expectNext(Optional.empty())
				.verifyComplete();
	}

	/**
	* NotFound가 아닌 오류(5xx 등 Admin API 쪽 장애)도 예외로 전파하지 않는다 — 표시 이름 하나
	* 실패했다고 호출부의 스레드 목록 조회 전체가 죽으면 안 된다.
	*/
	@Test
	void returnsEmptyWhenAdminApiFails() {
		KeycloakAdminClient client = clientRespondingWith(HttpStatus.INTERNAL_SERVER_ERROR, null, 60);

		StepVerifier.create(client.displayName(SUBJECT))
				.expectNext(Optional.empty())
				.verifyComplete();
	}

	/** 만료 전 두 번째 조회는 토큰을 다시 받지 않는다 — 매 조회마다 로그인하면 Keycloak에 부하가 쌓인다. */
	@Test
	void reusesTokenUntilItExpires() {
		KeycloakAdminClient client = clientReturning("sujin", 3600);

		client.displayName(SUBJECT).block();
		client.displayName("other-subject").block();

		assertThat(tokenRequests.get()).isEqualTo(1);
	}

	/** 만료 30초 전이면 재사용하지 않는다 — 요청 도중 만료되는 경합을 피하는 안전 여유분이다. */
	@Test
	void refetchesTokenNearExpiry() {
		KeycloakAdminClient client = clientReturning("sujin", 10);

		client.displayName(SUBJECT).block();
		client.displayName("other-subject").block();

		assertThat(tokenRequests.get()).isEqualTo(2);
	}

	// ------------------------------------------------------------------ 초대 후보 검색

	private static final String HWANG = """
			{ "id": "sub-hwang", "username": "demo3", "lastName": "황", "firstName": "정민" }""";
	private static final String KIM = """
			{ "id": "sub-kim", "username": "kimjm", "lastName": "김", "firstName": "정민" }""";
	private static final String LEE = """
			{ "id": "sub-lee", "username": "leems", "lastName": "이", "firstName": "민수" }""";

	/** 검색어(디코드된 search 값) → Keycloak이 돌려줄 사람들(JSON 객체). 없는 검색어는 빈 목록이다. */
	private KeycloakAdminClient clientSearching(java.util.Map<String, List<String>> resultsByQuery, List<String> asked) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().toString().endsWith("/protocol/openid-connect/token")) {
				return Mono.just(jsonResponse("""
						{ "access_token": "admin-token", "expires_in": 60, "token_type": "Bearer" }
						"""));
			}
			String query = org.springframework.web.util.UriComponentsBuilder.fromUri(request.url()).build()
					.getQueryParams().getFirst("search");
			String decoded = java.net.URLDecoder.decode(query, java.nio.charset.StandardCharsets.UTF_8);
			asked.add(decoded);
			return Mono.just(jsonResponse("[" + String.join(",", resultsByQuery.getOrDefault(decoded, List.of())) + "]"));
		});
		return new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET, Duration.ofMinutes(5));
	}

	/** 화면에 보이는 대로 붙여 쓴 "황정민"은 Keycloak의 어느 칸에도 맞지 않는다 — 성을 뗀 "정민"으로 다시 물어 찾는다. */
	@Test
	void findsSomeoneByTheirJoinedFamilyAndGivenName() {
		List<String> asked = new ArrayList<>();
		KeycloakAdminClient client = clientSearching(java.util.Map.of(
				"정민", List.of(HWANG, KIM),
				"민", List.of(HWANG, KIM, LEE)), asked);

		StepVerifier.create(client.search("황정민", 20))
				.expectNext(List.of(new KeycloakUserSummary("sub-hwang", "황정민")))
				.verifyComplete();
		assertThat(asked).containsExactly("황정민", "정민", "민");
	}

	/** 검색어 그대로 찾은 사람은 이름이 달라도 남긴다(아이디·이메일로 찾은 경우). 같은 사람은 한 번만 나온다. */
	@Test
	void keepsDirectMatchesAndListsEachPersonOnce() {
		KeycloakAdminClient client = clientSearching(java.util.Map.of(
				"demo3", List.of(HWANG),
				"emo3", List.of(HWANG, KIM)), new ArrayList<>());

		StepVerifier.create(client.search("demo3", 20))
				.expectNext(List.of(new KeycloakUserSummary("sub-hwang", "황정민")))
				.verifyComplete();
	}

	@Test
	void triesDroppingOneOrTwoLeadingCharactersAsTheFamilyName() {
		assertThat(KeycloakAdminClient.fullNameRemainders("정민")).containsExactly("민");
		assertThat(KeycloakAdminClient.fullNameRemainders("남궁민수")).containsExactly("궁민수", "민수");
		assertThat(KeycloakAdminClient.fullNameRemainders("황 정민")).isEmpty();
		assertThat(KeycloakAdminClient.fullNameRemainders("황")).isEmpty();
	}

	/** 절체 검증은 활성 사용자를 100건씩 끝까지 읽는다 — 첫 페이지에서 멈추면 뒤 사용자가 대조에서 조용히 빠진다. */
	@Test
	void listsEnabledUserSubjectsAcrossPagesUntilAShortPage() {
		List<String> firstValues = new ArrayList<>();
		KeycloakAdminClient client = clientServingUserPages(130, firstValues);

		List<String> users = client.listEnabledUserSubjects().block();

		assertThat(users).hasSize(130);
		assertThat(users.get(0)).isEqualTo("user-0");
		assertThat(users.get(129)).isEqualTo("user-129");
		assertThat(firstValues).containsExactly("0", "100");
		assertThat(tokenRequests.get()).as("페이지마다 토큰을 새로 받지 않는다").isEqualTo(1);
	}

	/** 정확히 100건이면 다음 페이지를 한 번 더 묻고, 빈 페이지가 오면 끝난다. */
	@Test
	void asksOneMorePageWhenTheLastPageIsExactlyFull() {
		List<String> firstValues = new ArrayList<>();
		KeycloakAdminClient client = clientServingUserPages(100, firstValues);

		assertThat(client.listEnabledUserSubjects().block()).hasSize(100);
		assertThat(firstValues).containsExactly("0", "100");
	}

	@Test
	void returnsAnEmptyListWhenThereAreNoEnabledUsers() {
		List<String> firstValues = new ArrayList<>();
		KeycloakAdminClient client = clientServingUserPages(0, firstValues);

		assertThat(client.listEnabledUserSubjects().block()).isEmpty();
		assertThat(firstValues).containsExactly("0");
	}

	/** 서비스 계정은 사람의 조직 배정 대상이 아니다. 이름이 없거나 subject가 빈 행은 검증 경계로 그대로 보낸다. */
	@Test
	void excludesOnlyIdentifiedServiceAccountsFromCutoverSubjects() {
		KeycloakAdminClient client = clientServingRawUserPages("""
				[
				  { "id": "service", "username": "service-account-ogjg-bff" },
				  { "id": "person", "username": "appuser" },
				  { "id": "unknown-name" },
				  { "id": "", "username": "malformed-person" }
				]
				""");

		assertThat(client.listEnabledUserSubjects().block()).containsExactly("person", "unknown-name", "");
	}

	/** 필터 뒤 목록이 비어도 원본 페이지가 가득 찼으면 다음 페이지의 사람을 빠뜨리지 않는다. */
	@Test
	void continuesPastAFullPageOfServiceAccounts() {
		List<String> firstValues = new ArrayList<>();
		String services = java.util.stream.IntStream.range(0, 100)
				.mapToObj(index -> "{\"id\":\"service-%d\",\"username\":\"service-account-client-%d\"}".formatted(index, index))
				.collect(java.util.stream.Collectors.joining(",", "[", "]"));
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().toString().endsWith("/protocol/openid-connect/token")) {
				return Mono.just(jsonResponse("{\"access_token\":\"t\",\"expires_in\":60}"));
			}
			String first = queryValue(request, "first");
			firstValues.add(first);
			return Mono.just(jsonResponse(first.equals("0") ? services : "[{\"id\":\"person\",\"username\":\"appuser\"}]"));
		});
		KeycloakAdminClient client = new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET,
				Duration.ofMinutes(5));

		assertThat(client.listEnabledUserSubjects().block()).containsExactly("person");
		assertThat(firstValues).containsExactly("0", "100");
	}

	/** 절체 검증에는 활성 사용자 subject만 필요하므로 간략 표현을 요청한다. */
	@Test
	void requestsOnlyEnabledUsersWithBriefRepresentation() {
		KeycloakAdminClient client = clientServingUserPages(1, new ArrayList<>());

		client.listEnabledUserSubjects().block();

		String query = lastUserRequest.get().url().getRawQuery();
		assertThat(query).contains("enabled=true").contains("briefRepresentation=true").contains("max=100");
	}

	/** 예전 tenant 속성이 있거나 잘못되어도 활성 사용자 subject 목록은 영향을 받지 않는다. */
	@Test
	void listsSubjectsRegardlessOfUnusedTenantAttributes() {
		String page = """
				[
				  { "id": "single", "attributes": { "tenant": ["acme"] } },
				  { "id": "none", "attributes": { "locale": ["ko"] } },
				  { "id": "no-attributes" },
				  { "id": "multiple", "attributes": { "tenant": ["acme", "globex"] } },
				  { "id": "blank", "attributes": { "tenant": [" "] } },
				  { "id": "empty-list", "attributes": { "tenant": [] } }
				]
				""";
		KeycloakAdminClient client = clientServingRawUserPages(page);

		List<String> subjects = client.listEnabledUserSubjects().block();

		assertThat(subjects).containsExactly("single", "none", "no-attributes", "multiple", "blank", "empty-list");
	}

	/** 두 번째 페이지가 실패하면 앞 페이지만 돌려주지 않고 오류를 전파한다 — 일부만 대조한 결과를 통과로 오해하지 않게 한다. */
	@Test
	void propagatesAFailureOnALaterPage() {
		AtomicInteger pages = new AtomicInteger();
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().toString().endsWith("/protocol/openid-connect/token")) {
				return Mono.just(jsonResponse("{ \"access_token\": \"t\", \"expires_in\": 60, \"token_type\": \"Bearer\" }"));
			}
			return Mono.just(pages.getAndIncrement() == 0 ? jsonResponse(usersJson(0, 100))
					: ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
		});
		KeycloakAdminClient client = new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET,
				Duration.ofMinutes(5));

		StepVerifier.create(client.listEnabledUserSubjects()).expectError().verify();
	}

	/** total명의 사용자를 100건 단위로 나눠 주는 Keycloak을 흉내 낸다. 요청마다 `first` 값을 기록한다. */
	private KeycloakAdminClient clientServingUserPages(int total, List<String> firstValues) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().toString().endsWith("/protocol/openid-connect/token")) {
				tokenRequests.incrementAndGet();
				return Mono.just(jsonResponse("{ \"access_token\": \"admin-token\", \"expires_in\": 3600, \"token_type\": \"Bearer\" }"));
			}
			lastUserRequest.set(request);
			int first = Integer.parseInt(queryValue(request, "first"));
			firstValues.add(String.valueOf(first));
			int size = Math.max(0, Math.min(100, total - first));
			return Mono.just(jsonResponse(usersJson(first, size)));
		});
		return new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET, Duration.ofMinutes(5));
	}

	private KeycloakAdminClient clientServingRawUserPages(String body) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().toString().endsWith("/protocol/openid-connect/token")) {
				return Mono.just(jsonResponse("{ \"access_token\": \"t\", \"expires_in\": 60, \"token_type\": \"Bearer\" }"));
			}
			return Mono.just(jsonResponse(body));
		});
		return new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET, Duration.ofMinutes(5));
	}

	private static String usersJson(int startIndex, int count) {
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < count; index++) {
			if (index > 0) json.append(',');
			json.append("{\"id\":\"user-").append(startIndex + index).append("\",\"attributes\":{\"tenant\":[\"acme\"]}}");
		}
		return json.append(']').toString();
	}

	private static String queryValue(ClientRequest request, String name) {
		for (String pair : request.url().getRawQuery().split("&")) {
			String[] parts = pair.split("=", 2);
			if (parts[0].equals(name)) return parts[1];
		}
		throw new IllegalStateException("쿼리에 " + name + "이 없다");
	}


	// ------------------------------------------------------------------ 설정 문제 분류(#326)

	/** 토큰 요청에 tokenStatus, Admin API 요청에 adminStatus로 답하는 클라이언트. 200이면 정상 응답이다. */
	private KeycloakAdminClient clientWithStatuses(HttpStatus tokenStatus, HttpStatus adminStatus) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().getPath().endsWith("/protocol/openid-connect/token")) {
				tokenRequests.incrementAndGet();
				return Mono.just(tokenStatus == HttpStatus.OK
						? jsonResponse("{ \"access_token\": \"admin-token\", \"expires_in\": 60 }")
						: ClientResponse.create(tokenStatus).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
								.body("{\"error\":\"unauthorized_client\",\"error_description\":\"secret detail\"}").build());
			}
			return Mono.just(adminStatus == HttpStatus.OK ? jsonResponse("[]") : ClientResponse.create(adminStatus).build());
		});
		return new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET, Duration.ZERO);
	}

	/** 토큰 발급 거부(id·secret 틀림, 서비스 계정 꺼짐)는 다시 시도해도 풀리지 않는 설정 문제다. 응답 본문은 싣지 않는다. */
	@Test
	void aRejectedTokenRequestIsAConfigurationProblem() {
		for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED)) {
			StepVerifier.create(clientWithStatuses(status, HttpStatus.OK).listPeople(10))
					.expectErrorSatisfies(error -> {
						assertThat(error).isInstanceOf(KeycloakAdminUnavailableException.class);
						KeycloakAdminUnavailableException unavailable = (KeycloakAdminUnavailableException) error;
						assertThat(unavailable.reason()).isEqualTo(KeycloakAdminUnavailableException.Reason.TOKEN_REJECTED);
						assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
						assertThat(unavailable.getMessage()).doesNotContain("secret detail");
						assertThat(unavailable.summary())
								.isEqualTo("Keycloak 관리 클라이언트 인증 실패(설정 확인, " + status.value() + ")");
					})
					.verify();
		}
	}

	/** Admin API 403은 서비스 계정 역할 누락이다. 사람 목록·검색·실존 확인·활성 계정·계정 전체 목록 모두 같은 예외로 알린다. */
	@Test
	void aForbiddenAdminCallIsAConfigurationProblemEverywhere() {
		KeycloakAdminClient client = clientWithStatuses(HttpStatus.OK, HttpStatus.FORBIDDEN);

		for (Mono<?> call : List.of(client.listPeople(10), client.search("kim", 10), client.exists(SUBJECT),
				client.listEnabledUserSubjects(), client.users(), client.eventsConfig())) {
			StepVerifier.create(call)
					.expectErrorSatisfies(error -> assertThat(error)
							.isInstanceOfSatisfying(KeycloakAdminUnavailableException.class, unavailable -> {
								assertThat(unavailable.reason()).isEqualTo(KeycloakAdminUnavailableException.Reason.FORBIDDEN);
								assertThat(unavailable.summary()).startsWith("Keycloak 관리 권한 부족(/admin/realms/app-realm/");
							}))
					.verify();
		}
	}

	/** 일시 장애는 설정 문제로 단정하지 않는다 — Admin API 401(토큰이 먼저 무효화됨)과 5xx는 원래 예외 그대로다. */
	@Test
	void transientFailuresStayAsTheyAre() {
		for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.INTERNAL_SERVER_ERROR,
				HttpStatus.SERVICE_UNAVAILABLE)) {
			StepVerifier.create(clientWithStatuses(HttpStatus.OK, status).listPeople(10))
					.expectErrorSatisfies(error -> assertThat(error)
							.isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class)
							.isNotInstanceOf(KeycloakAdminUnavailableException.class))
					.verify();
		}
		StepVerifier.create(clientWithStatuses(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.OK).listPeople(10))
				.expectError(org.springframework.web.reactive.function.client.WebClientResponseException.class).verify();
	}

	/** 분류는 경로와 상태를 함께 본다 — 토큰 엔드포인트의 403, Admin API의 400은 설정 문제로 단정하지 않는다. */
	@Test
	void onlyTheExpectedStatusOnTheExpectedPathIsAConfigurationProblem() {
		StepVerifier.create(clientWithStatuses(HttpStatus.FORBIDDEN, HttpStatus.OK).listPeople(10))
				.expectErrorSatisfies(error -> assertThat(error).isNotInstanceOf(KeycloakAdminUnavailableException.class))
				.verify();
		StepVerifier.create(clientWithStatuses(HttpStatus.OK, HttpStatus.BAD_REQUEST).listPeople(10))
				.expectErrorSatisfies(error -> assertThat(error).isNotInstanceOf(KeycloakAdminUnavailableException.class))
				.verify();
	}

	/** 거부된 토큰 요청은 잠시 기억해 Keycloak에 같은 요청을 거듭 보내지 않는다(이름 목록이 사람 수만큼 부르는 경우). */
	@Test
	void aRejectedTokenRequestIsRememberedForAWhile() {
		KeycloakAdminClient client = clientWithStatuses(HttpStatus.UNAUTHORIZED, HttpStatus.OK);

		for (int call = 0; call < 3; call++) {
			StepVerifier.create(client.listPeople(10)).expectError(KeycloakAdminUnavailableException.class).verify();
		}

		assertThat(tokenRequests.get()).isEqualTo(1);
	}

	/**
	 * Admin API 401(Keycloak이 토큰을 먼저 무효화함)과 403(토큰의 역할이 모자람 — 역할을 고친 뒤에도 캐시한 토큰은 옛 역할)이면
	 * 캐시를 비워 다음 요청이 새 토큰을 받는다.
	 */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.EnumSource(value = HttpStatus.class, names = { "UNAUTHORIZED", "FORBIDDEN" })
	void anAdminUnauthorizedOrForbiddenDropsTheCachedTokenForTheNextCall(HttpStatus failure) {
		AtomicReference<HttpStatus> adminStatus = new AtomicReference<>(failure);
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().getPath().endsWith("/protocol/openid-connect/token")) {
				tokenRequests.incrementAndGet();
				return Mono.just(jsonResponse("{ \"access_token\": \"admin-token\", \"expires_in\": 3600 }"));
			}
			return Mono.just(adminStatus.get() == HttpStatus.OK ? jsonResponse("[]")
					: ClientResponse.create(adminStatus.get()).build());
		});
		KeycloakAdminClient client = new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET,
				Duration.ZERO);

		StepVerifier.create(client.listPeople(10)).expectError().verify();
		adminStatus.set(HttpStatus.OK);
		StepVerifier.create(client.listPeople(10)).expectNext(List.of()).verifyComplete();

		assertThat(tokenRequests.get()).isEqualTo(2);
	}

	/** 표시 이름은 설정 문제여도 지금처럼 빈 값으로 삼킨다 — 이름 하나 때문에 화면 전체가 깨지면 안 된다. */
	@Test
	void displayNameSwallowsAConfigurationProblem() {
		StepVerifier.create(clientWithStatuses(HttpStatus.UNAUTHORIZED, HttpStatus.OK).displayName(SUBJECT))
				.expectNext(Optional.empty()).verifyComplete();
		StepVerifier.create(clientWithStatuses(HttpStatus.OK, HttpStatus.FORBIDDEN).displayName(SUBJECT))
				.expectNext(Optional.empty()).verifyComplete();
	}

	/** 이름이 모두 비는 원인을 찾을 수 있게 설정 문제는 남기되, 같은 상태가 이어지는 동안 다시 남기지 않는다. */
	@Test
	void displayNameWarnsOnceWhileTheConfigurationProblemLasts() {
		AtomicReference<HttpStatus> adminStatus = new AtomicReference<>(HttpStatus.FORBIDDEN);
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			if (request.url().getPath().endsWith("/protocol/openid-connect/token")) {
				return Mono.just(jsonResponse("{ \"access_token\": \"admin-token\", \"expires_in\": 3600 }"));
			}
			return Mono.just(adminStatus.get() == HttpStatus.OK
					? jsonResponse("{ \"id\": \"" + SUBJECT + "\", \"username\": \"sujin\" }")
					: ClientResponse.create(adminStatus.get()).build());
		});
		KeycloakAdminClient client = new KeycloakAdminClient(builder, INTERNAL_URL, REALM, CLIENT_ID, CLIENT_SECRET,
				Duration.ZERO);
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KeycloakAdminClient.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			client.displayName("first").block();
			client.displayName("second").block();
			assertThat(appender.list).hasSize(1);
			// 회복하면 풀리고, 다시 문제가 생기면 다시 남긴다.
			adminStatus.set(HttpStatus.OK);
			client.displayName("third").block();
			adminStatus.set(HttpStatus.FORBIDDEN);
			client.displayName("fourth").block();
		} finally {
			logger.detachAppender(appender);
		}
		assertThat(appender.list).hasSize(2).allSatisfy(event -> assertThat(event.getFormattedMessage())
				.contains("Keycloak 관리 권한 부족("));
	}
}
