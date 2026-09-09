package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.auth.UserIdentityService;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : CollabThreadControllerTest.java
 * Description : GET /api/collab/threads가 호출자를 기준으로 방을 걸러 내려주는지 검증한다(이슈 #22).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ChatControllerTest.FakeChatModelConfig.class, FakeJwtDecoderConfig.class, CollabRoomFixture.class,
		FakeKeycloakAdminConfig.class})
class CollabThreadControllerTest {

	@LocalServerPort
	private int port;

	@Autowired
	private CollabRoomFixture.CollabRooms rooms;

	@Autowired
	private MsgRepository msgRepository;

	@Autowired
	private ThrMbrRepository thrMbrRepository;

	@Autowired
	private ThrRepository thrRepository;

	@Autowired
	private UserIdentityService userIdentityService;

	private RestTestClient restTestClient;

	@BeforeEach
	void setUp() {
		restTestClient = RestTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	@Test
	void returnsOnlyThreadsTheCallerParticipatesIn() {
		UUID joined = rooms.openRoom("threads-owner", "threads-member");
		rooms.openRoom("threads-stranger");

		String body = listThreadsAs("threads-member");

		assertThat(body).contains(joined.toString());
		assertThat(body).contains("\"title\":\"테스트 협업방\"");
	}

	/** 참가한 방이 하나도 없으면 404가 아니라 빈 목록이다 — 화면이 "아직 방이 없다"를 그리는 근거다. */
	@Test
	void returnsEmptyListWhenCallerJoinedNothing() {
		rooms.openRoom("threads-other-owner");

		assertThat(listThreadsAs("threads-loner")).isEqualTo("[]");
	}

	@Test
	void createsAnActiveCollabThreadWithTheCreatorAsItsOnlyOwner() {
		String title = "  새 \"협업방\"  ";

		createThread("create-owner", title)
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.id").isNotEmpty();

		Thr created = threadsNamed(title).get(0);
		UUID ownerId = userIdentityService.resolveOrProvision("create-owner").block();
		List<ThrMbr> members = thrMbrRepository.findByThrIdAndStatus(created.getId(), ThrMbrStatus.ACTIVE);
		assertThat(created.getTitle()).isEqualTo(title);
		assertThat(created.getCreatedUserId()).isEqualTo(ownerId);
		assertThat(members).singleElement().satisfies(member -> {
			assertThat(member.getUserId()).isEqualTo(ownerId);
			assertThat(member.getCreatedByUserId()).isEqualTo(ownerId);
			assertThat(member.getRole()).isEqualTo(ThrMbrRole.OWNER);
		});
	}

	@Test
	void rejectsBlankAndTooLongTitlesWithoutCreatingAThread() {
		long before = thrRepository.count();

		createThread("create-invalid", "   ")
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
		createThread("create-invalid", "a".repeat(256))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");

		assertThat(thrRepository.count()).isEqualTo(before);
	}

	@Test
	void allowsDifferentRoomsToHaveTheSameTitle() {
		String title = "중복 가능한 제목";

		createThread("create-duplicate", title).expectStatus().isCreated();
		createThread("create-duplicate", title).expectStatus().isCreated();

		assertThat(threadsNamed(title)).hasSize(2)
				.extracting(Thr::getId)
				.doesNotHaveDuplicates();
	}

	/**
	* app_user에는 이름 컬럼이 없어(이슈 #22 코멘트) Keycloak을 정본으로 표시 이름을 채운다(이슈 #128).
	* FakeKeycloakAdminConfig가 subject를 그대로 표시 이름으로 돌려주므로 subject가 그대로 보인다.
	*/
	@Test
	void fillsParticipantsWithDisplayNamesFromKeycloak() {
		rooms.openRoom("threads-participants-owner", "threads-participants-member");

		String body = listThreadsAs("threads-participants-owner");

		assertThat(body).contains("\"participants\":[\"threads-participants-owner\",\"threads-participants-member\"]");
	}

	/** ARCHIVED는 삭제가 아니라 목록에서 빠지는 것뿐이라, 별도 보관함 엔드포인트로 계속 찾을 수 있다(#131). */
	@Test
	void excludesArchivedThreadsFromTheDefaultListButKeepsThemInTheArchive() {
		UUID thrId = rooms.openRoom("archive-owner", "archive-member");
		rooms.archiveRoom(thrId);

		assertThat(listThreadsAs("archive-member")).doesNotContain(thrId.toString());

		String archivedBody = restTestClient.get()
				.uri("/api/collab/threads/archived")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("archive-member", List.of("USER")))
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();
		assertThat(archivedBody).contains(thrId.toString());
	}

	@Test
	void ownerCanLockAndThenArchiveAThread() {
		UUID thrId = rooms.openRoom("lifecycle-owner", "lifecycle-member");

		restTestClient.put()
				.uri("/api/collab/threads/{threadId}/lock", thrId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("lifecycle-owner", List.of("USER")))
				.exchange()
				.expectStatus().isNoContent();

		restTestClient.put()
				.uri("/api/collab/threads/{threadId}/archive", thrId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("lifecycle-owner", List.of("USER")))
				.exchange()
				.expectStatus().isNoContent();

		assertThat(listThreadsAs("lifecycle-member")).doesNotContain(thrId.toString());
	}

