package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadApprovalController.java
 * Description : 협업방 승인 요청 API(#407). 모든 DB 작업은 boundedElastic에서 실행한다.
 */
@RestController
public class ThreadApprovalController {
	private final CurrentActorProvider actors;
	private final ThreadApprovalService approvals;

	public ThreadApprovalController(CurrentActorProvider actors, ThreadApprovalService approvals) {
		this.actors = actors;
		this.approvals = approvals;
	}

	/** id는 클라이언트가 만든 요청 식별자다 — 같은 id로 다시 보내면 요청이 하나만 남는다. */
	public record CreateRequest(@NotNull UUID id, @NotNull UUID messageId, @NotBlank String targetSubject) { }

	public record DecisionRequest(@NotNull Boolean approved, @Size(max = ThreadApprovalService.MAX_REASON_LENGTH) String reason) { }

	@GetMapping("/api/threads/{threadId}/approvals")
	public Mono<List<ThreadApprovalView>> list(@PathVariable UUID threadId) {
		return actors.currentActor().flatMap(actor -> Mono.fromCallable(() -> approvals.list(threadId, actor))
				.subscribeOn(Schedulers.boundedElastic()));
	}

	@PostMapping("/api/threads/{threadId}/approvals")
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<ThreadApprovalView> request(@PathVariable UUID threadId, @Valid @RequestBody CreateRequest request) {
		return actors.currentActor().flatMap(actor -> Mono.fromCallable(() -> approvals.request(threadId, request.id(),
				request.messageId(), request.targetSubject(), actor)).subscribeOn(Schedulers.boundedElastic()));
	}

	@PutMapping("/api/threads/{threadId}/approvals/{approvalId}/decision")
	public Mono<ThreadApprovalView> decide(@PathVariable UUID threadId, @PathVariable UUID approvalId,
			@Valid @RequestBody DecisionRequest request) {
		return actors.currentActor().flatMap(actor -> Mono.fromCallable(() -> approvals.decide(threadId, approvalId,
				request.approved(), request.reason(), actor)).subscribeOn(Schedulers.boundedElastic()));
	}
}
