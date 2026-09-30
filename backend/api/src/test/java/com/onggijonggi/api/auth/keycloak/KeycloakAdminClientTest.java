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

}
