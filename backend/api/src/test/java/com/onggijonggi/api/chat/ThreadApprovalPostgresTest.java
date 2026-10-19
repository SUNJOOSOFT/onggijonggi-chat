package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Class Name : ThreadApprovalPostgresTest.java
 * Description : 실제 최신 Flyway·PostgreSQL에서 협업방 승인 요청의 생성·재전송·처리·가시성·방 상태·삭제 연쇄를 검증한다(#407).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class ThreadApprovalPostgresTest {
	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("thread_apv").withUsername("test").withPassword("test");

	@DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.flyway.enabled", () -> "true");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		r.add("app.rbac.workspace-setup-path", () -> Path.of("../../infra/config/workspace-setup.default.yml").toAbsolutePath().normalize().toString());
	}

	@Autowired ThreadApprovalService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired ThrRepository threads;
	@Autowired ThrMbrRepository members;
	@Autowired MsgRepository messages;
	@Autowired RbacBootstrapService bootstrap;
	@MockitoBean ThreadSourceStorage storage;
	@MockitoBean WorkspaceAuthorizer authorizer;
	CurrentActor requester;
	CurrentActor target;
	CurrentActor bystander;
	UUID room;
	UUID tenant;
	UUID node;
	UUID message;

	@BeforeEach void setup() {
		reset(storage, authorizer);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(true);
		bootstrap.runCurrentConfiguration();
		jdbc.update("delete from thr");
		tenant = jdbc.queryForObject("select id from tnn where tnn_key='ogjg'", UUID.class);
		node = jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class);
		requester = actor(); target = actor(); bystander = actor();
		Thr value = Thr.collab(requester.userId(), "승인 방");
		value.placeIn(tenant, node);
		threads.saveAndFlush(value); room = value.getId();
		ThrMbr first = members.saveAndFlush(new ThrMbr(room, requester.userId(), ThrMbrRole.OWNER, requester.userId()));
		members.saveAndFlush(new ThrMbr(room, target.userId(), ThrMbrRole.MEMBER, requester.userId()));
		members.saveAndFlush(new ThrMbr(room, bystander.userId(), ThrMbrRole.MEMBER, requester.userId()));
		message = messages.saveAndFlush(Msg.human(UUID.randomUUID(), room, nextSeq(), first.getId(), "개정안 제안")).getId();
	}

	@Test void requestIsPendingAndVisibleOnlyToRequesterAndTarget() {
		UUID id = UUID.randomUUID();
		var created = service.request(room, id, message, target.subject(), requester);
		assertThat(created.status()).isEqualTo("PENDING");
		assertThat(created.requesterSubject()).isEqualTo(requester.subject());
		assertThat(created.targetSubject()).isEqualTo(target.subject());
		assertThat(created.canDecide()).isFalse();
		assertThat(service.list(room, requester)).extracting(ThreadApprovalView::id).containsExactly(id);
		var seenByTarget = service.list(room, target);
		assertThat(seenByTarget).extracting(ThreadApprovalView::id).containsExactly(id);
		assertThat(seenByTarget.get(0).canDecide()).isTrue();
		assertThat(service.list(room, bystander)).isEmpty();
	}

	@Test void resendingTheSameIdKeepsOneRequestAndADifferentContentConflicts() {
		UUID id = UUID.randomUUID();
		var first = service.request(room, id, message, target.subject(), requester);
		assertThat(service.request(room, id, message, target.subject(), requester)).isEqualTo(first);
		assertThat(jdbc.queryForObject("select count(*) from thr_apv", Integer.class)).isEqualTo(1);
		status(() -> service.request(room, id, message, bystander.subject(), requester), HttpStatus.CONFLICT);
		status(() -> service.request(room, id, message, target.subject(), bystander), HttpStatus.CONFLICT);
	}

	@Test void aMessageWithAPendingRequestRefusesAnotherAndTheSameIdIsStillAResend() {
		UUID first = UUID.randomUUID();
		var created = service.request(room, first, message, target.subject(), requester);
		// 다른 요청자·다른 대상이어도 같은 원글에 처리 전 요청이 있으면 거절한다.
		assertThatThrownBy(() -> service.request(room, UUID.randomUUID(), message, target.subject(), requester))
				.isInstanceOfSatisfying(ThreadApprovalException.class, error -> {
					assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(error.getCode()).isEqualTo("APPROVAL_ALREADY_PENDING");
				});
		status(() -> service.request(room, UUID.randomUUID(), message, bystander.subject(), target), HttpStatus.CONFLICT);
		// 같은 id의 재전송은 거절이 아니라 기존 요청을 돌려준다.
		assertThat(service.request(room, first, message, target.subject(), requester)).isEqualTo(created);
		assertThat(jdbc.queryForObject("select count(*) from thr_apv where msg_id=?", Integer.class, message)).isEqualTo(1);
		assertThat(lineEvents()).containsExactly("REQUESTED");
	}

	@Test void aMessageWhoseRequestEndedCanBeRequestedAgainAndOtherMessagesAreNotAffected() {
		UUID approved = UUID.randomUUID();
		service.request(room, approved, message, target.subject(), requester);
		service.decide(room, approved, true, null, target);
		UUID again = UUID.randomUUID();
		assertThat(service.request(room, again, message, target.subject(), requester).status()).isEqualTo("PENDING");
		service.decide(room, again, false, "다시 확인", target);
		assertThat(service.request(room, UUID.randomUUID(), message, bystander.subject(), requester).status()).isEqualTo("PENDING");
		// 대기 요청이 있는 원글과 다른 원글은 막지 않는다.
		UUID agent = messages.saveAndFlush(Msg.completedAgent(UUID.randomUUID(), room, nextSeq(), "AI 개정안")).getId();
		assertThat(service.request(room, UUID.randomUUID(), agent, target.subject(), requester).messageId()).isEqualTo(agent);
		assertThat(jdbc.queryForObject("select count(*) from thr_apv where status='PENDING'", Integer.class)).isEqualTo(2);
	}

	@Test void simultaneousRequestsForOneMessageLeaveExactlyOnePending() throws Exception {
		int callers = 6;
		var pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
		var start = new java.util.concurrent.CountDownLatch(1);
		try {
			java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
			for (int i = 0; i < callers; i++) {
				UUID id = UUID.randomUUID();
				results.add(pool.submit(() -> {
					start.await();
					try {
						service.request(room, id, message, target.subject(), requester);
						return true;
					} catch (ThreadApprovalException refused) {
						return false;
					}
				}));
			}
			start.countDown();
			int accepted = 0;
			for (var result : results) if (result.get(30, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
			assertThat(accepted).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("select count(*) from thr_apv where msg_id=? and status='PENDING'", Integer.class, message))
				.isEqualTo(1);
		assertThat(lineEvents()).containsExactly("REQUESTED");
	}

	@Test void onlyTheTargetCanDecideAndTheSameDecisionIsIdempotent() {
		UUID id = UUID.randomUUID();
		service.request(room, id, message, target.subject(), requester);
		status(() -> service.decide(room, id, true, null, requester), HttpStatus.NOT_FOUND);
		status(() -> service.decide(room, id, true, null, bystander), HttpStatus.NOT_FOUND);
		var approved = service.decide(room, id, true, null, target);
		assertThat(approved.status()).isEqualTo("APPROVED");
		assertThat(approved.completedAt()).isNotNull();
		assertThat(approved.canDecide()).isFalse();
		assertThat(service.decide(room, id, true, null, target).completedAt()).isEqualTo(approved.completedAt());
		status(() -> service.decide(room, id, false, null, target), HttpStatus.CONFLICT);
		assertThat(service.list(room, requester).get(0).status()).isEqualTo("APPROVED");
	}

	@Test void rejectionKeepsTheReasonButApprovalAndOverlongReasonsAreRefused() {
		UUID rejected = UUID.randomUUID();
		service.request(room, rejected, message, target.subject(), requester);
		var result = service.decide(room, rejected, false, "  기준 금액을 다시 확인해 주세요  ", target);
		assertThat(result.status()).isEqualTo("REJECTED");
		assertThat(result.reason()).isEqualTo("기준 금액을 다시 확인해 주세요");
		UUID other = UUID.randomUUID();
		service.request(room, other, message, target.subject(), requester);
		status(() -> service.decide(room, other, true, "승인인데 사유", target), HttpStatus.BAD_REQUEST);
		status(() -> service.decide(room, other, false, "가".repeat(ThreadApprovalService.MAX_REASON_LENGTH + 1), target),
				HttpStatus.BAD_REQUEST);
		assertThat(service.decide(room, other, false, "   ", target).reason()).isNull();
	}

	@Test void theTargetMustBeAnotherActiveParticipantWhoCanSeeTheRoom() {
		status(() -> service.request(room, UUID.randomUUID(), message, requester.subject(), requester), HttpStatus.BAD_REQUEST);
		status(() -> service.request(room, UUID.randomUUID(), message, "no-such-subject", requester), HttpStatus.BAD_REQUEST);
		CurrentActor outsider = actor();
		status(() -> service.request(room, UUID.randomUUID(), message, outsider.subject(), requester), HttpStatus.BAD_REQUEST);
		jdbc.update("update thr_mbr set status='LEFT',ended_at=now(),end_rsn='LEFT' where thr_id=? and user_id=?", room, bystander.userId());
		status(() -> service.request(room, UUID.randomUUID(), message, bystander.subject(), requester), HttpStatus.BAD_REQUEST);
		when(authorizer.canViewBlocking(org.mockito.ArgumentMatchers.eq(target.subject()), any())).thenReturn(false);
		status(() -> service.request(room, UUID.randomUUID(), message, target.subject(), requester), HttpStatus.BAD_REQUEST);
	}

	@Test void onlyCompletedHumanOrAgentMessagesOfThisRoomCanBeApproved() {
		UUID system = messages.saveAndFlush(Msg.system(room, nextSeq(), "")).getId();
		UUID pending = messages.saveAndFlush(Msg.pendingAgent(UUID.randomUUID(), room, nextSeq())).getId();
		UUID agent = messages.saveAndFlush(Msg.completedAgent(UUID.randomUUID(), room, nextSeq(), "AI 개정안")).getId();
		status(() -> service.request(room, UUID.randomUUID(), system, target.subject(), requester), HttpStatus.BAD_REQUEST);
		status(() -> service.request(room, UUID.randomUUID(), pending, target.subject(), requester), HttpStatus.BAD_REQUEST);
		status(() -> service.request(room, UUID.randomUUID(), UUID.randomUUID(), target.subject(), requester), HttpStatus.NOT_FOUND);
		assertThat(service.request(room, UUID.randomUUID(), agent, target.subject(), requester).messageId()).isEqualTo(agent);
		Thr other = Thr.collab(requester.userId(), "다른 방");
		other.placeIn(tenant, node);
		threads.saveAndFlush(other);
		ThrMbr otherOwner = members.saveAndFlush(new ThrMbr(other.getId(), requester.userId(), ThrMbrRole.OWNER, requester.userId()));
		members.saveAndFlush(new ThrMbr(other.getId(), target.userId(), ThrMbrRole.MEMBER, requester.userId()));
		UUID foreign = messages.saveAndFlush(Msg.human(UUID.randomUUID(), other.getId(), 1L, otherOwner.getId(), "다른 방 메시지")).getId();
		status(() -> service.request(room, UUID.randomUUID(), foreign, target.subject(), requester), HttpStatus.NOT_FOUND);
	}

	@Test void lockedRoomIsReadableButNeverWritable() {
		UUID id = UUID.randomUUID();
		service.request(room, id, message, target.subject(), requester);
		jdbc.update("update thr set status='LOCKED',locked_at=now() where id=?", room);
		assertThat(service.list(room, target)).hasSize(1);
		assertThat(service.list(room, target).get(0).canDecide()).isFalse();
		status(() -> service.request(room, UUID.randomUUID(), message, target.subject(), requester), HttpStatus.CONFLICT);
		status(() -> service.decide(room, id, true, null, target), HttpStatus.CONFLICT);
	}

	@Test void strangersAndOneOnOneRoomsAreNotFound() {
		CurrentActor stranger = actor();
		status(() -> service.list(room, stranger), HttpStatus.NOT_FOUND);
		status(() -> service.request(room, UUID.randomUUID(), message, target.subject(), stranger), HttpStatus.NOT_FOUND);
		status(() -> service.decide(room, UUID.randomUUID(), true, null, stranger), HttpStatus.NOT_FOUND);
		Thr direct = Thr.direct(UUID.randomUUID(), requester.userId(), "1:1");
		direct.placeIn(tenant, node);
		threads.saveAndFlush(direct);
		members.saveAndFlush(new ThrMbr(direct.getId(), requester.userId(), ThrMbrRole.OWNER, requester.userId()));
		status(() -> service.list(direct.getId(), requester), HttpStatus.NOT_FOUND);
	}

	@Test void everyRealChangeLeavesOneSystemLineAndRepeatsLeaveNone() {
		UUID id = UUID.randomUUID();
		service.request(room, id, message, target.subject(), requester);
		service.request(room, id, message, target.subject(), requester);
		assertThat(lineEvents()).containsExactly("REQUESTED");
		service.decide(room, id, true, null, target);
		service.decide(room, id, true, null, target);
		status(() -> service.decide(room, id, false, null, target), HttpStatus.CONFLICT);
		assertThat(lineEvents()).containsExactly("REQUESTED", "APPROVED");
		assertThat(jdbc.queryForObject("select content from msg where thr_id=? and pyl_json->'approval' is not null limit 1",
				String.class, room)).isEmpty();
	}

	@Test void theLinesCarryTheOriginalMessageAndTheRejectionReason() {
		UUID id = UUID.randomUUID();
		service.request(room, id, message, target.subject(), requester);
		service.decide(room, id, false, "  기준 금액을 다시 확인해 주세요  ", target);
		assertThat(lineField("originMsgId")).containsExactly(message.toString(), message.toString());
		assertThat(lineField("reason")).containsExactly(null, "기준 금액을 다시 확인해 주세요");
	}

	@Test void deletingTheRoomRemovesItsRequests() {
		service.request(room, UUID.randomUUID(), message, target.subject(), requester);
		assertThat(jdbc.queryForObject("select count(*) from thr_apv where thr_id=?", Integer.class, room)).isEqualTo(1);
		jdbc.update("delete from thr where id=?", room);
		assertThat(jdbc.queryForObject("select count(*) from thr_apv where thr_id=?", Integer.class, room)).isZero();
	}

	/** 서버가 승인 줄에 쓰는 방의 순번 카운터에서 뽑는다 — 직접 번호를 정하면 줄과 겹친다. */
	private long nextSeq() {
		Thr thread = threads.findById(room).orElseThrow();
		long first = thread.reserveSeqBlock(1);
		threads.saveAndFlush(thread);
		return first;
	}

	/** 승인 줄 pyl_json의 필드 하나를 순서대로 읽는다. 값이 없으면 null이 들어간다. */
	private java.util.List<String> lineField(String field) {
		return jdbc.queryForList("select pyl_json->'approval'->>'" + field + "' from msg where thr_id=? and ath_kind='SYSTEM' "
				+ "and pyl_json->'approval' is not null order by seq", String.class, room);
	}

	private java.util.List<String> lineEvents() {
		return jdbc.queryForList("select pyl_json->'approval'->>'event' from msg where thr_id=? and ath_kind='SYSTEM' "
				+ "and pyl_json->'approval' is not null order by seq", String.class, room);
	}

	private CurrentActor actor() {
		UUID id = UUID.randomUUID(); String subject = "apv-" + id;
		jdbc.update("insert into app_user(id,keycloak_subj) values (?,?)", id, subject);
		return new CurrentActor(id, subject, subject);
	}

	private void status(Runnable action, HttpStatus expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode()).isEqualTo(expected));
	}
}
