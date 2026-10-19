package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.RbacBootstrapService;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
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
 * Class Name : ThreadOutputPostgresTest.java
 * Description : 실제 최신 Flyway·PostgreSQL에서 방의 출력 등록(재전송 한 건)·목록(최신순, 방 격리)·내려받기·접근 확인·삭제 연쇄를 검증한다(#414).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class ThreadOutputPostgresTest {
	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("thread_out").withUsername("test").withPassword("test");

	@DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		r.add("spring.datasource.username", POSTGRES::getUsername);
		r.add("spring.datasource.password", POSTGRES::getPassword);
		r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		r.add("spring.flyway.enabled", () -> "true");
		r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		r.add("app.rbac.workspace-setup-path", () -> Path.of("../../infra/config/workspace-setup.default.yml").toAbsolutePath().normalize().toString());
	}

	@Autowired ThreadOutputService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired ThrRepository threads;
	@Autowired ThrMbrRepository members;
	@Autowired RbacBootstrapService bootstrap;
	@MockitoBean ThreadSourceStorage storage;
	@MockitoBean ThreadOutputStorage outputStorage;
	@MockitoBean WorkspaceAuthorizer authorizer;
	CurrentActor owner;
	CurrentActor member;
	CurrentActor stranger;
	UUID room;
	UUID otherRoom;
	UUID tenant;
	UUID node;

	@BeforeEach void setup() {
		reset(storage, outputStorage, authorizer);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(true);
		bootstrap.runCurrentConfiguration();
		jdbc.update("delete from thr");
		tenant = jdbc.queryForObject("select id from tnn where tnn_key='ogjg'", UUID.class);
		node = jdbc.queryForObject("select id from wrk_node where node_key='common'", UUID.class);
		owner = actor(); member = actor(); stranger = actor();
		room = collabRoom("출력 방", owner, member);
		otherRoom = collabRoom("다른 방", owner);
	}

	@Test void registeredOutputsAreListedNewestFirstWithoutTheStorageKey() {
		var first = service.register(room, UUID.randomUUID(), owner.userId(), "회의록.docx", "DOCX", 1200, "documents/t/2026/10/19/A/회의록.docx");
		var second = service.register(room, UUID.randomUUID(), member.userId(), "요약.md", "MD", 300, "documents/t/2026/10/19/B/요약.md");
		var listed = service.list(room, member);
		assertThat(listed).extracting(ThreadOutputView::id).containsExactly(second.id(), first.id());
		assertThat(listed.get(0)).satisfies(view -> {
			assertThat(view.fileName()).isEqualTo("요약.md");
			assertThat(view.fileKind()).isEqualTo("MD");
			assertThat(view.fileSize()).isEqualTo(300);
			assertThat(view.authorSubject()).isEqualTo(member.subject());
			assertThat(view.createdAt()).isNotNull();
		});
		// 응답 모양에 저장소 위치가 없다.
		assertThat(ThreadOutputView.class.getRecordComponents()).extracting(c -> c.getName())
				.doesNotContain("fileKey", "fileKeyPath", "objectKey");
	}

	@Test void resendingTheSameRequestKeyKeepsOneOutputAndADifferentFileConflicts() {
		UUID key = UUID.randomUUID();
		var first = service.register(room, key, owner.userId(), "보고서.docx", "DOCX", 10, "documents/t/2026/10/19/A/보고서.docx");
		assertThat(service.register(room, key, owner.userId(), "보고서.docx", "DOCX", 10, "documents/t/2026/10/19/A/보고서.docx")).isEqualTo(first);
		assertThat(jdbc.queryForObject("select count(*) from thr_out", Integer.class)).isEqualTo(1);
		assertThatThrownBy(() -> service.register(room, key, owner.userId(), "다른.docx", "DOCX", 10, "documents/t/2026/10/19/Z/다른.docx"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("select count(*) from thr_out", Integer.class)).isEqualTo(1);
	}

	@Test void invalidRegistrationValuesAreRefused() {
		UUID key = UUID.randomUUID();
		assertThatThrownBy(() -> service.register(room, key, owner.userId(), "", "DOCX", 10, "k")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.register(room, key, owner.userId(), "a.xlsx", "XLSX", 10, "k")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.register(room, key, owner.userId(), "a.docx", "DOCX", 0, "k")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.register(room, key, owner.userId(), "a.docx", "DOCX", 10, " ")).isInstanceOf(IllegalArgumentException.class);
		assertThat(jdbc.queryForObject("select count(*) from thr_out", Integer.class)).isZero();
	}

	@Test void aRoomThatDoesNotExistIsNotFound() {
		status(() -> service.register(UUID.randomUUID(), UUID.randomUUID(), owner.userId(), "a.md", "MD", 1, "k"), HttpStatus.NOT_FOUND);
	}

	@Test void outputsOfOneRoomAreNotListedInAnother() {
		service.register(room, UUID.randomUUID(), owner.userId(), "한 방.md", "MD", 5, "documents/t/2026/10/19/A/한 방.md");
		service.register(otherRoom, UUID.randomUUID(), owner.userId(), "다른 방.md", "MD", 5, "documents/t/2026/10/19/B/다른 방.md");
		assertThat(service.list(room, owner)).extracting(ThreadOutputView::fileName).containsExactly("한 방.md");
		assertThat(service.list(otherRoom, owner)).extracting(ThreadOutputView::fileName).containsExactly("다른 방.md");
	}

	@Test void anOutputIsHiddenFromPeopleWhoCannotEnterTheRoom() {
		service.register(room, UUID.randomUUID(), owner.userId(), "비공개.md", "MD", 5, "documents/t/2026/10/19/A/비공개.md");
		// 방 참여자가 아니다.
		status(() -> service.list(room, stranger), HttpStatus.NOT_FOUND);
		// 참여자지만 방의 워크스페이스를 볼 권한이 없다.
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(false);
		status(() -> service.list(room, member), HttpStatus.NOT_FOUND);
	}

	@Test void deletingTheRoomRemovesItsOutputs() {
		service.register(room, UUID.randomUUID(), owner.userId(), "지워질.md", "MD", 5, "documents/t/2026/10/19/A/지워질.md");
		service.register(otherRoom, UUID.randomUUID(), owner.userId(), "남을.md", "MD", 5, "documents/t/2026/10/19/B/남을.md");
		jdbc.update("delete from thr where id=?", room);
		assertThat(jdbc.queryForObject("select count(*) from thr_out where thr_id=?", Integer.class, room)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from thr_out where thr_id=?", Integer.class, otherRoom)).isEqualTo(1);
	}

	@Test void downloadReadsTheFileThroughTheWorkerForAMemberOfTheRoom() {
		byte[] content = {1, 2, 3};
		var out = service.register(room, UUID.randomUUID(), owner.userId(), "회의록.docx", "DOCX", 3, "documents/t/2026/10/19/A/회의록.docx");
		when(outputStorage.read(tenant, "documents/t/2026/10/19/A/회의록.docx")).thenReturn(content);
		var file = service.download(room, out.id(), member);
		assertThat(file.fileName()).isEqualTo("회의록.docx");
		assertThat(file.bytes()).isEqualTo(content);
	}

	@Test void downloadIsNotFoundForOutsidersAndForAnotherRoomsOutput() {
		var out = service.register(room, UUID.randomUUID(), owner.userId(), "비공개.md", "MD", 5, "documents/t/2026/10/19/A/비공개.md");
		var other = service.register(otherRoom, UUID.randomUUID(), owner.userId(), "다른.md", "MD", 5, "documents/t/2026/10/19/B/다른.md");
		status(() -> service.download(room, out.id(), stranger), HttpStatus.NOT_FOUND);
		// 방 주소와 출력 id의 방이 어긋나면 방 참여자(owner)여도 찾을 수 없다.
		status(() -> service.download(otherRoom, out.id(), owner), HttpStatus.NOT_FOUND);
		status(() -> service.download(room, other.id(), owner), HttpStatus.NOT_FOUND);
		status(() -> service.download(room, UUID.randomUUID(), owner), HttpStatus.NOT_FOUND);
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(false);
		status(() -> service.download(room, out.id(), member), HttpStatus.NOT_FOUND);
		verify(outputStorage, never()).read(any(), anyString());
	}

	@Test void aFileMissingInTheWorkerIsNotFound() {
		var out = service.register(room, UUID.randomUUID(), owner.userId(), "유실.md", "MD", 5, "documents/t/2026/10/19/A/유실.md");
		when(outputStorage.read(tenant, "documents/t/2026/10/19/A/유실.md")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
		status(() -> service.download(room, out.id(), owner), HttpStatus.NOT_FOUND);
	}

	@Test void onlyAMemberOfAnActiveRoomMayMakeOutputs() {
		assertThat(service.writableTenant(room, member)).isEqualTo(tenant);
		status(() -> service.writableTenant(room, stranger), HttpStatus.NOT_FOUND);
		for (String state : new String[] {"LOCKED", "ARCHIVED"}) {
			jdbc.update("update thr set status=?,locked_at=now(),archived_at=case when ?='ARCHIVED' then now() else null end where id=?",
					state, state, room);
			status(() -> service.writableTenant(room, owner), HttpStatus.CONFLICT);
		}
		// 잠긴 방도 읽기는 된다 — 만들기만 막는다.
		assertThat(service.list(room, owner)).isEmpty();
		when(authorizer.canViewBlocking(anyString(), any())).thenReturn(false);
		status(() -> service.writableTenant(otherRoom, owner), HttpStatus.NOT_FOUND);
	}

	@Test void anOutputIsFoundByItsRequestKeyOnlyInItsRoom() {
		UUID key = UUID.randomUUID();
		var out = service.register(room, key, owner.userId(), "찾을.md", "MD", 5, "documents/t/2026/10/19/A/찾을.md");
		assertThat(service.findByRequestKey(room, key)).hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(out.id()));
		assertThat(service.findByRequestKey(otherRoom, key)).isEmpty();
		assertThat(service.findByRequestKey(room, UUID.randomUUID())).isEmpty();
	}

	private UUID collabRoom(String title, CurrentActor creator, CurrentActor... others) {
		Thr value = Thr.collab(creator.userId(), title);
		value.placeIn(tenant, node);
		threads.saveAndFlush(value);
		members.saveAndFlush(new ThrMbr(value.getId(), creator.userId(), ThrMbrRole.OWNER, creator.userId()));
		for (CurrentActor other : others)
			members.saveAndFlush(new ThrMbr(value.getId(), other.userId(), ThrMbrRole.MEMBER, creator.userId()));
		return value.getId();
	}

	private CurrentActor actor() {
		UUID id = UUID.randomUUID(); String subject = "out-" + id;
		jdbc.update("insert into app_user(id,keycloak_subj) values (?,?)", id, subject);
		return new CurrentActor(id, subject, subject);
	}

	private void status(Runnable action, HttpStatus expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode()).isEqualTo(expected));
	}
}
