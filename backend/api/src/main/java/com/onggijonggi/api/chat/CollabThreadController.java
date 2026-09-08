package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : CollabThreadController.java
 * Description : 협업 스레드 조회·참가자 관리 계약 구현체. 1:1 대화를 다루는 ChatController와 저장
 *               테이블도 소유 모델도 달라 컨트롤러를 나눈다.
 *
 *               방을 만드는 경로는 아직 여기 없다 — 부를 화면이 없어 이슈 #23과 함께 진행한다
 *               (이슈 #22 코멘트). 그래서 참가자 관리는 이미 있는 방 위에서만 동작한다.
 *
 *               초대·제거·위임은 대상을 Keycloak subject로 지목한다. 사용자 검색 API가 없어
 *               호출자가 이미 아는 식별자를 그대로 받는 것 말고는 성립하는 계약이 없다(이슈 #20).
 */
@RestController
public class CollabThreadController {

	private final CurrentActorProvider currentActorProvider;
	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final MsgRepository msgRepository;
	private final ThreadMembershipService threadMembershipService;
	private final ThreadParticipantService threadParticipantService;
	private final ThreadLifecycleService threadLifecycleService;
	private final KeycloakAdminClient keycloakAdminClient;
	private final CollabThreadCreationService collabThreadCreationService;

	public CollabThreadController(CurrentActorProvider currentActorProvider, ThrRepository thrRepository,
			ThrMbrRepository thrMbrRepository, MsgRepository msgRepository,
			ThreadMembershipService threadMembershipService,
			ThreadParticipantService threadParticipantService, ThreadLifecycleService threadLifecycleService,
			KeycloakAdminClient keycloakAdminClient, CollabThreadCreationService collabThreadCreationService) {
		this.currentActorProvider = currentActorProvider;
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.msgRepository = msgRepository;
		this.threadMembershipService = threadMembershipService;
		this.threadParticipantService = threadParticipantService;
		this.threadLifecycleService = threadLifecycleService;
		this.keycloakAdminClient = keycloakAdminClient;
		this.collabThreadCreationService = collabThreadCreationService;
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

	/** 인증된 사용자는 제목만으로 방을 만들며, 생성 서비스가 최초 OWNER 참가를 함께 만든다. */
	@PostMapping("/api/collab/threads")
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<CreateCollabThreadResponse> createThread(@Valid @RequestBody CreateCollabThreadRequest request) {
		return actorUserId()
				.flatMap(userId -> Mono.fromCallable(() -> collabThreadCreationService
						.createBlocking(userId, request.title()))
						.subscribeOn(Schedulers.boundedElastic()))
				.map(CreateCollabThreadResponse::new);
	}

	/** 명단은 참가자면 누구나 본다 — 제거·위임 대상을 지목하려면 먼저 누가 있는지 알아야 한다. */
	@GetMapping("/api/collab/threads/{threadId}/participants")
	public Flux<ThreadParticipant> listParticipants(@PathVariable UUID threadId) {
		return actorUserId()
				.flatMap(userId -> threadParticipantService.list(threadId, userId))
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
						.map(displayName -> Map.entry(subject, displayName.orElse(subject))))
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
	* 참가자가 아니거나 존재하지 않는 스레드면 404 — listMessages(ChatController)와 같은 이유로 존재
	* 여부를 노출하지 않는다.
	*/
	@GetMapping("/api/collab/threads/{threadId}/messages")
	public Flux<MsgItem> listMessages(@PathVariable UUID threadId) {
		return currentActorProvider.currentActor()
				.map(CurrentActor::userId)
				.flatMap(userId -> threadMembershipService.isActiveParticipant(threadId, userId))
				.flatMap(participant -> participant
						? Mono.just(true)
						: Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
				.then(Mono.fromCallable(() -> msgRepository.findByThrIdOrderBySeqAsc(threadId))
						.subscribeOn(Schedulers.boundedElastic()))
				.flatMapMany(Flux::fromIterable)
				.map(MsgItem::from);
	}

}
