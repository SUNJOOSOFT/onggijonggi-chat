package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : ThreadParticipantControllerTest.java
 * Description : 참여자 초대·제거·소유권 위임·조회를 호출자 권한별로 검증한다(이슈 #20).
 *
 *               세 상태를 구분해서 본다 — 참가자가 아니면 방의 존재를 알리지 않으려고 404,
 *               참가자지만 OWNER가 아니면 403, 자격은 되는데 지금 상태로 할 수 없는 일이면 409다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ChatControllerTest.FakeChatModelConfig.class, FakeJwtDecoderConfig.class, CollabRoomFixture.class,
		FakeKeycloakAdminConfig.class})
class ThreadParticipantControllerTest {

	@LocalServerPort
	private int port;

	@Autowired
	private CollabRoomFixture.CollabRooms rooms;

	private RestTestClient restTestClient;

	@BeforeEach
	void setUp() {
		restTestClient = RestTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	@Test
	void ownerInvitesAndTheParticipantShowsUpInTheList() {
		UUID threadId = rooms.openRoom("invite-owner");
		rooms.user("invite-guest");

		invite(threadId, "invite-owner", "invite-guest").expectStatus().isNoContent();

		assertThat(participantsAs(threadId, "invite-owner"))
				.contains("\"subject\":\"invite-guest\"", "\"role\":\"MEMBER\"");
	}

	/** 이미 참가 중인 사람을 다시 초대해도 성공이다 — 초대가 이루려던 상태가 이미 성립해 있다. */
	@Test
	void invitingSomeoneAlreadyInTheRoomChangesNothing() {
		UUID threadId = rooms.openRoom("repeat-owner", "repeat-member");

		invite(threadId, "repeat-owner", "repeat-member").expectStatus().isNoContent();

		restTestClient.get()
				.uri("/api/collab/threads/{threadId}/participants", threadId)
				.header(HttpHeaders.AUTHORIZATION, bearer("repeat-owner"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(2);
	}

	/** 강퇴를 밴으로 다루지 않는다. 과거 행은 남고 새 참가 행이 생긴다. */
	@Test
	void aRevokedParticipantCanBeInvitedAgain() {
		UUID threadId = rooms.openRoom("reinvite-owner", "reinvite-member");
		remove(threadId, "reinvite-owner", "reinvite-member").expectStatus().isNoContent();

		invite(threadId, "reinvite-owner", "reinvite-member").expectStatus().isNoContent();

		assertThat(participantsAs(threadId, "reinvite-owner")).contains("\"subject\":\"reinvite-member\"");
	}

	@Test
	void aMemberCannotInvite() {
		UUID threadId = rooms.openRoom("member-invite-owner", "member-invite-member");
		rooms.user("member-invite-guest");

		invite(threadId, "member-invite-member", "member-invite-guest")
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
				.expectBody(String.class).value(body -> assertThat(body).contains("\"code\":\"FORBIDDEN\""));
	}

	/** 참가자가 아닌 사람에게는 방의 존재 자체를 알리지 않는다. */
	@Test
	void anOutsiderGetsNotFoundInsteadOfForbidden() {
		UUID threadId = rooms.openRoom("outsider-owner");
		rooms.user("outsider-guest");

		invite(threadId, "outsider-stranger", "outsider-guest")
				.expectStatus().isNotFound()
				.expectBody(String.class).value(body -> assertThat(body).contains("\"code\":\"NOT_FOUND\""));
	}

	/**
	* 한 번도 로그인하지 않은 사람도 초대할 수 있다(이슈 #127). app_user 행을 그 자리에서 만들지는
	* 않고 대기 초대로 남긴다 — 오타로 만든 유령 계정이 영구히 남지 않게 하려는 것이다.
	*/
	@Test
	void invitingSomeoneWhoNeverLoggedInLeavesAPendingInvitation() {
		UUID threadId = rooms.openRoom("pending-owner");

		invite(threadId, "pending-owner", "never-logged-in").expectStatus().isNoContent();

		assertThat(rooms.pendingInvitations(threadId)).containsExactly("never-logged-in");
		// 초대만으로 계정이 생기지는 않는다.
		assertThat(rooms.userExists("never-logged-in")).isFalse();
	}

	/** Keycloak에 없는 subject는 초대할 수 없다 — 오타가 영원히 발동하지 않는 초대로 남는 것을 막는다. */
	@Test
	void invitingASubjectMissingFromKeycloakIsNotFound() {
		UUID threadId = rooms.openRoom("ghost-check-owner");

		invite(threadId, "ghost-check-owner", "ghost-nobody").expectStatus().isNotFound();

		assertThat(rooms.pendingInvitations(threadId)).isEmpty();
	}

	@Test
	void ownerRevokesAMemberAndTheReasonIsRecorded() {
		UUID threadId = rooms.openRoom("revoke-owner", "revoke-member");

		remove(threadId, "revoke-owner", "revoke-member").expectStatus().isNoContent();

		assertThat(participantsAs(threadId, "revoke-owner")).doesNotContain("revoke-member");
		ThrMbr ended = rooms.lastParticipation(threadId, "revoke-member").orElseThrow();
		assertThat(ended.getStatus()).isEqualTo(ThrMbrStatus.REVOKED);
		assertThat(ended.getEndRsn()).isEqualTo("OWNER_REVOKED");
	}

	@Test
	void aMemberCanLeaveOnItsOwn() {
		UUID threadId = rooms.openRoom("leave-owner", "leave-member");

		remove(threadId, "leave-member", "leave-member").expectStatus().isNoContent();

		ThrMbr ended = rooms.lastParticipation(threadId, "leave-member").orElseThrow();
		assertThat(ended.getStatus()).isEqualTo(ThrMbrStatus.LEFT);
		assertThat(ended.getEndRsn()).isEqualTo("SELF_LEAVE");
	}

	@Test
	void aMemberCannotRemoveAnotherMember() {
		UUID threadId = rooms.openRoom("peer-owner", "peer-one", "peer-two");

		remove(threadId, "peer-one", "peer-two").expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void removingSomeoneWhoIsNotInTheRoomIsNotFound() {
		UUID threadId = rooms.openRoom("gone-owner");
		rooms.user("gone-guest");

		remove(threadId, "gone-owner", "gone-guest").expectStatus().isNotFound();
	}

	/** 권한이 없어서가 아니라 지금 상태로는 안 되는 일이라 409다. */
	@Test
	void theOwnerCannotLeaveBeforeHandingTheRoomOver() {
		UUID threadId = rooms.openRoom("stuck-owner", "stuck-member");

		remove(threadId, "stuck-owner", "stuck-owner")
				.expectStatus().isEqualTo(HttpStatus.CONFLICT)
				.expectBody(String.class)
				.value(body -> assertThat(body).contains("\"code\":\"PARTICIPANT_STATE_CONFLICT\""));
	}

	@Test
	void transferSwapsTheTwoRoles() {
		UUID threadId = rooms.openRoom("hand-owner", "hand-member");

		transferOwner(threadId, "hand-owner", "hand-member").expectStatus().isNoContent();

		assertThat(rooms.roleOf(threadId, "hand-member")).isEqualTo(ThrMbrRole.OWNER);
		assertThat(rooms.roleOf(threadId, "hand-owner")).isEqualTo(ThrMbrRole.MEMBER);
	}

	/** 두 규칙이 맞물리는 자리 — 위임을 마친 사람은 그제야 방을 나갈 수 있다. */
	@Test
	void theFormerOwnerCanLeaveOnceTheRoomHasANewOwner() {
		UUID threadId = rooms.openRoom("exit-owner", "exit-member");
		transferOwner(threadId, "exit-owner", "exit-member").expectStatus().isNoContent();

		remove(threadId, "exit-owner", "exit-owner").expectStatus().isNoContent();

		assertThat(participantsAs(threadId, "exit-member")).doesNotContain("exit-owner");
	}

	@Test
	void transferringToSomeoneOutsideTheRoomIsNotFound() {
		UUID threadId = rooms.openRoom("hand-nowhere-owner");
		rooms.user("hand-nowhere-guest");

		transferOwner(threadId, "hand-nowhere-owner", "hand-nowhere-guest").expectStatus().isNotFound();
	}

	@Test
	void aMemberCannotTransferOwnership() {
		UUID threadId = rooms.openRoom("hand-deny-owner", "hand-deny-one", "hand-deny-two");

		transferOwner(threadId, "hand-deny-one", "hand-deny-two")
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	* 비참여자에게 404를 주는 규칙은 세 메서드가 공유하지만, remove는 자기 자신인지 판정이
	* subject 비교로 바뀌면서(순서 버그 수정) 내부 경로가 나머지 둘과 달라졌다 — 별도로 잠가둔다.
	*/
	@Test
	void anOutsiderCannotRemoveAnyone() {
		UUID threadId = rooms.openRoom("remove-outsider-owner", "remove-outsider-member");
		rooms.user("remove-outsider-stranger");

		remove(threadId, "remove-outsider-stranger", "remove-outsider-member")
				.expectStatus().isNotFound();
	}

	@Test
	void anOutsiderCannotTransferOwnership() {
		UUID threadId = rooms.openRoom("transfer-outsider-owner", "transfer-outsider-member");
		rooms.user("transfer-outsider-stranger");

		transferOwner(threadId, "transfer-outsider-stranger", "transfer-outsider-member")
				.expectStatus().isNotFound();
	}

	@Test
	void invitingWithABlankSubjectIsRejected() {
		UUID threadId = rooms.openRoom("invite-blank-owner");

		invite(threadId, "invite-blank-owner", "")
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void transferringWithABlankSubjectIsRejected() {
		UUID threadId = rooms.openRoom("transfer-blank-owner");

		transferOwner(threadId, "transfer-blank-owner", "")
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
	}

	/** 명단은 참가자면 누구나 본다 — 제거·위임 대상을 지목하려면 먼저 누가 있는지 알아야 한다. */
	@Test
	void anyParticipantCanSeeTheRoster() {
		UUID threadId = rooms.openRoom("roster-owner", "roster-member");

		// role·subject를 인덱스로 짝지어 확인한다 — 문자열에 둘 다 나타나기만 하면 통과하는
		// 검증은 role이 서로 뒤바뀐 응답도 놓친다. 서비스가 role 오름차순(OWNER 먼저)으로
		// 정렬해 내려주므로 순서가 결정적이다.
		restTestClient.get()
				.uri("/api/collab/threads/{threadId}/participants", threadId)
				.header(HttpHeaders.AUTHORIZATION, bearer("roster-member"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].subject").isEqualTo("roster-owner")
				.jsonPath("$[0].role").isEqualTo("OWNER")
				.jsonPath("$[1].subject").isEqualTo("roster-member")
				.jsonPath("$[1].role").isEqualTo("MEMBER");
	}

	/**
	* self는 호출자 자신의 행에만 서고, displayName은 실 Keycloak이 없는 테스트 환경에서
	* KeycloakAdminClient.displayName()이 WebClientException을 삼키고 빈 값을 돌려주므로
	* subject로 대체된다(KeycloakAdminClient 클래스 주석 참고) — 둘 다 결정적으로 검증 가능하다.
	*/
	@Test
	void theRosterMarksTheCallersOwnRowAndFillsDisplayNames() {
		UUID threadId = rooms.openRoom("self-owner", "self-member");

		restTestClient.get()
				.uri("/api/collab/threads/{threadId}/participants", threadId)
				.header(HttpHeaders.AUTHORIZATION, bearer("self-member"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].self").isEqualTo(false)
				.jsonPath("$[0].displayName").isEqualTo("self-owner")
				.jsonPath("$[1].self").isEqualTo(true)
				.jsonPath("$[1].displayName").isEqualTo("self-member");
	}

	@Test
	void anOutsiderCannotSeeTheRoster() {
		UUID threadId = rooms.openRoom("roster-closed-owner");
		rooms.user("roster-outsider");

		restTestClient.get()
				.uri("/api/collab/threads/{threadId}/participants", threadId)
				.header(HttpHeaders.AUTHORIZATION, bearer("roster-outsider"))
				.exchange()
				.expectStatus().isNotFound();
	}

	private RestTestClient.ResponseSpec invite(UUID threadId, String actor, String targetSubject) {
		return restTestClient.post()
				.uri("/api/collab/threads/{threadId}/participants", threadId)
				.header(HttpHeaders.AUTHORIZATION, bearer(actor))
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"subject\":\"" + targetSubject + "\"}")
				.exchange();
	}

	private RestTestClient.ResponseSpec remove(UUID threadId, String actor, String targetSubject) {
		return restTestClient.delete()
				.uri("/api/collab/threads/{threadId}/participants/{subject}", threadId, targetSubject)
				.header(HttpHeaders.AUTHORIZATION, bearer(actor))
				.exchange();
	}

	private RestTestClient.ResponseSpec transferOwner(UUID threadId, String actor, String targetSubject) {
		return restTestClient.put()
				.uri("/api/collab/threads/{threadId}/owner", threadId)
				.header(HttpHeaders.AUTHORIZATION, bearer(actor))
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"subject\":\"" + targetSubject + "\"}")
				.exchange();
	}

	private String participantsAs(UUID threadId, String actor) {
		return restTestClient.get()
				.uri("/api/collab/threads/{threadId}/participants", threadId)
				.header(HttpHeaders.AUTHORIZATION, bearer(actor))
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();
	}

	private String bearer(String subject) {
		return "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER"));
	}

}
