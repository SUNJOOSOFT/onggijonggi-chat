package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActor;
import com.onggijonggi.api.auth.CurrentActorProvider;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.MsgRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : CollabThreadController.java
 * Description : 협업 스레드 조회 계약 구현체. 1:1 대화를 다루는 ChatController와 저장 테이블도
 *               소유 모델도 달라 컨트롤러를 나눈다.
 *
 *               방을 만들고 참가자를 초대하는 경로는 여기 없다 — 부를 화면이 아직 없어 이슈 #23과
 *               함께 진행한다(이슈 #22 코멘트).
 */
@RestController
public class CollabThreadController {

	private final CurrentActorProvider currentActorProvider;
	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final MsgRepository msgRepository;
	private final ThreadMembershipService threadMembershipService;

	public CollabThreadController(CurrentActorProvider currentActorProvider, ThrRepository thrRepository,
			ThrMbrRepository thrMbrRepository, MsgRepository msgRepository,
			ThreadMembershipService threadMembershipService) {
		this.currentActorProvider = currentActorProvider;
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.msgRepository = msgRepository;
		this.threadMembershipService = threadMembershipService;
	}

	/** 어떤 방을 내려줄지 고르는 것은 서버 몫이라, 호출자가 참가자인 방만 나간다. */
	@GetMapping("/api/collab/threads")
	public Flux<CollabThreadSummary> listThreads() {
		return currentActorProvider.currentActor()
				.map(CurrentActor::userId)
				.flatMap(userId -> Mono.fromCallable(() -> joinedCollabThreads(userId))
						.subscribeOn(Schedulers.boundedElastic()))
				.flatMapMany(Flux::fromIterable);
	}

	/**
	* 참가 행을 먼저 읽고 그 id로 Thread를 가져온다. thr_mbr에 연관관계를 매핑하지 않아(ChatSess와
	* 같은 이유) 조인 대신 두 번 조회하지만, 두 번째는 findAllById 한 번이라 건수만큼 늘지 않는다.
	*
	* status로 Thread를 거르지 않는다 — ARCHIVED로 만드는 코드 경로가 아직 없어, 지금 거르면 아무
	* 행도 만들지 않는 조건을 미리 박아두는 셈이 된다.
	*/
	private List<CollabThreadSummary> joinedCollabThreads(UUID userId) {
		List<UUID> joinedIds = thrMbrRepository.findByUserIdAndStatus(userId, ThrMbrStatus.ACTIVE)
				.stream()
				.map(ThrMbr::getThrId)
				.toList();
		if (joinedIds.isEmpty()) {
			return List.of();
		}
		return thrRepository.findAllById(joinedIds).stream()
				.filter(thr -> thr.getKind() == ThrKind.COLLAB)
				.sorted(Comparator.comparing(Thr::getCreatedAt).reversed())
				.map(CollabThreadSummary::from)
				.toList();
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
