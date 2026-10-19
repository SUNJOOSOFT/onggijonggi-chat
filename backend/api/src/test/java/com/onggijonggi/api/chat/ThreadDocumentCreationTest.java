package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.chat.ThreadDocumentPlan.Block;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
 * Class Name : ThreadDocumentCreationTest.java
 * Description : 새 문서 만들기 — 워커 생성 경로·내부 헤더로 요청하고 결과를 방의 출력으로 남기는지, 한도 초과·권한·워커 거절·저장소
 *               실패를 모델이 다시 시도하거나 사용자에게 알릴 수 있는 문장으로 돌려주는지, 같은 턴의 같은 계획이 파일을 또 만들지
 *               않는지 확인한다(#415).
 */
class ThreadDocumentCreationTest {

	private static final String CREATED = """
			{"fileId":"01ARZ3NDEKTSV4RRFFQ69G5FAV","fileName":"주간 보고.docx",
			"objectKey":"documents/t/2026/10/19/01ARZ3NDEKTSV4RRFFQ69G5FAV/주간 보고.docx",
			"contentType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","size":4096,
			"createdAt":"2026-10-19T01:00:00Z","requestId":"r"}""";

	private final ThreadOutputService outputs = mock(ThreadOutputService.class);
	private final List<ClientRequest> requests = new ArrayList<>();
	private final UUID tenant = UUID.randomUUID();
	private final UUID room = UUID.randomUUID();
	private final CurrentActor actor = new CurrentActor(UUID.randomUUID(), "s", "s");
	private final Set<UUID> turn = new LinkedHashSet<>();
	private final ThreadDocumentPlan plan = new ThreadDocumentPlan("주간 보고", "DOCX",
			List.of(new Block("paragraph", null, "이번 주 현황", null, null, null)));

	private ThreadDocumentCreation creation(HttpStatus status, String body, String apiKey) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
			requests.add(request);
			return Mono.just(ClientResponse.create(status).header("Content-Type", "application/json").body(body).build());
		});
		return new ThreadDocumentCreation(outputs, new ObjectMapper(), builder, "http://worker", apiKey);
	}

	private ThreadDocumentCreation creation(HttpStatus status, String body) {
		return creation(status, body, "key");
	}

	private ThreadOutputView view(String fileName) {
		return new ThreadOutputView(UUID.randomUUID(), fileName, "DOCX", 4096, "s", Instant.now());
	}

	private void writable() {
		when(outputs.writableTenant(eq(room), any())).thenReturn(tenant);
		when(outputs.findByRequestKey(eq(room), any())).thenReturn(Optional.empty());
		when(outputs.register(eq(room), any(), any(), anyString(), anyString(), anyLong(), anyString()))
				.thenAnswer(call -> view(call.getArgument(3)));
	}

	@Test
	void createsTheFileThroughTheWorkerAndRegistersItAsAnOutput() {
		writable();

		ThreadDocumentCreation.Result result = creation(HttpStatus.CREATED, CREATED).create(room, actor, "turn-1", plan, turn);

		ClientRequest sent = requests.get(0);
		assertThat(sent.url().toString()).isEqualTo("http://worker" + ThreadDocumentCreation.DOCUMENTS_PATH);
		assertThat(sent.method().name()).isEqualTo("POST");
		assertThat(sent.headers().getFirst("X-Internal-Api-Key")).isEqualTo("key");
		assertThat(sent.headers().getFirst("X-Tenant-Id")).isEqualTo(tenant.toString());
		verify(outputs).register(eq(room), any(), eq(actor.userId()), eq("주간 보고.docx"), eq("DOCX"), eq(4096L),
				eq("documents/t/2026/10/19/01ARZ3NDEKTSV4RRFFQ69G5FAV/주간 보고.docx"));
		assertThat(result.registered()).isTrue();
		assertThat(result.message()).contains("'주간 보고.docx'").contains("등록했습니다").contains("출력 구역");
		assertThat(turn).hasSize(1);
	}

	@Test
	void markdownRequestsUseTheMdKindAndExtension() {
		writable();
		ThreadDocumentPlan markdown = new ThreadDocumentPlan("회의록", "md", plan.blocks());

		creation(HttpStatus.CREATED, CREATED.replace("주간 보고.docx", "회의록.md")).create(room, actor, "turn-1", markdown, turn);

		verify(outputs).register(eq(room), any(), any(), eq("회의록.md"), eq("MD"), anyLong(), anyString());
		assertThat(ThreadDocumentCreation.fileName(markdown)).isEqualTo("회의록.md");
	}

	@Test
	void theWorkerRequestFollowsTheDslAndCarriesNothingElse() {
		var body = new ObjectMapper().writeValueAsString(ThreadDocumentCreation.request(plan, "주간 보고.docx"));
		assertThat(body).contains("\"documentType\":\"DOCX\"").contains("\"outputFormat\":\"DOCX\"")
				.contains("\"fileName\":\"주간 보고.docx\"").contains("\"templateData\":{}")
				.contains("\"operations\":[{\"type\":\"paragraph\",\"text\":\"이번 주 현황\"}]").contains("\"source\":\"bff\"");
		assertThat(body).doesNotContain("templateId").doesNotContain("tenant");
	}

	@Test
	void fileNamesDropPathAndControlCharactersAndFallBack() {
		assertThat(ThreadDocumentCreation.fileName(new ThreadDocumentPlan("../a/b\\c:d", "DOCX", plan.blocks())))
				.isEqualTo("a b c d.docx");
		assertThat(ThreadDocumentCreation.fileName(new ThreadDocumentPlan("보고서‮docx.", "DOCX", plan.blocks())))
				.isEqualTo("보고서 docx.docx");
		assertThat(ThreadDocumentCreation.fileName(new ThreadDocumentPlan("///", "MD", plan.blocks()))).isEqualTo("문서.md");
		assertThat(ThreadDocumentCreation.fileName(new ThreadDocumentPlan("가".repeat(300), "DOCX", plan.blocks())).length())
				.isEqualTo(100 + ".docx".length());
	}

	@Test
	void aPlanOverTheLimitsNeverReachesTheWorker() {
		ThreadDocumentPlan over = new ThreadDocumentPlan("제목", "DOCX",
				List.of(new Block("paragraph", null, "가".repeat(ThreadDocumentPlan.MAX_TEXT_LENGTH + 1), null, null, null)));

		ThreadDocumentCreation.Result result = creation(HttpStatus.CREATED, CREATED).create(room, actor, "turn-1", over, turn);

		assertThat(result.registered()).isFalse();
		assertThat(result.message()).startsWith(ThreadDocumentCreation.FAILED).contains("1번째 요소").contains("10000자");
		assertThat(requests).isEmpty();
		verify(outputs, never()).writableTenant(any(), any());
	}

	@Test
	void aRequestOverTheWorkerSizeLimitNeverReachesTheWorker() {
		writable();
		// 요소마다 글자 한도 안이지만 합치면 1MiB를 넘는 계획이다.
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < 400; i++) blocks.add(new Block("paragraph", null, "가".repeat(ThreadDocumentPlan.MAX_TEXT_LENGTH), null, null, null));

		ThreadDocumentCreation.Result result = creation(HttpStatus.CREATED, CREATED).create(room, actor, "turn-1",
				new ThreadDocumentPlan("큰 문서", "DOCX", blocks), turn);

		assertThat(result.message()).startsWith(ThreadDocumentCreation.FAILED).contains("너무 큽니다");
		assertThat(requests).isEmpty();
	}

	@Test
	void aLockedRoomOrAnOutsiderGetsNoDocument() {
		when(outputs.writableTenant(eq(room), any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
		ThreadDocumentCreation.Result locked = creation(HttpStatus.CREATED, CREATED).create(room, actor, "turn-1", plan, turn);
		assertThat(locked.message()).startsWith(ThreadDocumentCreation.FAILED).contains("잠기거나 보관된 방");

		when(outputs.writableTenant(eq(room), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
		ThreadDocumentCreation.Result outsider = creation(HttpStatus.CREATED, CREATED).create(room, actor, "turn-1", plan, turn);
		assertThat(outsider.message()).startsWith(ThreadDocumentCreation.FAILED).contains("만들 수 없습니다");

		assertThat(requests).isEmpty();
		verify(outputs, never()).register(any(), any(), any(), anyString(), anyString(), anyLong(), anyString());
	}

	@Test
	void theSamePlanInTheSameTurnReturnsTheEarlierOutputWithoutCallingTheWorker() {
		writable();
		ThreadDocumentCreation creation = creation(HttpStatus.CREATED, CREATED);
		creation.create(room, actor, "turn-1", plan, turn);
		when(outputs.findByRequestKey(eq(room), any())).thenReturn(Optional.of(view("주간 보고.docx")));

		ThreadDocumentCreation.Result again = creation.create(room, actor, "turn-1", plan, turn);

		assertThat(requests).hasSize(1);
		assertThat(again.registered()).isTrue();
		assertThat(again.message()).contains("이미").contains("새 파일은 만들지 않았습니다");
		assertThat(turn).hasSize(1);
	}

	@Test
	void theSamePlanInAnotherTurnGetsItsOwnKey() {
		ThreadDocumentCreation creation = creation(HttpStatus.CREATED, CREATED);
		assertThat(creation.requestKey("turn-1", plan)).isEqualTo(creation.requestKey("turn-1", plan))
				.isNotEqualTo(creation.requestKey("turn-2", plan));
		ThreadDocumentPlan other = new ThreadDocumentPlan("주간 보고", "DOCX", List.of(new Block("paragraph", null, "다른 내용", null, null, null)));
		assertThat(creation.requestKey("turn-1", other)).isNotEqualTo(creation.requestKey("turn-1", plan));
	}

	@Test
	void aTurnMakesAtMostThreeDifferentDocumentsButMayRepeatAnEarlierOne() {
		writable();
		ThreadDocumentCreation creation = creation(HttpStatus.CREATED, CREATED);
		List<ThreadDocumentPlan> plans = new ArrayList<>();
		for (int i = 0; i < 4; i++)
			plans.add(new ThreadDocumentPlan("문서" + i, "DOCX", List.of(new Block("paragraph", null, "내용" + i, null, null, null))));
		for (int i = 0; i < ThreadDocumentCreation.MAX_PER_TURN; i++)
			assertThat(creation.create(room, actor, "turn-1", plans.get(i), turn).registered()).isTrue();

		ThreadDocumentCreation.Result fourth = creation.create(room, actor, "turn-1", plans.get(3), turn);

		assertThat(fourth.registered()).isFalse();
		assertThat(fourth.message()).startsWith(ThreadDocumentCreation.FAILED).contains("최대 3개");
		assertThat(requests).hasSize(ThreadDocumentCreation.MAX_PER_TURN);

		when(outputs.findByRequestKey(eq(room), any())).thenReturn(Optional.of(view("문서0.docx")));
		assertThat(creation.create(room, actor, "turn-1", plans.get(0), turn).registered()).isTrue();
	}

	@Test
	void workerRejectionsBecomeReasonsTheModelCanFix() {
		writable();
		String detail = "{\"code\":\"VALIDATION_ERROR\",\"message\":\"operations.0.text: too long\",\"requestId\":\"r\"}";
		for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY}) {
			ThreadDocumentCreation.Result result = creation(status, detail).create(room, actor, "turn-" + status.value(), plan, turn);
			assertThat(result.registered()).isFalse();
			assertThat(result.message()).startsWith(ThreadDocumentCreation.FAILED).contains("검증을 통과하지 못했습니다")
					.contains("operations.0.text: too long").contains("다시 부르세요");
		}
		verify(outputs, never()).register(any(), any(), any(), anyString(), anyString(), anyLong(), anyString());
	}

	@Test
	void authFailuresServerErrorsAndAMissingKeyNeverClaimAFileAndHideTheCause() {
		writable();
		for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE}) {
			ThreadDocumentCreation.Result result = creation(status, "{\"code\":\"X\",\"message\":\"secret detail\"}").create(room, actor,
					"turn-" + status.value(), plan, turn);
			assertThat(result.registered()).isFalse();
			assertThat(result.message()).startsWith(ThreadDocumentCreation.FAILED).contains("문서 저장소 문제").doesNotContain("secret");
		}
		ThreadDocumentCreation.Result noKey = creation(HttpStatus.CREATED, CREATED, "").create(room, actor, "turn-key", plan, turn);
		assertThat(noKey.message()).startsWith(ThreadDocumentCreation.FAILED).doesNotContain("API key");
		assertThat(turn).isEmpty();
		verify(outputs, never()).register(any(), any(), any(), anyString(), anyString(), anyLong(), anyString());
	}

	@Test
	void aRegistrationFailureAfterTheWorkerMadeTheFileIsReportedAsNotCreated() {
		writable();
		when(outputs.register(eq(room), any(), any(), anyString(), anyString(), anyLong(), anyString()))
				.thenThrow(new IllegalArgumentException("출력 등록 값이 올바르지 않다"));

		ThreadDocumentCreation.Result result = creation(HttpStatus.CREATED, CREATED).create(room, actor, "turn-1", plan, turn);

		assertThat(result.registered()).isFalse();
		assertThat(result.message()).startsWith(ThreadDocumentCreation.FAILED);
		assertThat(turn).isEmpty();
	}

	@Test
	void aConcurrentCallThatRegisteredFirstIsReturnedInsteadOfAFailure() {
		writable();
		when(outputs.register(eq(room), any(), any(), anyString(), anyString(), anyLong(), anyString()))
				.thenThrow(new IllegalStateException("같은 요청 키로 다른 파일이 이미 등록되어 있다"));
		when(outputs.findByRequestKey(eq(room), any())).thenReturn(Optional.empty(), Optional.of(view("주간 보고.docx")));

		ThreadDocumentCreation.Result result = creation(HttpStatus.CREATED, CREATED).create(room, actor, "turn-1", plan, turn);

		assertThat(result.registered()).isTrue();
		assertThat(result.message()).contains("'주간 보고.docx'").contains("등록했습니다");
	}
}
