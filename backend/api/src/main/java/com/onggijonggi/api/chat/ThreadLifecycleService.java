package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadLifecycleService.java
 * Description : 협업 Thread의 잠금·보관·삭제(#131). OWNER만 할 수 있고, ThreadParticipantService와
 *               같은 상태 코드 관례를 따른다 — 참가자가 아니면 404, 참가자인데 OWNER가 아니면 403,
 *               지금 상태로는 할 수 없는 전이(이미 ARCHIVED인 방을 다시 잠그는 등)는 409.
 *
 *               LOCKED는 되돌릴 수 있는 잠금(쓰기만 막는다)이고 ARCHIVED는 최종 상태다. ACTIVE →
 *               LOCKED → ARCHIVED 순서로만 전이하며, LOCKED를 건너뛰고 ACTIVE → ARCHIVED로 바로
 *               가는 것도 허용한다 — 둘 다 V8__thread.sql의 CHECK 제약(상태별 시각 컬럼 조합)을
 *               만족한다.
 */
@Service
public class ThreadLifecycleService {

	private final ThrRepository thrRepository;

	private final ThrMbrRepository thrMbrRepository;

	public ThreadLifecycleService(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository) {
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
	}

	/** ACTIVE인 방만 잠글 수 있다. 이미 LOCKED·ARCHIVED면 409. */
	public Mono<Void> lock(UUID threadId, UUID actorUserId) {
		return Mono.<Void>fromCallable(() -> {
					Thr thr = requireOwnerActor(threadId, actorUserId);
					if (thr.getStatus() != ThrStatus.ACTIVE) {
						throw stateConflict();
					}
					thr.lock();
					thrRepository.save(thr);
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** ACTIVE·LOCKED 둘 다에서 보관할 수 있다. 이미 ARCHIVED면 409(멱등하게 조용히 넘기지 않는다). */
	public Mono<Void> archive(UUID threadId, UUID actorUserId) {
		return Mono.<Void>fromCallable(() -> {
					Thr thr = requireOwnerActor(threadId, actorUserId);
					if (thr.getStatus() == ThrStatus.ARCHIVED) {
						throw stateConflict();
					}
					thr.archive();
					thrRepository.save(thr);
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	* 상태와 무관하게 지울 수 있다. thr_mbr·msg는 FK on delete cascade(V10__thread_participant.sql,
	* V11__message.sql)로 함께 지워진다 — 1:1 채팅의 chat_sess 삭제와 같은 정책이다.
	*/
	public Mono<Void> delete(UUID threadId, UUID actorUserId) {
		return Mono.<Void>fromCallable(() -> {
					Thr thr = requireOwnerActor(threadId, actorUserId);
					thrRepository.delete(thr);
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	* actor가 그 방의 ACTIVE OWNER인지 확인하고 Thr을 반환한다. thr_mbr 존재로 방 존재를 대신
	* 확인하는 것은 ThreadParticipantService와 같은 이유다 — 참가자가 아니면 방이 있는지조차
	* 알리지 않는다.
	*/
	private Thr requireOwnerActor(UUID threadId, UUID actorUserId) {
		var actor = thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, actorUserId, ThrMbrStatus.ACTIVE)
				.orElseThrow(ThreadLifecycleService::notParticipant);
		if (actor.getRole() != ThrMbrRole.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
		return thrRepository.findById(threadId).orElseThrow(ThreadLifecycleService::notParticipant);
	}

	private static ResponseStatusException notParticipant() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	private static ResponseStatusException stateConflict() {
		return new ResponseStatusException(HttpStatus.CONFLICT);
	}

}
