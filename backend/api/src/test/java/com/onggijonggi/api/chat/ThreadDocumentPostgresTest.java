package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * Class Name : ThreadDocumentPostgresTest.java
 * Description : 실제 최신 Flyway·PostgreSQL에서 방 문서 인가, 원자 등록, 고정, 삭제와 원본 정리 경합을 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class ThreadDocumentPostgresTest {

	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("thread_docs").withUsername("test").withPassword("test");
	@DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.flyway.enabled", () -> "true");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		r.add("app.rbac.workspace-setup-path", () -> Path.of("../../infra/config/workspace-setup.default.yml").toAbsolutePath().normalize().toString());
	}
	@Autowired ThreadDocumentService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired ThrRepository threads;
	@Autowired ThrMbrRepository members;
	@Autowired RbacBootstrapService bootstrap;
	@MockitoBean ThreadSourceStorage storage;
	@MockitoBean WorkspaceAuthorizer authorizer;
	CurrentActor owner;
	CurrentActor member;
	UUID room;
	UUID tenant;
	byte[] bytes = "등록 원문".getBytes(StandardCharsets.UTF_8);

	@BeforeEach void setup() {
		reset(storage, authorizer);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(true);
		bootstrap.runCurrentConfiguration();
		jdbc.update("delete from thr");
		jdbc.update("delete from thr_doc_end");
		tenant = jdbc.queryForObject("select id from tnn where tnn_key='ogjg'", UUID.class);
		UUID common = jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class);
		owner = actor(); member = actor();
		Thr value = Thr.collab(owner.userId(), "문서 방");
		value.placeIn(tenant, common);
		threads.saveAndFlush(value); room = value.getId();
		members.saveAndFlush(new ThrMbr(room, owner.userId(), ThrMbrRole.OWNER, owner.userId()));
		members.saveAndFlush(new ThrMbr(room, member.userId(), ThrMbrRole.MEMBER, owner.userId()));
	}

	@Test void registeredOriginalIsPendingUnpinnedAndRetryDoesNotDuplicateEvents() {
		UUID id = UUID.randomUUID();
		var first = upload(id, member);
		assertThat(first.status()).isEqualTo("PENDING");
		assertThat(first.pinned()).isFalse();
		assertThat(service.upload(room, id, member, "guide.txt", bytes)).isEqualTo(first);
		verify(storage, times(1)).save(eq(tenant), eq(room), eq(id), eq(ThreadDocumentService.digest(bytes)), eq(bytes), any(), any());
		assertThat(events(id)).isEqualTo(1);
		when(storage.read(eq(tenant), eq(room), eq(id), eq(ThreadDocumentService.digest(bytes)), any())).thenReturn(bytes);
		assertThat(service.original(room, id, owner).bytes()).isEqualTo(bytes);
		assertThat(service.list(room, member).documents()).hasSize(1);
	}

	@Test void participantCanPinOthersButOnlyUploaderOrOwnerCanUnpinOrDelete() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		service.change(room, id, member, "PINNED", UUID.randomUUID());
		assertThat(service.list(room, member).documents().get(0).pinned()).isTrue();
		status(() -> service.change(room, id, member, "UNPINNED", UUID.randomUUID()), HttpStatus.FORBIDDEN);
		status(() -> service.change(room, id, member, "DELETED", UUID.randomUUID()), HttpStatus.FORBIDDEN);
		UUID request = UUID.randomUUID();
		service.change(room, id, owner, "DELETED", request);
		service.change(room, id, owner, "DELETED", request);
		assertThat(events(id)).isEqualTo(3);
		assertThat(service.list(room, owner).documents()).isEmpty();
		status(() -> service.original(room, id, owner), HttpStatus.NOT_FOUND);
		assertThat(service.processing(id, "PENDING", "PROCESSING")).isFalse();
	}

	@Test void departureKeepsDocumentButRemovesUploaderAccess() {
		UUID id = UUID.randomUUID(); upload(id, member);
		jdbc.update("update thr_mbr set status='LEFT',ended_at=now(),end_rsn='LEFT' where thr_id=? and user_id=?", room, member.userId());
		status(() -> service.list(room, member), HttpStatus.NOT_FOUND);
		assertThat(service.list(room, owner).documents()).hasSize(1);
		service.change(room, id, owner, "PINNED", UUID.randomUUID());
		jdbc.update("update app_user set status='INACTIVE', inactive_at=now() where id=?", member.userId());
		assertThat(service.list(room, owner).documents()).hasSize(1);
	}

	@Test void lockedAndArchivedRoomsAreReadableButNeverWritable() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		for (String state : new String[]{"LOCKED", "ARCHIVED"}) {
			jdbc.update("update thr set status=?,locked_at=now(),archived_at=case when ?='ARCHIVED' then now() else null end where id=?", state, state, room);
			var listing = service.list(room, owner);
			assertThat(listing.canUpload()).isFalse();
			assertThat(listing.documents().get(0).canPin()).isFalse();
			status(() -> upload(UUID.randomUUID(), owner), HttpStatus.CONFLICT);
			status(() -> service.change(room, id, owner, "DELETED", UUID.randomUUID()), HttpStatus.CONFLICT);
		}
	}

	@Test void expiredUploadFailsAndCleanupIsLeasedSoAFailedDeleteIsNotRetriedAtOnce() {
		UUID id = UUID.randomUUID();
		// 저장 요청이 돌아오지 않은 채 예약이 만료된 상태: UPLOADING 행과 기한이 지난 정리 참조만 남아 있다.
		doAnswer(invocation -> { throw new IllegalStateException("BFF 중단"); })
				.when(storage).save(any(), any(), any(), any(), any(), any(), any());
		assertThatThrownBy(() -> upload(id, owner)).isInstanceOf(IllegalStateException.class);
		jdbc.update("update thr_doc set status='UPLOADING', err=null where id=?", id);
		jdbc.update("update thr_doc_end set next_at=now()-interval '1 second' where doc_id=?", id);
		var claimed = new java.util.concurrent.atomic.AtomicReference<Object>();
		doAnswer(invocation -> {
			claimed.set(jdbc.queryForObject("select next_at > now() + interval '60 seconds' from thr_doc_end where doc_id=?", Boolean.class, id));
			throw new IllegalStateException("워커 응답 없음");
		}).when(storage).delete(any(), any(), any(), any(), any());
		service.cleanupDue();
		assertThat(claimed.get()).as("삭제 호출 중에는 선점으로 다음 시각이 밀려 있다").isEqualTo(true);
		assertThat(jdbc.queryForMap("select status, err from thr_doc where id=?", id))
				.containsEntry("status", "FAILED").containsEntry("err", "SOURCE_UPLOAD_EXPIRED");
		service.cleanupDue();
		verify(storage, times(1)).delete(any(), any(), any(), any(), any());
		assertThat(jdbc.queryForObject("select att_cnt from thr_doc_end where doc_id=?", Integer.class, id)).isEqualTo(1);
	}

	@Test void aDocumentOfAnotherRoomCannotBeReachedThroughMyRoom() {
		UUID foreign = UUID.randomUUID(); upload(foreign, owner);
		CurrentActor stranger = actor();
		Thr other = Thr.collab(stranger.userId(), "다른 방");
		other.placeIn(tenant, jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class));
		threads.saveAndFlush(other);
		members.saveAndFlush(new ThrMbr(other.getId(), stranger.userId(), ThrMbrRole.OWNER, stranger.userId()));
		when(storage.read(any(), any(), any(), any(), any())).thenReturn(bytes);
		status(() -> service.original(other.getId(), foreign, stranger), HttpStatus.NOT_FOUND);
		status(() -> service.change(other.getId(), foreign, stranger, "PINNED", UUID.randomUUID()), HttpStatus.NOT_FOUND);
		status(() -> service.change(other.getId(), foreign, stranger, "DELETED", UUID.randomUUID()), HttpStatus.NOT_FOUND);
		status(() -> service.upload(other.getId(), foreign, stranger, "guide.txt", bytes), HttpStatus.NOT_FOUND);
		assertThat(service.list(other.getId(), stranger).documents()).isEmpty();
		assertThat(service.list(room, owner).documents()).singleElement()
				.satisfies(doc -> assertThat(doc.pinned()).isFalse());
		verify(storage, never()).read(any(), any(), any(), any(), any());
	}

	/** 방 잠금이 공유라 같은 FAILED 문서의 재시도끼리는 문서 행 잠금으로 줄을 선다. 늦은 쪽은 앞선 재시도가 남긴 UPLOADING을 다시 읽고 409다. */
	@Test void concurrentRetriesOfAFailedDocumentSerializeOnTheDocumentRow() throws Exception {
		UUID id = UUID.randomUUID();
		doThrow(new IllegalStateException("저장 실패")).when(storage).save(any(), any(), any(), any(), any(), any(), any());
		assertThatThrownBy(() -> upload(id, owner)).isInstanceOf(IllegalStateException.class);
		jdbc.update("delete from thr_doc_end where doc_id=?", id);
		doNothing().when(storage).save(any(), any(), any(), any(), any(), any(), any());
		var executor = Executors.newSingleThreadExecutor();
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			connection.setAutoCommit(false);
			try (var hold = connection.prepareStatement("select id from thr_doc where id=? for update")) {
				hold.setObject(1, id); hold.executeQuery();
			}
			var retry = executor.submit(() -> upload(id, owner));
			Thread.sleep(300);
			assertThat(retry.isDone()).as("앞선 재시도가 문서 행을 쥔 동안 기다린다").isFalse();
			try (var first = connection.prepareStatement("update thr_doc set status='UPLOADING', src_att_id=? where id=?")) {
				first.setObject(1, UUID.randomUUID()); first.setObject(2, id); first.executeUpdate();
			}
			connection.commit();
			assertThatThrownBy(() -> retry.get(10, TimeUnit.SECONDS)).cause().isInstanceOfSatisfying(ResponseStatusException.class,
					error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
		} finally { executor.shutdown(); }
		verify(storage, times(1)).save(any(), any(), any(), any(), any(), any(), any());
	}

	@Test void severalDocumentsCanBePinnedAndUnpinningOneKeepsTheOthers() {
		UUID first = UUID.randomUUID(); upload(first, owner);
		UUID second = UUID.randomUUID(); service.upload(room, second, member, "second.txt", "둘째 원문".getBytes(StandardCharsets.UTF_8));
		service.change(room, first, owner, "PINNED", UUID.randomUUID());
		service.change(room, second, member, "PINNED", UUID.randomUUID());
		assertThat(service.list(room, owner).documents()).allMatch(ThreadDocumentView::pinned).hasSize(2);
		service.change(room, first, owner, "UNPINNED", UUID.randomUUID());
		assertThat(service.list(room, owner).documents())
				.extracting(ThreadDocumentView::id, ThreadDocumentView::pinned)
				.containsExactly(tuple(first, false), tuple(second, true));
	}

	@Test void retransmittedChangeStillSucceedsAfterTheRoomIsLocked() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		UUID request = UUID.randomUUID();
		service.change(room, id, owner, "PINNED", request);
		jdbc.update("update thr set status='LOCKED',locked_at=now() where id=?", room);
		service.change(room, id, owner, "PINNED", request);
		status(() -> service.change(room, id, owner, "UNPINNED", UUID.randomUUID()), HttpStatus.CONFLICT);
		assertThat(events(id)).isEqualTo(2);
	}

	@Test void originalDeletedWhileReadingIsNotFoundAndPlainOutageIsUnavailable() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		when(storage.read(any(), any(), any(), any(), any()))
				.thenThrow(ThreadDocumentException.storageUnavailable(new IllegalStateException("워커 장애")));
		status(() -> service.original(room, id, owner), HttpStatus.SERVICE_UNAVAILABLE);
		doAnswer(invocation -> {
			jdbc.update("update thr_doc set status='DELETED',pnn=false,deleted_at=now() where id=?", id);
			throw ThreadDocumentException.storageUnavailable(new IllegalStateException("워커 404"));
		}).when(storage).read(any(), any(), any(), any(), any());
		status(() -> service.original(room, id, owner), HttpStatus.NOT_FOUND);
	}

	@Test void cleanupDeletesTheSourceWithoutHoldingTheDocumentRowLock() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		service.change(room, id, owner, "DELETED", UUID.randomUUID());
		doAnswer(invocation -> jdbc.queryForList("select id from thr_doc where id=? for update nowait", id))
				.when(storage).delete(any(), any(), any(), any(), any());
		service.cleanupDue();
		verify(storage).delete(eq(tenant), eq(room), eq(id), eq(ThreadDocumentService.digest(bytes)), any());
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_end", Integer.class)).isZero();
	}

	@Test void latestWorkspaceAndAccountAuthorizationIsRequiredIncludingOriginalSecondCheck() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(false);
		status(() -> service.list(room, owner), HttpStatus.NOT_FOUND);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(true);
		when(storage.read(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
			jdbc.update("update app_user set status='INACTIVE', inactive_at=now() where id=?", owner.userId());
			return bytes;
		});
		status(() -> service.original(room, id, owner), HttpStatus.NOT_FOUND);
	}

	@Test void failedStorageCannotPinOrReadAndCleanupFailureRetries() {
		UUID id = UUID.randomUUID();
		doThrow(new IllegalStateException("저장 실패")).when(storage).save(any(), any(), any(), any(), any(), any(), any());
		assertThatThrownBy(() -> upload(id, owner)).isInstanceOf(IllegalStateException.class);
		assertThat(events(id)).isZero();
		assertThat(service.list(room, owner).documents().get(0).canPin()).isFalse();
		assertThat(service.list(room, owner).documents().get(0).canReadOriginal()).isFalse();
		status(() -> service.change(room, id, owner, "PINNED", UUID.randomUUID()), HttpStatus.CONFLICT);
		jdbc.update("update thr_doc_end set next_at=now()-interval '1 second'");
		doThrow(new IllegalStateException("정리 실패")).when(storage).delete(any(), any(), any(), any(), any());
		service.cleanupDue();
		assertThat(jdbc.queryForObject("select att_cnt from thr_doc_end where doc_id=?", Integer.class, id)).isEqualTo(1);
		doNothing().when(storage).delete(any(), any(), any(), any(), any());
		jdbc.update("update thr_doc_end set next_at=now()-interval '1 second'");
		service.cleanupDue();
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_end", Integer.class)).isZero();
	}

	@Test void roomDeletionRetainsCleanupReferenceAndIndividualDeleteRetainsEvent() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		service.change(room, id, owner, "DELETED", UUID.randomUUID());
		assertThat(events(id)).isEqualTo(2);
		jdbc.update("delete from thr where id=?", room);
		assertThat(jdbc.queryForObject("select count(*) from thr_doc", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_evt", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_end", Integer.class)).isEqualTo(1);
		service.cleanupDue();
		verify(storage).delete(eq(tenant), eq(room), eq(id), eq(ThreadDocumentService.digest(bytes)), any());
	}

	@Test void lateUploadAfterRoomDeletionCannotRegisterAndStillQueuesCleanup() {
		UUID id = UUID.randomUUID();
		doAnswer(invocation -> { jdbc.update("delete from thr where id=?", room); return null; })
				.when(storage).save(any(), any(), any(), any(), any(), any(), any());
		status(() -> upload(id, owner), HttpStatus.NOT_FOUND);
		assertThat(events(id)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from thr_doc_end", Integer.class)).isEqualTo(1);
		service.cleanupDue();
		verify(storage).delete(eq(tenant), eq(room), eq(id), eq(ThreadDocumentService.digest(bytes)), any());
	}

	@Test void registeredFailureKeepsOriginalAndDeletedProcessingCannotResurrect() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		assertThat(service.processing(id, "PENDING", "PROCESSING")).isTrue();
		assertThat(service.processing(id, "PROCESSING", "FAILED")).isTrue();
		assertThat(upload(id, owner).status()).isEqualTo("FAILED");
		assertThat(service.list(room, owner).documents().get(0).canReadOriginal()).isTrue();
		verify(storage, times(1)).save(any(), any(), any(), any(), any(), any(), any());
		service.change(room, id, owner, "DELETED", UUID.randomUUID());
		assertThat(service.processing(id, "PROCESSING", "READY")).isFalse();
	}

	@Test void invalidFileAndMismatchedRegistrationAreRejected() {
		status(() -> service.upload(room, UUID.randomUUID(), owner, "../x.txt", bytes), HttpStatus.BAD_REQUEST);
		status(() -> service.upload(room, UUID.randomUUID(), owner, "report\u202Efdp.txt", bytes), HttpStatus.BAD_REQUEST);
		status(() -> service.upload(room, UUID.randomUUID(), owner, "x.exe", bytes), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		status(() -> service.upload(room, UUID.randomUUID(), owner, "x.txt", new byte[0]), HttpStatus.BAD_REQUEST);
		status(() -> service.upload(room, UUID.randomUUID(), owner, "x.txt", new byte[ThreadDocumentService.MAX_FILE_BYTES+1]), HttpStatus.CONTENT_TOO_LARGE);
		UUID id = UUID.randomUUID(); upload(id, owner);
		status(() -> service.upload(room, id, owner, "guide.txt", new byte[]{1}), HttpStatus.CONFLICT);
		status(() -> upload(id, member), HttpStatus.NOT_FOUND);
	}

	@Test void eventFailureRollsBackPinAndRegistrationFinalization() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		jdbc.execute("alter table thr_doc_evt add constraint test_event_failure check (evt_kind <> 'PINNED')");
		try {
			assertThatThrownBy(() -> service.change(room, id, owner, "PINNED", UUID.randomUUID()))
					.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThat(service.list(room, owner).documents().get(0).pinned()).isFalse();
			assertThat(events(id)).isEqualTo(1);
		} finally { jdbc.execute("alter table thr_doc_evt drop constraint test_event_failure"); }
		jdbc.execute("alter table thr_doc_evt add constraint test_registration_failure check (evt_kind <> 'REGISTERED') not valid");
		UUID failed = UUID.randomUUID();
		try {
			assertThatThrownBy(() -> upload(failed, owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
			assertThat(events(failed)).isZero();
			assertThat(jdbc.queryForObject("select status from thr_doc where id=?", String.class, failed)).isEqualTo("FAILED");
			assertThat(jdbc.queryForObject("select count(*) from thr_doc_end where doc_id=?", Integer.class, failed)).isEqualTo(1);
		} finally { jdbc.execute("alter table thr_doc_evt drop constraint test_registration_failure"); }
	}

	@Test void directOwnerOnlyAndCrossTenantForeignKeyAreEnforced() {
		UUID common = jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class);
		Thr direct = Thr.direct(UUID.randomUUID(), owner.userId(), "개인 문서");
		direct.placeIn(tenant, common); threads.saveAndFlush(direct);
		members.saveAndFlush(new ThrMbr(direct.getId(), owner.userId(), ThrMbrRole.OWNER, owner.userId()));
		members.saveAndFlush(new ThrMbr(direct.getId(), member.userId(), ThrMbrRole.MEMBER, owner.userId()));
		UUID id = UUID.randomUUID();
		service.upload(direct.getId(), id, owner, "guide.txt", bytes);
		status(() -> service.list(direct.getId(), member), HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> jdbc.update("update thr_doc set tnn_id=? where id=?", UUID.randomUUID(), id))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(service.list(direct.getId(), owner).documents()).hasSize(1);
	}

	@Test void simultaneousRegistrationCannotOverwriteUploadingReservation() {
		UUID id = UUID.randomUUID();
		doAnswer(invocation -> {
			status(() -> upload(id, owner), HttpStatus.CONFLICT);
			return null;
		}).when(storage).save(any(), any(), any(), any(), any(), any(), any());
		assertThat(upload(id, owner).status()).isEqualTo("PENDING");
		verify(storage, times(1)).save(any(), any(), any(), any(), any(), any(), any());
		assertThat(events(id)).isEqualTo(1);
	}

	@Test void legacyDocumentsAreNeverAssignedMovedOrDeleted() {
		UUID old = UUID.randomUUID(); UUID version = UUID.randomUUID();
		jdbc.update("insert into doc(id,doc_key,file_name,org_path,acc_tag,emb_mdl,chnk_ver,dept) values (?,?,'legacy.pdf','legacy/path',array['OLD'],'legacy','1','legacy')", old, "legacy-"+old);
		jdbc.update("insert into doc_ver(id,doc_id,ver_seq,file_name,org_path,uploaded_by) values (?,?,1,'legacy.pdf','legacy/path','legacy-user')", version, old);
		jdbc.update("update doc set cur_ver_id=? where id=?", version, old);
		UUID id = UUID.randomUUID(); upload(id, owner);
		service.change(room, id, owner, "DELETED", UUID.randomUUID());
		jdbc.update("delete from thr where id=?", room);
		service.cleanupDue();
		assertThat(jdbc.queryForObject("select org_path from doc where id=?", String.class, old)).isEqualTo("legacy/path");
		assertThat(jdbc.queryForObject("select cur_ver_id from doc where id=?", UUID.class, old)).isEqualTo(version);
		assertThat(jdbc.queryForObject("select count(*) from doc_ver where id=?", Integer.class, version)).isEqualTo(1);
	}

	@Test void nonParticipantCannotReadOrWriteEvenWithKnownDocumentId() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		CurrentActor outsider = actor();
		status(() -> service.list(room, outsider), HttpStatus.NOT_FOUND);
		status(() -> service.original(room, id, outsider), HttpStatus.NOT_FOUND);
		status(() -> service.upload(room, UUID.randomUUID(), outsider, "guide.txt", bytes), HttpStatus.NOT_FOUND);
		status(() -> service.change(room, id, outsider, "PINNED", UUID.randomUUID()), HttpStatus.NOT_FOUND);
		verify(storage, never()).read(any(), any(), any(), any(), any());
	}

	@Test void successfulPinRetryNeverOverridesLaterUnpinAndNoOpIsNotAccepted() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		UUID pinRequest = UUID.randomUUID();
		service.change(room, id, owner, "PINNED", pinRequest);
		status(() -> service.change(room, id, owner, "PINNED", UUID.randomUUID()), HttpStatus.CONFLICT);
		service.change(room, id, owner, "UNPINNED", UUID.randomUUID());
		service.change(room, id, owner, "PINNED", pinRequest);
		assertThat(service.list(room, owner).documents().get(0).pinned()).isFalse();
		assertThat(events(id)).isEqualTo(3);
	}

	@Test void staleUploadCompletionCannotChangeNewReservationOrItsCleanup() {
		UUID id = UUID.randomUUID();
		doAnswer(invocation -> {
			// 만료 정리가 첫 예약을 끝내고, 두 번째 요청이 저장 중인 시점을 재현한다.
			jdbc.update("update thr_doc set status='FAILED', updated_at=updated_at+interval '1 second' where id=?", id);
			jdbc.update("update thr_doc set status='UPLOADING', src_att_id=?, updated_at=updated_at+interval '1 second' where id=?", UUID.randomUUID(), id);
			jdbc.update("update thr_doc_end set next_at=now()+interval '10 minutes' where doc_id=?", id);
			return null;
		}).when(storage).save(any(), any(), any(), any(), any(), any(), any());
		status(() -> upload(id, owner), HttpStatus.CONFLICT);
		assertThat(jdbc.queryForObject("select status from thr_doc where id=?", String.class, id)).isEqualTo("UPLOADING");
		assertThat(jdbc.queryForObject("select next_at > now()+interval '9 minutes' from thr_doc_end where doc_id=?", Boolean.class, id)).isTrue();
		assertThat(events(id)).isZero();
	}

	@Test void deletedRoomDocumentIdCannotOverwriteOutstandingOriginalCleanup() {
		UUID id = UUID.randomUUID(); upload(id, owner);
		UUID deletedRoom = room;
		jdbc.update("delete from thr where id=?", room);
		UUID common = jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class);
		Thr replacement = Thr.collab(owner.userId(), "다른 문서 방");
		replacement.placeIn(tenant, common); threads.saveAndFlush(replacement);
		room = replacement.getId();
		members.saveAndFlush(new ThrMbr(room, owner.userId(), ThrMbrRole.OWNER, owner.userId()));
		status(() -> upload(id, owner), HttpStatus.CONFLICT);
		assertThat(jdbc.queryForObject("select thr_id from thr_doc_end where doc_id=?", UUID.class, id)).isEqualTo(deletedRoom);
		service.cleanupDue();
		verify(storage).delete(eq(tenant), eq(deletedRoom), eq(id), eq(ThreadDocumentService.digest(bytes)), any());
		verify(storage, times(1)).save(any(), any(), any(), any(), any(), any(), any());
	}

	@Test void failedUploadRetriesOnlyAfterCleanupWithANewStorageAttempt() {
		UUID id = UUID.randomUUID();
		doThrow(new IllegalStateException("저장 실패")).when(storage).save(any(), any(), any(), any(), any(), any(), any());
		assertThatThrownBy(() -> upload(id, owner)).isInstanceOf(IllegalStateException.class);
		UUID oldAttempt = jdbc.queryForObject("select src_att_id from thr_doc where id=?", UUID.class, id);
		status(() -> upload(id, owner), HttpStatus.CONFLICT);
		doNothing().when(storage).save(any(), any(), any(), any(), any(), any(), any());
		jdbc.update("update thr_doc_end set next_at=now()-interval '1 second' where doc_id=?", id);
		service.cleanupDue();
		verify(storage).delete(tenant, room, id, ThreadDocumentService.digest(bytes), oldAttempt);
		assertThat(upload(id, owner).status()).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select src_att_id from thr_doc where id=?", UUID.class, id)).isNotEqualTo(oldAttempt);
		assertThat(service.processing(id, "PENDING", "PROCESSING")).isTrue();
		assertThat(service.processing(id, "PROCESSING", "READY")).isTrue();
		assertThat(service.list(room, owner).documents().get(0).pinned()).isFalse();
	}

	@Test void participantRemovalSerializesWithInFlightDocumentAuthorization() throws Exception {
		UUID id = UUID.randomUUID(); upload(id, owner);
		CountDownLatch checkedMembership = new CountDownLatch(1);
		CountDownLatch finishAuthorization = new CountDownLatch(1);
		when(authorizer.canViewBlocking(eq(member.subject()), any())).thenAnswer(invocation -> {
			checkedMembership.countDown();
			if (!finishAuthorization.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("authorization timeout");
			return true;
		});
		var executor = Executors.newSingleThreadExecutor();
		var pin = executor.submit(() -> service.change(room, id, member, "PINNED", UUID.randomUUID()));
		try {
			assertThat(checkedMembership.await(10, TimeUnit.SECONDS)).isTrue();
			try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
					var statement = connection.createStatement()) {
				statement.execute("set lock_timeout = '200ms'");
				try (var removal = connection.prepareStatement("update thr_mbr set status='REVOKED',ended_at=now(),end_rsn='OWNER_REVOKED' where thr_id=? and user_id=?")) {
					removal.setObject(1, room); removal.setObject(2, member.userId());
					assertThatThrownBy(removal::executeUpdate).isInstanceOfSatisfying(SQLException.class,
							error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
				}
			}
		} finally {
			finishAuthorization.countDown();
			executor.shutdown();
		}
		pin.get(10, TimeUnit.SECONDS);
		assertThat(jdbc.update("update thr_mbr set status='REVOKED',ended_at=now(),end_rsn='OWNER_REVOKED' where thr_id=? and user_id=?", room, member.userId())).isEqualTo(1);
		status(() -> service.change(room, id, member, "UNPINNED", UUID.randomUUID()), HttpStatus.NOT_FOUND);
		assertThat(service.list(room, owner).documents().get(0).pinned()).isTrue();
	}

	private ThreadDocumentView upload(UUID id, CurrentActor actor) { return service.upload(room, id, actor, "guide.txt", bytes); }
	private int events(UUID id) { return jdbc.queryForObject("select count(*) from thr_doc_evt where doc_id=?", Integer.class, id); }
	private CurrentActor actor() {
		UUID id = UUID.randomUUID(); String subject = "doc-"+id;
		jdbc.update("insert into app_user(id,keycloak_subj) values (?,?)", id, subject);
		return new CurrentActor(id, subject, subject);
	}
	private void status(Runnable action, HttpStatus expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode()).isEqualTo(expected));
	}
}
