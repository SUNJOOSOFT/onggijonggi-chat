package com.onggijonggi.etl;

import com.onggijonggi.common.document.TagPrompt;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Class Name : TaggingWorker.java
 * Description : 방 문서 태깅 작업(#362). 문서 처리(IngestionWorkers)와 따로 도는 스레드 하나가, 검색 준비가 끝난 문서의 현재 회차 중
 *               태그가 없거나·태깅 설정이 바뀌었거나·실패해 다시 할 때가 된 것을 골라 태그만 붙인다(조각·임베딩은 다시 하지 않는다).
 *               새 문서, 다시 만들기(#348)로 새로 생긴 회차, 첫 배포의 기존 문서, 설정 변경이 모두 이 한 경로로 처리된다.
 *               태깅이 늦거나 실패해도 문서 상태에는 영향이 없다 — 문서는 이미 "검색 준비 완료"이고, 태그가 없는 동안 검색의 태그 채널에만
 *               걸리지 않는다. 태깅 서버 설정이 비면 돌지 않는다(본문을 다른 곳으로 대신 보내지 않는다).
 *               로그에는 문서·회차 ID와 건수·시간, 실패 사유 코드·HTTP 상태만 남긴다(본문·태그 원문·예외 메시지는 남기지 않는다).
 *               한 주기의 흐름(loop → cycle):
 *                 1. 기동 뒤 한 번 지난 실패를 풀어 바로 다시 대상이 되게 한다(설정·주소를 고쳐 재기동한 경우).
 *                 2. 문서 처리가 꺼져 있거나 검색 인덱스 준비·이전 중이면 기다린다(waitingReason — 5분마다 알린다).
 *                 3. 태그 색인 별칭을 확인하고, 기동 뒤 처음이거나 새로 만들었으면 DB(정본)의 태그로 다시 채운다(restorePending).
 *                 4. 대상 한 묶음을 차례로 태깅한다. 일시 장애면 묶음을 멈추고 쉬며, 잇따를수록 오래 쉰다(stalls).
 *               restorePending·stalls는 이 스레드만 읽고 쓴다(volatile이 아니다).
 */
@Component
public class TaggingWorker implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(TaggingWorker.class);
	/** 종료 때 진행 중인 태깅을 기다리는 상한. 끊긴 태깅은 다음 기동에서 다시 대상이 된다. */
	private static final Duration STOP_WAIT = Duration.ofSeconds(5);
	/** 태그 색인을 다시 채울 때 한 요청에 담는 수. */
	private static final int RESTORE_PAGE = 500;

	private final TagStore store;
	private final Tagger tagger;
	private final TagIndex index;
	private final ChunkIndex chunks;
	private final IngestionWorkers workers;
	private final TaggingProperties settings;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile Thread thread;
	/** 태깅이 꺼진 이유. null이면 돌고 있다. */
	private volatile String disabledReason;
	/** 태깅이 기다리는 이유(문서 처리 꺼짐·검색 인덱스 준비 전). null이면 기다리지 않는다. */
	private volatile String waitingReason;
	/** 태그 색인을 DB 태그로 다시 채워야 한다(기동 뒤 처음·색인을 새로 만들었을 때, 실패하면 다음 주기에 다시). */
	private boolean restorePending = true;
	/** 일시 장애로 묶음을 잇따라 멈춘 횟수. 대상이 없거나 한 건이라도 태깅하면 0으로 돌아간다. */
	private int stalls;

	public TaggingWorker(TagStore store, Tagger tagger, TagIndex index, ChunkIndex chunks, IngestionWorkers workers, TaggingProperties settings) {
		this.store = store;
		this.tagger = tagger;
		this.index = index;
		this.chunks = chunks;
		this.workers = workers;
		this.settings = settings;
	}

	@Override
	public void start() {
		if (running.get()) return;
		if (!settings.enabled()) {
			disabledReason = "태깅 서버 주소·모델(app.etl.tagging.url·model, compose는 TAGGING_URL·TAGGING_MODEL)이 비어 있다";
			log.info("문서 태깅을 시작하지 않는다: {}", disabledReason);
			return;
		}
		running.set(true);
		thread = new Thread(this::loop, "etl-tagging");
		thread.setDaemon(true);
		thread.start();
		log.info("문서 태깅 스레드 시작(모델 {} · 카테고리 {}개)", settings.model(), settings.categories().size());
	}

	private void loop() {
		boolean released = false;
		while (running.get()) {
			int done = 0;
			try {
				// 기동 뒤 한 번. DB가 아직 안 되면 다음 바퀴에 다시 한다.
				if (!released) {
					releaseFailures();
					released = true;
				}
				// 문서 처리가 꺼져 있거나 조각 인덱스 준비·이전이 끝나지 않았으면 기다린다 — 태그만 앞서 쌓이지 않게.
				waitingReason = !workers.isRunning() ? "문서 처리가 꺼져 있다(임베딩 설정 등)"
						: !chunks.prepared() ? "검색 인덱스 준비 전이다" : chunks.migrating() ? "검색 인덱스를 옮기는 중이다" : null;
				if (waitingReason == null) done = cycle();
			} catch (EtlFailure failure) {
				// 태그 색인 별칭 확인·생성(index.ensure) 실패. 대상 조회 실패와 구분하고, 반복되므로 스택 없이 남긴다.
				log.warn("태그 검색 인덱스를 준비하지 못했다({}) — 잠시 뒤 다시 본다", reason(failure.code(), failure));
			} catch (Throwable error) {
				log.warn("문서 태깅 대상을 읽지 못했다 — 잠시 뒤 다시 본다", error);
			}
			if (done == 0) pause();
		}
	}

	/** 기동 뒤 한 번, 지난 실패를 바로 다시 시도하게 푼다(설정·주소를 고쳐 재기동한 경우). */
	void releaseFailures() {
		int released = store.releaseFailures();
		if (released > 0) log.info("지난 태깅 실패 {}건을 바로 다시 시도한다(기동 — 태깅 설정·서버 주소가 바뀌었을 수 있다)", released);
	}

	/** 대상 한 묶음을 태깅한다. 처리한 수를 돌려준다. */
	int cycle() {
		// 기동 뒤 처음, 또는 태그 색인만 사라졌으면(색인 삭제·스냅샷 복원) DB가 정본이므로 DB 태그로 다시 채운다 — LLM은 다시 부르지 않는다.
		if (index.ensure()) restorePending = true;
		if (restorePending) {
			try {
				restoreIndex();
				restorePending = false;
			} catch (RuntimeException error) {
				// 다음 주기에 다시 한다. 태깅은 막지 않는다 — 복원이 계속 실패해도 새 태그는 붙는다(단건 쓰기는 별칭을 다시 확인한다).
				log.warn("태그 검색 인덱스를 DB 태그로 맞추지 못했다({}) — 다음 주기에 다시 한다", error instanceof EtlFailure failure
						? reason(failure.code(), failure) : error.getClass().getSimpleName());
			}
		}
		String fingerprint = settings.fingerprint();
		List<TagStore.Target> targets = store.targets(fingerprint, settings.batch());
		// 할 일이 없으면 쉬는 간격을 처음으로 되돌린다 — 장애 뒤 늘어난 간격 때문에 새 문서를 오래 기다리지 않게.
		if (targets.isEmpty()) stalls = 0;
		for (TagStore.Target target : targets) {
			if (!running.get()) break;
			// 일시 장애면 묶음의 나머지를 지금 시도하지 않고 쉰다 — 태깅 서버가 잠깐 끊긴 사이 대기 문서가 한꺼번에 실패로 밀리지 않게.
			if (!tag(target, fingerprint)) {
				stalls++;
				return 0;
			}
			stalls = 0;
		}
		return targets.size();
	}

	/** DB의 태그를 태그 색인에 다시 쓴다. */
	private void restoreIndex() {
		int restored = 0;
		UUID after = TagStore.FIRST;
		for (List<TagStore.Stored> page = store.stored(after, RESTORE_PAGE); !page.isEmpty(); page = store.stored(after, RESTORE_PAGE)) {
			index.restore(page);
			restored += page.size();
			after = page.get(page.size() - 1).id();
		}
		if (restored > 0) log.info("태그 검색 인덱스를 DB의 태그 {}건으로 맞췄다", restored);
	}

	/** 한 회차를 태깅한다. 일시 장애로 실패하면 false다. */
	boolean tag(TagStore.Target target, String fingerprint) {
		long started = System.nanoTime();
		try {
			TagPrompt.Tags tags = tagger.tag(target.job());
			index.write(target, tags, fingerprint);
			if (!store.saveDone(target, tags, fingerprint)) {
				// 태깅하는 동안 문서가 지워졌거나 회차가 정리됐다. 방금 쓴 태그 문서를 거둔다.
				index.delete(target.document(), target.runSeq());
				log.info("태깅 중 정리된 회차라 태그를 버린다: doc={} run={}", target.document(), target.runSeq());
				return true;
			}
			log.info("문서 태깅 완료: doc={} run={} 분류={} 키워드 {}개 ({}ms)", target.document(), target.runSeq(),
					tags.classified() ? "됨" : "미분류", tags.keywords().size(), (System.nanoTime() - started) / 1_000_000);
			return true;
		} catch (EtlFailure failure) {
			// 영구 실패는 기록했으면 다음 문서로 간다. 기록하지 못했으면 같은 문서가 곧바로 다시 잡히므로 쉰다.
			return failed(target, failure.code(), failure.permanent(), fingerprint, failure) && failure.permanent();
		} catch (RuntimeException unexpected) {
			failed(target, "UNEXPECTED", false, fingerprint, unexpected);
			return false;
		}
	}

	/** 실패를 남긴다. 남겼으면 true. */
	private boolean failed(TagStore.Target target, String code, boolean permanent, String fingerprint, RuntimeException cause) {
		if (!running.get()) {
			// 종료 중 끊긴 요청이다. 실패로 남기면 다음 기동 뒤에도 간격만큼 밀린다 — 남기지 않고 다음 기동 때 바로 다시 한다.
			log.info("종료 중이라 태깅을 멈춘다 — 다음 기동 때 다시 한다: doc={} run={}", target.document(), target.runSeq());
			return false;
		}
		String stored = code.length() > 64 ? code.substring(0, 64) : code;
		// 영구 실패(입력 거절·원본 없음 등)는 다시 해도 같으니 긴 간격을 둔다. 설정·주소를 고쳐 재기동하면 바로 다시 대상이 된다(releaseFailures).
		Duration first = permanent ? settings.permanentRetryDelay() : settings.retryFirstDelay();
		Duration max = permanent ? settings.permanentRetryDelay() : settings.retryDelay();
		try {
			store.saveFailed(target, stored, fingerprint, first, max);
		} catch (RuntimeException error) {
			log.warn("태깅 실패를 기록하지 못했다 — 잠시 뒤 다시 대상이 된다: doc={} run={}", target.document(), target.runSeq(), error);
			return false;
		}
		// 예기치 못한 오류(코드 결함)만 스택을 남긴다. 태깅 서버 장애는 문서마다 반복되므로 한 줄로 남긴다.
		String retry = permanent ? "영구 실패 — " + readable(max) + " 뒤 또는 ETL 재기동 때 다시 시도" : "일시 장애 — 간격을 늘려 가며 다시 시도";
		if ("UNEXPECTED".equals(code))
			log.warn("문서 태깅 실패({}, {}): doc={} run={}", code, retry, target.document(), target.runSeq(), cause);
		else
			log.warn("문서 태깅 실패({}, {}): doc={} run={}", reason(code, cause instanceof EtlFailure failure ? failure : null), retry,
					target.document(), target.runSeq());
		return true;
	}

	/**
	 * 로그에 남길 사유: 코드와(있으면) HTTP 상태. 예외 메시지 전체는 남기지 않는다 — 연결 오류 메시지에는 태깅 서버 주소가 들어 있다.
	 * 상태만 있어도 주소·모델 이름 오타(404·400)와 서버 장애(5xx)를 가를 수 있다.
	 */
	static String reason(String code, EtlFailure failure) {
		String status = failure == null ? "" : httpStatus(failure);
		return status.isEmpty() ? code : code + " " + status;
	}

	/** 간격을 읽기 쉽게(예: 7일, 1시간, 30초). */
	static String readable(Duration delay) {
		if (delay.toSeconds() % 86_400 == 0) return delay.toDays() + "일";
		if (delay.toSeconds() % 3_600 == 0) return delay.toHours() + "시간";
		if (delay.toSeconds() % 60 == 0) return delay.toMinutes() + "분";
		return delay.toSeconds() + "초";
	}

	/** 실패의 HTTP 상태(예: "HTTP 404")만 꺼낸다. 없으면 빈 글자. */
	static String httpStatus(EtlFailure failure) {
		// 메시지는 "코드: 내용"이다(EtlFailure).
		String message = failure.getMessage().substring(failure.code().length() + 2);
		if (!message.startsWith("HTTP ")) return "";
		int end = 5;
		while (end < message.length() && Character.isDigit(message.charAt(end))) end++;
		return message.substring(0, end);
	}

	private void pause() {
		// 일시 장애가 잇따르면 쉬는 시간을 두 배씩 늘린다 — 태깅 서버가 오래 끊긴 동안 문서를 하나씩 두드리지 않게. 상한은 실패한
		// 회차의 재시도 간격 상한(retryDelay)과 같게 둔다(그보다 오래 쉬면 다시 할 때가 된 회차를 늦게 본다). 16은 시프트가 넘치지 않게
		// 하는 안전장치일 뿐이다(그 전에 상한에 닿는다).
		long idle = settings.idleDelay().toMillis();
		long longest = Math.max(idle, settings.retryDelay().toMillis());
		long delay = Math.min(idle << Math.min(stalls, 16), longest);
		try {
			Thread.sleep(delay);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			running.set(false);
		}
	}

	@Override
	public void stop() {
		running.set(false);
		Thread current = thread;
		if (current == null) return;
		current.interrupt();
		try {
			current.join(STOP_WAIT.toMillis());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	/** 태깅이 꺼져 있거나 기다리는 중이면 주기적으로 다시 알린다(검색의 태그 채널을 켰는데 태그가 붙지 않는 이유를 찾게). */
	@Scheduled(fixedDelayString = "${app.etl.disabled-warn-delay:5m}", initialDelayString = "${app.etl.disabled-warn-delay:5m}")
	public void warnIfDisabled() {
		if (disabledReason != null) log.info("문서 태깅이 꺼져 있다 — 검색의 태그 채널에 쓸 태그가 붙지 않는다: {}", disabledReason);
		else if (waitingReason != null) log.info("문서 태깅이 기다리는 중이다 — {}", waitingReason);
	}
}
