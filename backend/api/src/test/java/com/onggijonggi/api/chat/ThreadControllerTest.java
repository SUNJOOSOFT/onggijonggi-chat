package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.UserIdentityService;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.List;
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
 * Class Name : ThreadControllerTest.java
 * Description : DIRECT·COLLAB 공용 이력 조회 `GET /api/threads/{threadId}/messages`를
 *               검증한다(이슈 #219). DIRECT 쪽 시나리오는 `CollabThreadControllerTest`의
 *               `rejectsTheCollabHistoryAliasForAnActiveDirectOwner`와 같은 방식으로 thr·
 *               thr_mbr을 직접 심는다 — HTTP 채팅 흐름 전체를 거칠 필요가 없다. 채팅이 만든
 *               DIRECT 이력을 이 엔드포인트로 읽는 통합 시나리오는
 *               `ChatControllerTest.readsDirectHistoryFromTheCommonThreadEndpoint`가 계속 맡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, CollabRoomFixture.class,
		FakeKeycloakAdminConfig.class})
class ThreadControllerTest {

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
	void rejectsNegativeAfterSeq() {
		UUID ownerId = userIdentityService.resolveOrProvision("thread-history-negative").block();
		UUID threadId = UUID.randomUUID();
		thrRepository.save(Thr.direct(threadId, ownerId, "1:1 대화"));
		thrMbrRepository.save(new ThrMbr(threadId, ownerId, ThrMbrRole.OWNER, ownerId));

		restTestClient.get()
				.uri("/api/threads/{threadId}/messages?afterSeq=-1", threadId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + TestJwtSupport.signedJwt("thread-history-negative", List.of("USER")))
				.exchange()
				.expectStatus().isBadRequest();
	}

	@Test
	void returnsDirectThreadHistoryForItsOwner() {
		UUID ownerId = userIdentityService.resolveOrProvision("thread-history-owner").block();
		UUID threadId = UUID.randomUUID();
		thrRepository.save(Thr.direct(threadId, ownerId, "1:1 대화"));
		ThrMbr owner = thrMbrRepository.save(new ThrMbr(threadId, ownerId, ThrMbrRole.OWNER, ownerId));
		msgRepository.save(Msg.human(UUID.randomUUID(), threadId, 0, owner.getId(), "안녕 AI야"));

		restTestClient.get()
				.uri("/api/threads/{threadId}/messages", threadId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + TestJwtSupport.signedJwt("thread-history-owner", List.of("USER")))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].athKind").isEqualTo("HUMAN")
				.jsonPath("$[0].content").isEqualTo("안녕 AI야");
	}

	@Test
	void returnsCollabThreadHistoryToo() {
		UUID threadId = rooms.openRoom("thread-history-collab-owner");

		restTestClient.get()
				.uri("/api/threads/{threadId}/messages", threadId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + TestJwtSupport.signedJwt("thread-history-collab-owner", List.of("USER")))
				.exchange()
				.expectStatus().isOk();
	}

	@Test
	void rejectsMessageHistoryForNonParticipants() {
		UUID ownerId = userIdentityService.resolveOrProvision("thread-history-private-owner").block();
		UUID threadId = UUID.randomUUID();
		thrRepository.save(Thr.direct(threadId, ownerId, "1:1 대화"));
		thrMbrRepository.save(new ThrMbr(threadId, ownerId, ThrMbrRole.OWNER, ownerId));

		restTestClient.get()
				.uri("/api/threads/{threadId}/messages", threadId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + TestJwtSupport.signedJwt("thread-history-outsider", List.of("USER")))
				.exchange()
				.expectStatus().isNotFound();
	}

}
