package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.authz.ThreadScopeFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : ThreadDocumentRevisionTest.java
 * Description : 개정본 만들기 — 고정 docx 원본을 워커의 개정 경로로 보내고 결과를 버전을 올린 이름("_v1.docx")으로 방 문서에 등록하는지,
 *               워커 거절(원문 없음·여러 곳)과 권한·저장소 실패를 모델이 다시 시도하거나 사용자에게 알릴 수 있는 문장으로 돌려주는지 확인한다.
 */
class ThreadDocumentRevisionTest {

	private final ThreadDocumentService documents = mock(ThreadDocumentService.class);
	private final List<ClientRequest> requests = new ArrayList<>();
	private final UUID tenant = UUID.randomUUID();
	private final UUID room = UUID.randomUUID();
	private final UUID rules = UUID.randomUUID();
	private final CurrentActor actor = new CurrentActor(UUID.randomUUID(), "s", "s");
	private final List<ThreadDocumentTool.Edit> edits = List.of(new ThreadDocumentTool.Edit("주 12시간", "주 18시간"));

	private ThreadDocumentRevision revision(HttpStatus status, String body) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			requests.add(request);
			return Mono.just(ClientResponse.create(status).header("Content-Type", status.is2xxSuccessful()
					? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/json").body(body).build());
		});
		return new ThreadDocumentRevision(documents, new ObjectMapper(), builder, "http://worker", "key");
	}

	private void pinned(String fileName) {
		when(documents.searchScope(eq(room), any())).thenReturn(new ThreadDocumentScope(tenant, ThreadScopeFilter.of(List.of(room)),
				List.of(new ThreadDocumentScope.Target(rules, fileName, 1, "bge-m3", 1024))));
		when(documents.original(room, rules, actor)).thenReturn(new ThreadDocumentService.Original(fileName, new byte[] {1, 2}));
		inRoom();
	}

	private void inRoom(String... fileNames) {
		when(documents.list(eq(room), any())).thenReturn(new ThreadDocumentView.Listing("ACTIVE", true, List.of(fileNames).stream()
				.map(name -> new ThreadDocumentView(UUID.randomUUID(), name, 1, "READY", false, true, true, false, true, true, false, null))
				.toList()));
	}

	@Test
	void registersTheRevisedCopyNextToTheOriginal() {
		pinned("취업규칙.docx");

		String result = revision(HttpStatus.OK, "revised").revise(room, actor, "취업규칙.docx", edits);

		ClientRequest sent = requests.get(0);
		assertThat(sent.url().toString()).isEqualTo("http://worker" + ThreadDocumentRevision.REVISIONS_PATH);
		assertThat(sent.headers().getFirst("X-Internal-Api-Key")).isEqualTo("key");
		assertThat(sent.headers().getFirst("X-Tenant-Id")).isEqualTo(tenant.toString());
		verify(documents).upload(eq(room), any(), eq(actor), eq("취업규칙_v1.docx"), eq("revised".getBytes()));
		assertThat(result).contains("취업규칙_v1.docx").contains("방 문서에 등록").contains("신구조문 대비표");
	}

	@Test
	void aSecondRevisionOfTheSameDocumentGetsTheNextVersion() {
		pinned("취업규칙.docx");
		inRoom("취업규칙.docx", "취업규칙_v1.docx");

		String result = revision(HttpStatus.OK, "revised").revise(room, actor, "취업규칙.docx", edits);

		verify(documents).upload(eq(room), any(), eq(actor), eq("취업규칙_v2.docx"), eq("revised".getBytes()));
		assertThat(result).contains("취업규칙_v2.docx");
	}

	@Test
	void theVersionAtTheEndOfTheNameGoesUpAndSkipsNamesAlreadyInTheRoom() {
		assertThat(ThreadDocumentRevision.revisionName("사내_취업_규칙_20260101.docx", Set.of())).isEqualTo("사내_취업_규칙_20260101_v1.docx");
		assertThat(ThreadDocumentRevision.revisionName("규칙_v1.docx", Set.of())).isEqualTo("규칙_v2.docx");
		assertThat(ThreadDocumentRevision.revisionName("규칙_v9.docx", Set.of())).isEqualTo("규칙_v10.docx");
		assertThat(ThreadDocumentRevision.revisionName("규칙_v1.docx", Set.of("규칙_v2.docx"))).isEqualTo("규칙_v3.docx");
		assertThat(ThreadDocumentRevision.revisionName("규칙.docx", Set.of("규칙_v1.docx", "규칙_v2.docx"))).isEqualTo("규칙_v3.docx");
	}

	@Test
	void aDateOrLongNumberAfterVIsNotAVersion() {
		assertThat(ThreadDocumentRevision.revisionName("규칙_v20260101.docx", Set.of())).isEqualTo("규칙_v20260101_v1.docx");
		assertThat(ThreadDocumentRevision.revisionName("규칙_v20260101120000.docx", Set.of())).isEqualTo("규칙_v20260101120000_v1.docx");
		assertThat(ThreadDocumentRevision.revisionName("규칙_v1234.docx", Set.of())).isEqualTo("규칙_v1235.docx");
	}

	@Test
	void aMissingOrAmbiguousSentenceTellsTheModelHowToRetry() {
		pinned("취업규칙.docx");

		String missing = revision(HttpStatus.UNPROCESSABLE_CONTENT, "{\"code\":\"EDIT_NOT_FOUND\",\"message\":\"edit 1: not found\",\"requestId\":\"r\"}")
				.revise(room, actor, "취업규칙.docx", edits);
		String ambiguous = revision(HttpStatus.UNPROCESSABLE_CONTENT, "{\"code\":\"EDIT_AMBIGUOUS\",\"message\":\"edit 1: 2 times\",\"requestId\":\"r\"}")
				.revise(room, actor, "취업규칙.docx", edits);

		// 실패는 모두 같은 문장으로 시작한다 — 모델이 거절을 성공으로 읽지 않게.
		assertThat(missing).startsWith(ThreadDocumentRevision.FAILED).contains("찾지 못했습니다").contains("edit 1").contains(ThreadDocumentTool.NAME);
		assertThat(ambiguous).startsWith(ThreadDocumentRevision.FAILED).contains("여러 번");
		verify(documents, never()).upload(any(), any(), any(), any(), any());
	}

	@Test
	void onlyAPinnedDocxWithThatExactNameIsRevised() {
		pinned("취업규칙.docx");
		ThreadDocumentRevision revision = revision(HttpStatus.OK, "revised");

		assertThat(revision.revise(room, actor, "취업규칙.pdf", edits)).startsWith(ThreadDocumentRevision.FAILED).contains("docx 문서만");
		assertThat(revision.revise(room, actor, "규정.docx", edits)).startsWith(ThreadDocumentRevision.FAILED)
				.contains("고정된 문서 중 '규정.docx'이 없습니다");
		assertThat(revision.revise(room, actor, "취업규칙.docx", List.of())).startsWith(ThreadDocumentRevision.FAILED).contains("하나 이상");
		assertThat(requests).isEmpty();
	}

	@Test
	void twoPinnedDocumentsWithTheSameNameAreNotRevised() {
		when(documents.searchScope(eq(room), any())).thenReturn(new ThreadDocumentScope(tenant, ThreadScopeFilter.of(List.of(room)), List.of(
				new ThreadDocumentScope.Target(rules, "취업규칙.docx", 1, "bge-m3", 1024),
				new ThreadDocumentScope.Target(UUID.randomUUID(), "취업규칙.docx", 1, "bge-m3", 1024))));

		String result = revision(HttpStatus.OK, "revised").revise(room, actor, "취업규칙.docx", edits);

		assertThat(result).startsWith(ThreadDocumentRevision.FAILED).contains("여러 개 고정");
		assertThat(requests).isEmpty();
		verify(documents, never()).original(any(), any(), any());
		verify(documents, never()).upload(any(), any(), any(), any(), any());
	}

	@Test
	void refusedRegistrationAndWorkerFailureAreReportedWithoutTheCause() {
		pinned("취업규칙.docx");
		when(documents.upload(any(), any(), any(), any(), any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

		assertThat(revision(HttpStatus.OK, "revised").revise(room, actor, "취업규칙.docx", edits))
				.startsWith(ThreadDocumentRevision.FAILED).contains("등록할 수 없습니다");
		assertThat(revision(HttpStatus.INTERNAL_SERVER_ERROR, "{}").revise(room, actor, "취업규칙.docx", edits))
				.startsWith(ThreadDocumentRevision.FAILED).contains("문서 저장소 문제").doesNotContain("worker").doesNotContain("500");
	}
}
