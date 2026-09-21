package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : CollabThreadController.java
 * Description : 협업 스레드 조회·참가자 관리 계약 구현체. 1:1 대화를 다루는 ChatController와 저장
 *               테이블도 소유 모델도 달라 컨트롤러를 나눈다. DIRECT·COLLAB 공용 이력 조회는
 *               `ThreadController`가 따로 맡는다(이슈 #219) — 이 클래스는 이제 진짜로 COLLAB
 *               전용이다.
 *
 *               방을 만드는 경로는 아직 여기 없다 — 부를 화면이 없어 이슈 #23과 함께 진행한다
 *               (이슈 #22 코멘트). 그래서 참가자 관리는 이미 있는 방 위에서만 동작한다.
 *
 *               초대·제거·위임은 대상을 Keycloak subject로 지목한다. 사용자 검색 API가 없어
 *               호출자가 이미 아는 식별자를 그대로 받는 것 말고는 성립하는 계약이 없다(이슈 #20).
 */
@RestController
public class CollabThreadController {

	/**
	* 표시 이름 조회를 한 번에 이만큼만 내보낸다(이슈 #200). flatMap의 기본 상한은 256이라, 캐시가
	* 비어 있을 때 방·참가자가 많으면 Keycloak Admin API로 한꺼번에 몰려 나간다 — 이 엔드포인트엔
	* WS 핸드셰이크(#6)와 달리 레이트리밋도 없다. 캐시(KeycloakAdminClient)가 평소 호출을 줄이고,
	* 이 상한이 캐시 미스가 몰리는 순간을 눌러 준다.
	*/
	private static final int DISPLAY_NAME_LOOKUP_CONCURRENCY = 8;

	private final CurrentActorProvider currentActorProvider;
	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final ThreadMembershipService threadMembershipService;
	private final ThreadParticipantService threadParticipantService;
	private final ThreadLifecycleService threadLifecycleService;
	private final KeycloakAdminClient keycloakAdminClient;
	private final CollabThreadCreationService collabThreadCreationService;
	private final ThreadMessageQueryService threadMessageQueryService;

	public CollabThreadController(CurrentActorProvider currentActorProvider, ThrRepository thrRepository,
			ThrMbrRepository thrMbrRepository, ThreadMembershipService threadMembershipService,
			ThreadParticipantService threadParticipantService, ThreadLifecycleService threadLifecycleService,
			KeycloakAdminClient keycloakAdminClient, CollabThreadCreationService collabThreadCreationService,
			ThreadMessageQueryService threadMessageQueryService) {
		this.currentActorProvider = currentActorProvider;
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.threadMembershipService = threadMembershipService;
		this.threadParticipantService = threadParticipantService;
		this.threadLifecycleService = threadLifecycleService;
		this.keycloakAdminClient = keycloakAdminClient;
		this.collabThreadCreationService = collabThreadCreationService;
		this.threadMessageQueryService = threadMessageQueryService;
	}

	/**
	* 어떤 방을 내려줄지 고르는 것은 서버 몫이라, 호출자가 참가자인 방만 나간다. ARCHIVED는 빠진다 —
	* 그 방을 다시 보려면 아래 보관함 엔드포인트를 쓴다(#131).
	*/
	@GetMapping("/api/collab/threads")
	public Flux<CollabThreadSummary> listThreads() {
		return actorUserId()
				.flatMap(userId -> Mono
						.fromCallable(() -> joinedThreads(userId, thr -> thr.getStatus() != ThrStatus.ARCHIVED))
						.subscribeOn(Schedulers.boundedElastic())
						.flatMap(threads -> summariesFor(threads, userId)))
				.flatMapMany(Flux::fromIterable);
	}

	/**
	* ARCHIVED로 넘어간 방은 삭제된 게 아니라 목록에서 빠질 뿐이라, 메시지를 계속 읽을 수 있어야
	* 한다(#131) — 그 방을 다시 찾는 별도 조회 경로.
	*/
	@GetMapping("/api/collab/threads/archived")
	public Flux<CollabThreadSummary> listArchivedThreads() {
		return actorUserId()
				.flatMap(userId -> Mono
						.fromCallable(() -> joinedThreads(userId, thr -> thr.getStatus() == ThrStatus.ARCHIVED))
						.subscribeOn(Schedulers.boundedElastic())
						.flatMap(threads -> summariesFor(threads, userId)))
				.flatMapMany(Flux::fromIterable);
	}

	/**
	* 인증된 사용자는 제목만으로 방을 만들며, 생성 서비스가 최초 OWNER 참가를 함께 만든다.
	* Idempotency-Key 헤더가 있으면(이슈 #149) 응답 유실 뒤 재시도에도 같은 방을 그대로 돌려준다 —
	* 헤더가 없는 호출은 이 계약을 요구하지 않은 것으로 보고 기존과 동일하게 매번 새로 만든다.
	*/
	@PostMapping("/api/collab/threads")
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<CreateCollabThreadResponse> createThread(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CreateCollabThreadRequest request) {
		return actorUserId()
				.flatMap(userId -> Mono
						.fromCallable(() -> createWithRetry(userId, request.title(), idempotencyKey))
						.subscribeOn(Schedulers.boundedElastic()))
				.map(CreateCollabThreadResponse::new);
	}

	/**
	* 같은 idempotency key로 동시에 들어온 다른 요청이 먼저 커밋되면, 우리 쪽 저장은 thr_idm_key의
	* 유니크 인덱스에 막혀 트랜잭션째 롤백된다(CollabThreadCreationService 주석 참고). 그 순간엔
	* 이긴 쪽 행이 이미 커밋돼 있으므로, 한 번만 다시 불러 그 결과를 그대로 따라간다 — 두 번째
	* 시도까지 같은 경합에 걸릴 일은 없다.
	*/
	private UUID createWithRetry(UUID userId, String title, String idempotencyKey) {
		try {
			return collabThreadCreationService.createBlocking(userId, title, idempotencyKey);
		} catch (DataIntegrityViolationException raced) {
			return collabThreadCreationService.createBlocking(userId, title, idempotencyKey);
		}
	}

	/** 명단은 참가자면 누구나 본다 — 제거·위임 대상을 지목하려면 먼저 누가 있는지 알아야 한다. */
	@GetMapping("/api/collab/threads/{threadId}/participants")
	public Flux<ParticipantView> listParticipants(@PathVariable UUID threadId) {
		return actorUserId()
				.flatMap(userId -> threadParticipantService.list(threadId, userId))
				.flatMap(this::withParticipantDisplayNames)
				.flatMapMany(Flux::fromIterable);
	}

	/** 초대는 OWNER만 한다. 이미 참가 중인 사람을 다시 초대해도 성공으로 답한다(멱등). */
	@PostMapping("/api/collab/threads/{threadId}/participants")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> inviteParticipant(@PathVariable UUID threadId,
			@Valid @RequestBody ParticipantSubjectRequest request) {
		return actorUserId()
				.flatMap(userId -> threadParticipantService.invite(threadId, userId, request.subject()));
	}

	/**
	* 초대할 사람을 이름으로 찾는다(이슈 #172). 초대와 같은 인가(OWNER)를 쓴다 — 초대할 수 없는
	* 사람에게 검색을 열면 계정 목록만 노출된다.
	*
	* 인가와 COLLAB 종류 확인을 먼저 한 뒤, 검색어가 너무 짧으면 realm을 훑지 않고 빈 목록으로 답한다.
	* 따라서 DIRECT ID는 검색어 길이와 관계없이 존재 비노출 404다.
	*/
	@GetMapping("/api/collab/threads/{threadId}/participants/candidates")
	public Flux<InviteCandidate> searchInviteCandidates(@PathVariable UUID threadId,
			@RequestParam("q") String query) {
		String trimmed = query.trim();
		return actorUserId()
				.flatMap(userId -> threadParticipantService.searchCandidates(threadId, userId, trimmed))
				.flatMapMany(Flux::fromIterable);
	}

	/** 대기 초대를 거둔다(이슈 #172). 초대해 놓고 잊은 것을 되돌릴 유일한 경로다. */
	@DeleteMapping("/api/collab/threads/{threadId}/invitations/{subject}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> revokeInvitation(@PathVariable UUID threadId, @PathVariable String subject) {
		return actorUserId()
				.flatMap(userId -> threadParticipantService.revokeInvitation(threadId, userId, subject));
	}

	/**
	* 자진 탈퇴와 OWNER의 타인 제거를 한 경로로 받는다 — 같은 리소스(참가)를 끝내는 일이라
	* 나누지 않고, 누구를 지목했는지에 따라 서비스가 규칙을 가른다.
	*/
	@DeleteMapping("/api/collab/threads/{threadId}/participants/{subject}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> removeParticipant(@PathVariable UUID threadId, @PathVariable String subject) {
		return currentActorProvider.currentActor()
				.flatMap(actor -> threadParticipantService.remove(threadId, actor.userId(), actor.subject(), subject));
	}

	/** 스레드의 OWNER 자리를 교체한다. OWNER가 방을 떠나려면 이걸 먼저 해야 한다. */
	@PutMapping("/api/collab/threads/{threadId}/owner")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> transferOwner(@PathVariable UUID threadId,
			@Valid @RequestBody ParticipantSubjectRequest request) {
		return actorUserId()
				.flatMap(userId -> threadParticipantService.transferOwner(threadId, userId, request.subject()));
	}

	/** OWNER만 잠글 수 있다. LOCKED는 되돌릴 수 있는 상태 — 새 메시지·초대 등 쓰기만 막는다(#131). */
	@PutMapping("/api/collab/threads/{threadId}/lock")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> lockThread(@PathVariable UUID threadId) {
		return actorUserId().flatMap(userId -> threadLifecycleService.lock(threadId, userId));
	}

	/** OWNER만 보관할 수 있다. ARCHIVED는 최종 상태다(#131). */
	@PutMapping("/api/collab/threads/{threadId}/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> archiveThread(@PathVariable UUID threadId) {
		return actorUserId().flatMap(userId -> threadLifecycleService.archive(threadId, userId));
	}

	/** OWNER만 지울 수 있다. 참여·메시지는 cascade로 함께 지워진다(#131). */
	@DeleteMapping("/api/collab/threads/{threadId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> deleteThread(@PathVariable UUID threadId) {
		return actorUserId().flatMap(userId -> threadLifecycleService.delete(threadId, userId));
	}

	private Mono<UUID> actorUserId() {
		return currentActorProvider.currentActor().map(CurrentActor::userId);
	}

	/**
	* 참가 행을 먼저 읽고 그 id로 Thread를 가져온다. thr_mbr에 연관관계를 매핑하지 않아(ChatSess와
	* 같은 이유) 조인 대신 두 번 조회하지만, 두 번째는 findAllById 한 번이라 건수만큼 늘지 않는다.
	* statusFilter로 일반 목록(ACTIVE·LOCKED)과 보관함(ARCHIVED)을 같은 조회 로직으로 가른다(#131).
	*
	* Casbin 도입 전 목록 필터링 기준(#209): thr_mbr ACTIVE 참가 여부만으로 거른다 — OWNER든
	* 초대받은 MEMBER든 참가 행이 있어야 보인다. Casbin이 들어오면(v0.3) 이 자리가 정책 판정
	* 호출로 바뀌지만, 그 전까지는 이 최소 스코프가 최종 필터링 기준이다.
	*/
	private List<Thr> joinedThreads(UUID userId, Predicate<Thr> statusFilter) {
		List<UUID> joinedIds = thrMbrRepository.findByUserIdAndStatus(userId, ThrMbrStatus.ACTIVE)
				.stream()
				.map(ThrMbr::getThrId)
				.toList();
		if (joinedIds.isEmpty()) {
			return List.of();
		}
		return thrRepository.findAllById(joinedIds).stream()
				.filter(thr -> thr.getKind() == ThrKind.COLLAB)
				.filter(statusFilter)
				.sorted(Comparator.comparing(Thr::getCreatedAt).reversed())
				.toList();
	}

	/**
	* 각 방의 참가자 subject는 threadParticipantService(이미 검증된 조회)로 얻고, 표시 이름은 여기서
	* 한꺼번에 붙인다 — 같은 사람이 여러 방에 있으면 Keycloak Admin API를 그만큼 중복 호출하게 되므로
	* 스레드 목록 전체에서 subject를 한 번만 모아 조회한다(이슈 #128).
	*/
	private Mono<List<CollabThreadSummary>> summariesFor(List<Thr> threads, UUID userId) {
		return Flux.fromIterable(threads)
				.flatMapSequential(thr -> threadParticipantService.list(thr.getId(), userId)
						.map(participants -> new ThreadWithSubjects(thr,
								participants.stream().map(ThreadParticipant::subject).toList())))
				.collectList()
				.flatMap(this::withDisplayNames);
	}

	private Mono<List<CollabThreadSummary>> withDisplayNames(List<ThreadWithSubjects> threads) {
		Set<String> subjects = threads.stream()
				.flatMap(thread -> thread.subjects().stream())
				.collect(Collectors.toSet());
		return Flux.fromIterable(subjects)
				.flatMap(subject -> keycloakAdminClient.displayName(subject)
						.map(displayName -> Map.entry(subject, displayName.orElse(subject))),
						DISPLAY_NAME_LOOKUP_CONCURRENCY)
				.collectMap(Map.Entry::getKey, Map.Entry::getValue)
				.map(displayNamesBySubject -> threads.stream()
						.map(thread -> CollabThreadSummary.from(thread.thr(),
								thread.subjects().stream().map(displayNamesBySubject::get).toList()))
						.toList());
	}

	/** 참가자 subject까지만 담은 중간 형태 — 표시 이름은 방 여러 개를 다 모은 뒤에 한 번에 붙인다. */
	private record ThreadWithSubjects(Thr thr, List<String> subjects) {
	}

	/**
	* listParticipants 전용 표시 이름 해석. withDisplayNames와 같은 모양(스레드 전체에서 subject를
	* 한 번만 모아 Keycloak Admin API를 호출)이지만, 이 엔드포인트 하나의 결과에서만 모아 해석한다 —
	* summariesFor가 이미 하는 "스레드 여러 개를 가로지르는" 배치와 섞으면 그쪽 최적화가 깨진다
	* (이슈 #23).
	*/
	private Mono<List<ParticipantView>> withParticipantDisplayNames(List<ThreadParticipant> participants) {
		Set<String> subjects = participants.stream().map(ThreadParticipant::subject).collect(Collectors.toSet());
		return Flux.fromIterable(subjects)
				.flatMap(subject -> keycloakAdminClient.displayName(subject)
						.map(displayName -> Map.entry(subject, displayName.orElse(subject))),
						DISPLAY_NAME_LOOKUP_CONCURRENCY)
				.collectMap(Map.Entry::getKey, Map.Entry::getValue)
				.map(displayNamesBySubject -> participants.stream()
						.map(participant -> new ParticipantView(participant.subject(), participant.role(),
								participant.self(), displayNamesBySubject.get(participant.subject()),
								participant.pending()))
						.toList());
	}

	/**
	* 레거시 COLLAB 전용 이력 조회 — 공용 참가자 판정을 쓰는 `ThreadController.listThreadMessages`와
	* 인가만 다르고, 조회·표시 이름 해석은 `ThreadMessageQueryService`를 함께 쓴다(이슈 #219).
	*
	* afterSeq를 주면 그 값보다 큰 seq만 돌려준다(이슈 #190). 방 진입 때는 생략해 전부 받고,
	* 재접속 때는 마지막으로 받은 seq를 넘겨 끊긴 동안의 것만 따라잡는다 — 방송은 그 순간 붙어
	* 있는 연결에만 가고 다시 틀어주지 않으므로, 그 구멍을 메우는 경로가 이것뿐이다.
	*/
	@GetMapping("/api/collab/threads/{threadId}/messages")
	public Flux<MsgItem> listMessages(@PathVariable UUID threadId,
			@RequestParam(name = "afterSeq", required = false) Long afterSeq) {
		return currentActorProvider.currentActor()
				.map(CurrentActor::userId)
				.flatMap(userId -> threadMembershipService.isActiveCollabParticipant(threadId, userId))
				.flatMapMany(participant -> threadMessageQueryService.listMessagesForParticipant(threadId, afterSeq,
						participant));
	}

}