	@Test
	void nonOwnerCannotLockAThread() {
		UUID thrId = rooms.openRoom("lifecycle-owner-2", "lifecycle-member-2");

		restTestClient.put()
				.uri("/api/collab/threads/{threadId}/lock", thrId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + TestJwtSupport.signedJwt("lifecycle-member-2", List.of("USER")))
				.exchange()
				.expectStatus().isForbidden();
	}

	/**
	* thr_mbr·msg의 FK on delete cascade는 실제 Postgres 스키마(Flyway)에만 있다 — 이 테스트는
	* Flyway를 끄고 Hibernate가 엔티티 매핑만으로 즉석 스키마를 만들어(application-test.properties),
	* Thr·ThrMbr·Msg 사이에 연관관계 매핑이 없는 이 코드베이스 관례상 FK 자체가 안 생긴다. 그래서
	* cascade 여부가 아니라 삭제 자체의 동작(OWNER 확인·204·thr 행 제거)만 여기서 확인한다.
	*/
	@Test
	void ownerCanDeleteAThread() {
		UUID thrId = rooms.openRoom("delete-owner", "delete-member");

		restTestClient.delete()
				.uri("/api/collab/threads/{threadId}", thrId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("delete-owner", List.of("USER")))
				.exchange()
				.expectStatus().isNoContent();

		assertThat(thrRepository.findById(thrId)).isEmpty();
	}

	@Test
	void nonOwnerCannotDeleteAThread() {
		UUID thrId = rooms.openRoom("delete-owner-2", "delete-member-2");

		restTestClient.delete()
				.uri("/api/collab/threads/{threadId}", thrId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + TestJwtSupport.signedJwt("delete-member-2", List.of("USER")))
				.exchange()
				.expectStatus().isForbidden();
	}

	@Test
	void returnsSavedMessagesInSeqOrderForAParticipant() {
		UUID thrId = rooms.openRoom("messages-owner", "messages-member");
		UUID ownerId = userIdentityService.resolveOrProvision("messages-owner").block();
		ThrMbr ownerMembership = thrMbrRepository.findByThrIdAndUserIdAndStatus(thrId, ownerId, ThrMbrStatus.ACTIVE)
				.orElseThrow();
		msgRepository.save(Msg.human(thrId, 0, ownerMembership.getId(), "안녕 AI야"));
		Msg agentMsg = Msg.pendingAgent(thrId, 1);
		agentMsg.complete("안녕하세요! 무엇을 도와드릴까요?");
		msgRepository.save(agentMsg);

		String body = restTestClient.get()
				.uri("/api/collab/threads/{threadId}/messages", thrId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + TestJwtSupport.signedJwt("messages-member", List.of("USER")))
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();

		assertThat(body).contains("\"안녕 AI야\"");
		assertThat(body).contains("\"안녕하세요! 무엇을 도와드릴까요?\"");
		assertThat(body.indexOf("안녕 AI야")).isLessThan(body.indexOf("안녕하세요! 무엇을 도와드릴까요?"));
	}

	/**
	* FakeKeycloakAdminConfig가 subject를 그대로 표시 이름으로 돌려주므로(이슈 #128과 같은 방식),
	* HUMAN 메시지는 작성자의 subject가 표시 이름 자리에 그대로 보인다. AGENT는 thrMbrId가 없어
	* 표시 이름도 null이다(이슈 #147).
	*/
	@Test
	void includesAuthorDisplayNameForHumanMessagesButNotForAgentMessages() {
		UUID thrId = rooms.openRoom("author-owner", "author-member");
		UUID memberId = userIdentityService.resolveOrProvision("author-member").block();
		ThrMbr memberMembership = thrMbrRepository.findByThrIdAndUserIdAndStatus(thrId, memberId, ThrMbrStatus.ACTIVE)
				.orElseThrow();
		msgRepository.save(Msg.human(thrId, 0, memberMembership.getId(), "질문 있어요"));
		Msg agentMsg = Msg.pendingAgent(thrId, 1);
		agentMsg.complete("답변입니다");
		msgRepository.save(agentMsg);

		String body = restTestClient.get()
				.uri("/api/collab/threads/{threadId}/messages", thrId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt("author-owner", List.of("USER")))
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();

		assertThat(body).contains("\"authorDisplayName\":\"author-member\"");
		assertThat(body).contains("\"authorDisplayName\":null");
	}

	@Test
	void rejectsMessageHistoryForNonParticipants() {
		UUID thrId = rooms.openRoom("messages-private-owner");

		restTestClient.get()
				.uri("/api/collab/threads/{threadId}/messages", thrId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + TestJwtSupport.signedJwt("messages-outsider", List.of("USER")))
				.exchange()
				.expectStatus().isNotFound();
	}

	private String listThreadsAs(String subject) {
		return restTestClient.get()
				.uri("/api/collab/threads")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER")))
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();
	}

	private RestTestClient.ResponseSpec createThread(String subject, String title) {
		return restTestClient.post()
				.uri("/api/collab/threads")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER")))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.body(Map.of("title", title))
				.exchange();
	}

	private List<Thr> threadsNamed(String title) {
		return thrRepository.findAll().stream().filter(thread -> thread.getTitle().equals(title)).toList();
	}

}
