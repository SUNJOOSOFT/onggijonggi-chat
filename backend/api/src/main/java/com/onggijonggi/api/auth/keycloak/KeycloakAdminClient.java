package com.onggijonggi.api.auth.keycloak;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Class Name : KeycloakAdminClient.java
 * Description : 다른 사용자의 표시 이름 조회(이슈 #128)와 실존 확인(이슈 #127)을 Keycloak Admin
 *               API로 한다. 요청의 JWT는
 *               호출자 본인의 claim만 담고 있어, 협업 스레드의 다른 참가자 이름은 이 경로로만 얻을 수
 *               있다. 로그인에 쓰는 것과 같은 클라이언트의 서비스 계정(client_credentials)으로 admin
 *               토큰을 받고, 만료 30초 전까지는 재사용한다.
 */
@Component
public class KeycloakAdminClient {

	private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(30);

	private final WebClient webClient;
	private final String realm;
	private final String clientId;
	private final String clientSecret;
	private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

	public KeycloakAdminClient(WebClient.Builder webClientBuilder,
			@Value("${app.keycloak.internal-url}") String internalUrl,
			@Value("${app.keycloak.realm}") String realm,
			@Value("${app.keycloak.admin.client-id}") String clientId,
			@Value("${app.keycloak.admin.client-secret}") String clientSecret) {
		this.webClient = webClientBuilder.baseUrl(internalUrl).build();
		this.realm = realm;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
	}

	/**
	 * subject(AppUser.keycloakSubj, JWT sub 클레임과 같은 값)로 표시 이름을 조회한다. app_user.id(내부
	 * UUID)와는 다른 값이라 호출부가 미리 keycloakSubj로 바꿔서 넘겨야 한다. 탈퇴로 못 찾은 경우뿐
	 * 아니라 토큰 발급 실패·타임아웃·5xx 등 Admin API 쪽 오류 전부를 빈 Optional로 삼킨다 — 표시
	 * 이름 하나 못 가져온 것 때문에 호출부의 스레드 목록 조회 전체가 죽으면 안 된다.
	 */
	public Mono<Optional<String>> displayName(String subject) {
		return adminToken()
				.flatMap(token -> lookupUser(subject, token))
				.onErrorResume(WebClientException.class, ignored -> Mono.just(Optional.empty()));
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
	 * 이름·이메일·username으로 계정을 찾는다(이슈 #172의 초대 대상 검색).
	 *
	 * 초대창이 subject(UUID)를 손으로 받던 것을 대체한다 — 사람이 그 값을 알 방법이 앱 안에
	 * 없었다. 결과의 subject는 화면이 그대로 초대 API에 넘기고, 사람은 표시 이름만 본다.
	 *
	 * 표시 이름은 displayName()과 같은 규약(username = OIDC preferred_username)을 쓴다.
	 *
	 * 오류는 displayName()처럼 삼키지 않고 전파한다. 검색이 빈 결과와 장애를 구분하지 못하면
	 * "그런 사람 없음"으로 보여 초대자가 헛물을 켠다 — exists()와 같은 판단이다.
	 *
	 * @param query 부분 일치 검색어. Keycloak이 username·email·firstName·lastName을 함께 본다
	 * @param max 최대 결과 수. 상한은 호출부가 정한다
	 */
	public Mono<List<KeycloakUserSummary>> search(String query, int max) {
		return adminToken()
				.flatMapMany(token -> webClient.get()
						.uri(builder -> builder.path("/admin/realms/{realm}/users")
								.queryParam("search", query)
								.queryParam("max", max)
								.queryParam("briefRepresentation", true)
								.build(realm))
						.headers(headers -> headers.setBearerAuth(token))
						.retrieve()
						.bodyToFlux(SearchedUser.class))
				.map(user -> new KeycloakUserSummary(user.id(), user.username()))
				.collectList();
	}

	private Mono<Optional<String>> lookupUser(String subject, String token) {
		return webClient.get()
				.uri("/admin/realms/{realm}/users/{id}", realm, subject)
				.headers(headers -> headers.setBearerAuth(token))
				.retrieve()
				.bodyToMono(UserRepresentation.class)
				.map(user -> Optional.ofNullable(user.username()));
	}

	private Mono<String> adminToken() {
		CachedToken cached = cachedToken.get();
		if (cached != null && cached.isValidAt(Instant.now())) {
			return Mono.just(cached.value());
		}
		return fetchToken().doOnNext(cachedToken::set).map(CachedToken::value);
	}

	private Mono<CachedToken> fetchToken() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");
		form.add("client_id", clientId);
		form.add("client_secret", clientSecret);
		return webClient.post()
				.uri("/realms/{realm}/protocol/openid-connect/token", realm)
				.body(BodyInserters.fromFormData(form))
				.retrieve()
				.bodyToMono(TokenResponse.class)
				.map(response -> new CachedToken(response.accessToken(),
						Instant.now().plusSeconds(response.expiresIn()).minus(EXPIRY_SAFETY_MARGIN)));
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

	/** username을 표시 이름으로 쓴다 — OIDC의 preferred_username과 같은 값이다. */
	private record UserRepresentation(String username) {
	}

	/** 검색 응답 항목. briefRepresentation이라 id·username 외에는 오지 않는다. */
	private record SearchedUser(String id, String username) {
	}

}
