package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActorProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Class Name : ThreadController.java
 * Description : DIRECT·COLLAB 공용 이력 조회 계약 구현체(이슈 #159, #219). 이전에는
 *               `CollabThreadController`가 이 엔드포인트를 같이 들고 있어 클래스 이름만 보고
 *               COLLAB 전용이라고 오해하기 쉬웠다 — `ThreadMessageDispatcher`·
 *               `ThreadWebSocketHandler`와 같은 이유로 이 컨트롤러도 "Thread*"로 따로 둔다.
 *               조회·표시 이름 해석 로직 자체는 레거시 `CollabThreadController.listMessages`와
 *               `ThreadMessageQueryService`를 함께 쓴다 — 인가 방식만 다르다(공용 참가자 vs
 *               COLLAB 전용 참가자).
 */
@RestController
public class ThreadController {

	private final CurrentActorProvider currentActorProvider;
	private final ThreadMembershipService threadMembershipService;
	private final ThreadMessageQueryService threadMessageQueryService;

	public ThreadController(CurrentActorProvider currentActorProvider,
			ThreadMembershipService threadMembershipService, ThreadMessageQueryService threadMessageQueryService) {
		this.currentActorProvider = currentActorProvider;
		this.threadMembershipService = threadMembershipService;
		this.threadMessageQueryService = threadMessageQueryService;
	}

	/**
	* DIRECT·COLLAB ACTIVE 참가자가 공통 msg 이력을 raw athKind 계약으로 읽는 공용 경로다. 협업방은 그 방의
	* 워크스페이스도 볼 수 있어야 한다 — 못 보면 참가자가 아닌 것과 똑같이 404다.
	*/
	@GetMapping("/api/threads/{threadId}/messages")
	public Flux<MsgItem> listThreadMessages(@PathVariable UUID threadId,
			@RequestParam(name = "afterSeq", required = false) Long afterSeq) {
		return currentActorProvider.currentActor()
				.flatMap(actor -> threadMembershipService.isActiveParticipant(threadId, actor.userId())
						.zipWith(threadMembershipService.canEnterWorkspace(threadId, actor.subject()),
								(participant, inWorkspace) -> participant && inWorkspace))
				.flatMapMany(participant -> threadMessageQueryService.listMessagesForParticipant(threadId, afterSeq,
						participant));
	}

}
