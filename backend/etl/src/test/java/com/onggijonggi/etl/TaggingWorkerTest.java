package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.document.TagPrompt;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Class Name : TaggingWorkerTest.java
 * Description : 태깅 작업(#362)의 실패 처리 — 일시 장애면 묶음의 나머지를 미루고 쉬는지(대기 문서가 한꺼번에 실패로 밀리지 않게), 영구 실패는
 *               긴 간격으로 남기고 다음 문서로 넘어가는지, 종료 중 끊긴 태깅은 실패로 남기지 않는지 본다. 스레드는 띄우되 문서 처리 워커를
 *               꺼 둬 스스로 묶음을 돌지 않게 하고, 묶음은 테스트가 직접 돌린다.
 */
class TaggingWorkerTest {

	private final TagStore store = mock(TagStore.class);
	private final Tagger tagger = mock(Tagger.class);
	private final IngestionWorkers workers = mock(IngestionWorkers.class);
	private final TaggingProperties settings = TaggingClientTest.properties("http://unused");
	private final TagIndex index = mock(TagIndex.class);
	private final TaggingWorker worker = new TaggingWorker(store, tagger, index, mock(ChunkIndex.class), workers, settings);
	private final List<TagStore.Target> targets = List.of(target(), target(), target());

	@AfterEach
	void tearDown() {
		worker.stop();
	}

	@Test
	void aTransientFailureStopsTheBatchAndBacksOff() {
		when(store.targets(anyString(), anyInt())).thenReturn(targets);
		when(tagger.tag(any(RunStore.Job.class))).thenThrow(EtlFailure.transientFailure("TAGGING_UNAVAILABLE", "끊김", null));
		worker.start();

		assertThat(worker.cycle()).as("쉬게 0을 돌려준다").isZero();

		verify(tagger, times(1)).tag(any(RunStore.Job.class));
		verify(store).saveFailed(eq(targets.get(0)), eq("TAGGING_UNAVAILABLE"), anyString(), eq(settings.retryFirstDelay()), eq(settings.retryDelay()));
	}

	@Test
	void aPermanentFailureWaitsLongAndTheBatchGoesOn() {
		when(store.targets(anyString(), anyInt())).thenReturn(targets);
		when(tagger.tag(any(RunStore.Job.class))).thenThrow(EtlFailure.permanent("SOURCE_MISSING", "원본 없음"));
		worker.start();

		assertThat(worker.cycle()).isEqualTo(3);

		verify(store, times(3)).saveFailed(any(), eq("SOURCE_MISSING"), anyString(), eq(settings.permanentRetryDelay()), eq(settings.permanentRetryDelay()));
	}

	@Test
	void aPermanentFailureThatCannotBeRecordedPausesInsteadOfSpinning() {
		when(store.targets(anyString(), anyInt())).thenReturn(targets);
		when(tagger.tag(any(RunStore.Job.class))).thenThrow(EtlFailure.permanent("SOURCE_MISSING", "원본 없음"));
		doThrow(new IllegalStateException("DB 끊김")).when(store)
				.saveFailed(any(), anyString(), anyString(), any(Duration.class), any(Duration.class));
		worker.start();

		assertThat(worker.cycle()).as("같은 문서가 곧바로 다시 잡히니 쉰다").isZero();
		verify(tagger, times(1)).tag(any(RunStore.Job.class));
	}

	@Test
	void aTaggingCutOffByShutdownIsNotRecordedAsAFailure() {
		when(tagger.tag(any(RunStore.Job.class))).thenThrow(EtlFailure.transientFailure("TAGGING_UNAVAILABLE", "인터럽트", null));

		// 시작하지 않은(또는 멈춘) 작업 — 종료 중과 같다.
		assertThat(worker.tag(targets.get(0), "fp")).isFalse();

		verify(store, never()).saveFailed(any(), anyString(), anyString(), any(Duration.class), any(Duration.class));
	}

	@Test
	void aTaggedRunIsIndexedThenSaved() {
		TagPrompt.Tags tags = new TagPrompt.Tags("기타", List.of("연차"), "요약");
		when(tagger.tag(any(RunStore.Job.class))).thenReturn(tags);
		when(store.saveDone(targets.get(0), tags, "fp")).thenReturn(true);
		worker.start();

		assertThat(worker.tag(targets.get(0), "fp")).isTrue();

		var order = inOrder(index, store);
		order.verify(index).write(targets.get(0), tags, "fp");
		order.verify(store).saveDone(targets.get(0), tags, "fp");
		verify(index, never()).delete(targets.get(0).document(), 1);
	}

	/** 태깅하는 동안 회차가 정리됐으면(저장 거부) 방금 쓴 태그 색인 문서를 거둔다. 실패는 아니다. */
	@Test
	void aRunRemovedWhileTaggingIsWithdrawnFromTheIndex() {
		TagPrompt.Tags tags = new TagPrompt.Tags("기타", List.of("연차"), "요약");
		when(tagger.tag(any(RunStore.Job.class))).thenReturn(tags);
		when(store.saveDone(targets.get(1), tags, "fp")).thenReturn(false);
		worker.start();

		assertThat(worker.tag(targets.get(1), "fp")).isTrue();
		verify(index).delete(targets.get(1).document(), 1);
	}

	@Test
	void anUnexpectedErrorIsATransientFailure() {
		when(tagger.tag(any(RunStore.Job.class))).thenThrow(new IllegalStateException("결함"));
		worker.start();

		assertThat(worker.tag(targets.get(0), "fp")).isFalse();
		verify(store).saveFailed(eq(targets.get(0)), eq("UNEXPECTED"), eq("fp"), eq(settings.retryFirstDelay()), eq(settings.retryDelay()));
	}

	/** 기동 뒤 첫 주기에 한 번, 색인이 있어도 DB 태그로 맞춘다(채우던 중 재기동됐을 수 있다). 그 뒤 주기에는 다시 하지 않는다. */
	@Test
	void theIndexIsAlignedWithTheStoreOnceAfterStartup() {
		var stored = new TagStore.Stored(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
				new TagPrompt.Tags("기타", List.of(), "요약"), "fp");
		when(index.ensure()).thenReturn(false);
		when(store.stored(any(), anyInt())).thenReturn(List.of(stored), List.of());
		when(store.targets(anyString(), anyInt())).thenReturn(List.of());
		worker.start();

		worker.cycle();
		worker.cycle();

		verify(index, times(1)).restore(List.of(stored));
	}

	/** 태그 색인을 새로 만들었으면 DB 태그를 쪽 단위로 다시 쓴다. 채우다 실패하면 태깅은 그대로 하고 다음 주기에(색인이 이미 있어도) 이어 한다. */
	@Test
	void aRecreatedIndexIsRestoredFromTheStoreEvenAcrossAFailure() {
		var stored = new TagStore.Stored(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
				new TagPrompt.Tags("기타", List.of(), "요약"), "fp");
		when(index.ensure()).thenReturn(true, false);
		when(store.stored(any(), anyInt())).thenReturn(List.of(stored), List.of(stored), List.of());
		doThrow(EtlFailure.transientFailure("TAG_INDEX_UNAVAILABLE", "끊김", null)).doNothing().when(index).restore(any());
		when(store.targets(anyString(), anyInt())).thenReturn(List.of());
		worker.start();

		assertThat(worker.cycle()).as("복원이 실패해도 태깅은 계속한다").isZero();
		assertThat(worker.cycle()).isZero();

		verify(index, times(2)).restore(List.of(stored));
		verify(store, times(2)).targets(anyString(), anyInt());
	}

	/** 로그에는 실패의 HTTP 상태만 남긴다 — 연결 오류 메시지에는 태깅 서버 주소가 들어 있다. */
	@Test
	void onlyTheHttpStatusOfAFailureIsLogged() {
		assertThat(TaggingWorker.httpStatus(EtlFailure.permanent("TAGGING_REJECTED", "HTTP 404 {\"detail\":\"Not Found\"}"))).isEqualTo("HTTP 404");
		assertThat(TaggingWorker.httpStatus(EtlFailure.transientFailure("TAGGING_UNAVAILABLE", "I/O error on POST request for \"http://h:1/v1\"", null)))
				.isEmpty();
	}

	@Test
	void theLoggedReasonAndRetryDelayAreReadable() {
		assertThat(TaggingWorker.reason("TAGGING_REJECTED", EtlFailure.permanent("TAGGING_REJECTED", "HTTP 404 {}"))).isEqualTo("TAGGING_REJECTED HTTP 404");
		assertThat(TaggingWorker.reason("TAGGING_UNAVAILABLE", EtlFailure.transientFailure("TAGGING_UNAVAILABLE", "연결 거부", null))).isEqualTo("TAGGING_UNAVAILABLE");
		assertThat(TaggingWorker.reason("UNEXPECTED", null)).isEqualTo("UNEXPECTED");
		assertThat(List.of(Duration.ofDays(7), Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofSeconds(90)).stream().map(TaggingWorker::readable))
				.containsExactly("7일", "1시간", "1분", "90초");
	}

	private static TagStore.Target target() {
		return new TagStore.Target(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "a.txt", "d", null);
	}
}
