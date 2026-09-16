package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ChatController.java
 * Description : 개인 채팅의 레거시 세션 별칭이다(이슈 #164로 HTTP 스트림은 제거됨 — 1:1 실시간은
 *               WS로 통일됐다). 외부 URL·ChatSessSummary/ChatMsgItem 모양은 유지하되, 읽기·
 *               이름변경·삭제의 정본은 DIRECT thr/msg다. Thread 종류와 소유권은 항상 404로 감춘다.
 *               `/api/chat/sessions` 목록 조회를 대체할 `/api/threads` 목록 엔드포인트가 아직
 *               없어 이 별칭은 당장은 계속 남는다.
 */
@RestController
public class ChatController {

	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final MsgRepository msgRepository;
	private final CurrentActorProvider currentActorProvider;

	public ChatController(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository, MsgRepository msgRepository,
			CurrentActorProvider currentActorProvider) {
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.msgRepository = msgRepository;
		this.currentActorProvider = currentActorProvider;
	}

	@GetMapping("/api/chat/sessions")
	public Flux<ChatSessSummary> listSessions() {
		return currentUserId()
				.flatMap(userId -> Mono
						.fromCallable(() -> thrRepository.findByKindAndDrcOwnUserIdOrderByCreatedAtDesc(ThrKind.DIRECT, userId))
						.subscribeOn(Schedulers.boundedElastic()))
				.flatMapMany(Flux::fromIterable)
				.map(ChatSessSummary::from);
	}

	@GetMapping("/api/chat/sessions/{sessionId}/messages")
	public Flux<ChatMsgItem> listMessages(@PathVariable UUID sessionId) {
		return currentUserId()
				.flatMap(userId -> findOwnedDirectThreadOrNotFound(sessionId, userId))
				.flatMap(thread -> Mono
						.fromCallable(() -> msgRepository.findByThrIdOrderBySeqAsc(thread.getId()))
						.subscribeOn(Schedulers.boundedElastic()))
				.flatMapMany(Flux::fromIterable)
				.map(ChatMsgItem::from);
	}

	/** DIRECT Thread 삭제는 thr_mbr/msg FK cascade로 이력까지 함께 제거한다. */
	@DeleteMapping("/api/chat/sessions/{sessionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> deleteSession(@PathVariable UUID sessionId) {
		return currentUserId()
				.flatMap(userId -> findOwnedDirectThreadOrNotFound(sessionId, userId))
				.flatMap(thread -> Mono.fromRunnable(() -> thrRepository.delete(thread))
						.subscribeOn(Schedulers.boundedElastic()))
				.then();
	}

	@PatchMapping("/api/chat/sessions/{sessionId}")
	public Mono<ChatSessSummary> renameSession(@PathVariable UUID sessionId,
			@Valid @RequestBody RenameSessionRequest request) {
		return currentUserId()
				.flatMap(userId -> renameOwnedDirectThread(sessionId, userId, request.title()))
				.map(ChatSessSummary::from);
	}

	/** 새 공용 lifecycle 경로는 DIRECT만 허용하고 성공 시 본문 없이 204를 반환한다. */
	@PatchMapping("/api/threads/{threadId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> renameThread(@PathVariable UUID threadId, @Valid @RequestBody RenameSessionRequest request) {
		return currentUserId()
				.flatMap(userId -> renameOwnedDirectThread(threadId, userId, request.title()))
				.then();
	}

	@DeleteMapping("/api/threads/{threadId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> deleteThread(@PathVariable UUID threadId) {
		return currentUserId()
				.flatMap(userId -> findOwnedDirectThreadOrNotFound(threadId, userId))
				.flatMap(thread -> Mono.fromRunnable(() -> thrRepository.delete(thread))
						.subscribeOn(Schedulers.boundedElastic()))
				.then();
	}

	private Mono<Thr> renameOwnedDirectThread(UUID threadId, UUID userId, String title) {
		return findOwnedDirectThreadOrNotFound(threadId, userId)
				.flatMap(thread -> Mono.fromCallable(() -> {
					thread.rename(title.trim());
					return thrRepository.save(thread);
				}).subscribeOn(Schedulers.boundedElastic()));
	}

	/** DIRECT가 아니거나 소유자 ACTIVE OWNER가 아니면 404로 통일해 종류·존재를 숨긴다. */
	private Mono<Thr> findOwnedDirectThreadOrNotFound(UUID sessionId, UUID userId) {
		return Mono.fromCallable(() -> thrRepository.findById(sessionId)
					.filter(thread -> thread.getKind() == ThrKind.DIRECT && userId.equals(thread.getDrcOwnUserId()))
					.filter(thread -> thrMbrRepository.existsByThrIdAndUserIdAndStatus(sessionId, userId,
							ThrMbrStatus.ACTIVE)))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(Mono::justOrEmpty)
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
	}

	private Mono<UUID> currentUserId() {
		return currentActorProvider.currentActor().map(CurrentActor::userId);
	}

}
