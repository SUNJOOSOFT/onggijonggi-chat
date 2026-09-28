package com.onggijonggi.api.chat;

import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrIdmKey;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.persistence.ThrIdmKeyRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : CollabThreadCreationService.java
 * Description : 03·CORE 협업방 생성의 원자 단위다. thr와 최초 ACTIVE OWNER 참가자 행을 함께
 *               저장해 방만 있고 입장 권한이 없는 상태를 만들지 않는다.
 *
 *               idempotencyKey가 있으면(이슈 #149) 같은 사용자·같은 키의 재시도에 새 thr을 또
 *               만들지 않고 최초 결과를 그대로 돌려준다. 같은 키에 다른 title이 오면 거절한다 —
 *               title 자체가 key는 아니지만, 확인 없이 통과시키면 키 재사용 실수를 못 잡는다.
 *               키가 없는 호출(idempotencyKey == null)은 이 계약을 요구하지 않은 것으로 보고
 *               기존과 동일하게 매번 새로 만든다. TTL이 지난 키는 새 요청으로 취급하기 전에
 *               옛 행을 즉시 삭제한다(이슈 #240) — 그대로 두면 새 키 저장이 유니크 인덱스와
 *               충돌한다.
 *
 *               workspace는 방을 둘 워크스페이스다. 볼 수 있는지는 호출부(ThreadWorkspaceService)가 이미 판정했다.
 *               워크스페이스 트리가 없는 배포에서는 null이고 방은 워크스페이스 없이 만들어진다. 같은 키에 다른
 *               워크스페이스가 오면 title과 같은 이유로 거절한다.
 */
@Service
public class CollabThreadCreationService {

	/** 이 기간이 지난 키는 재사용하지 않는다 — 별도 정리 배치 없이 조회 시점에 "새 요청"으로 본다. */
	private static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);

	private final ThrRepository thrRepository;
	private final ThrMbrRepository thrMbrRepository;
	private final ThrIdmKeyRepository thrIdmKeyRepository;

	public CollabThreadCreationService(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository,
			ThrIdmKeyRepository thrIdmKeyRepository) {
		this.thrRepository = thrRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.thrIdmKeyRepository = thrIdmKeyRepository;
	}

	/**
	* 호출자는 WebFlux 이벤트 루프 밖에서 이 blocking JPA 트랜잭션을 실행한다. 같은 idempotencyKey로
	* 동시에 두 요청이 들어오면 thr_idm_key의 유니크 인덱스가 뒤늦은 쪽의 저장을 막고, 이 트랜잭션
	* 전체가 롤백돼 그쪽의 thr·thr_mbr도 함께 사라진다(방만 있고 키 기록은 없는 상태를 만들지
	* 않기 위해 같은 트랜잭션에 묶었다) — 진 쪽의 재시도는 호출부(CollabThreadController) 몫이다.
	*/
	@Transactional
	public UUID createBlocking(UUID actorUserId, String title, String idempotencyKey, WorkspaceNode workspace) {
		UUID workspaceId = workspace == null ? null : workspace.getId();
		if (idempotencyKey != null) {
			Optional<ThrIdmKey> existing = thrIdmKeyRepository.findByUserIdAndKey(actorUserId, idempotencyKey);
			if (existing.isPresent()) {
				if (!isExpired(existing.get())) {
					return sameThreadOrConflict(existing.get(), title, workspaceId);
				}
				thrIdmKeyRepository.deleteImmediatelyByUserIdAndKey(actorUserId, idempotencyKey);
			}
		}
		Thr thread = Thr.collab(actorUserId, title);
		if (workspace != null) {
			thread.placeIn(workspace.getTenantId(), workspace.getId());
		}
		thrRepository.save(thread);
		thrMbrRepository.save(new ThrMbr(thread.getId(), actorUserId, ThrMbrRole.OWNER, actorUserId));
		if (idempotencyKey != null) {
			thrIdmKeyRepository.save(new ThrIdmKey(actorUserId, idempotencyKey, title, thread.getId()));
		}
		return thread.getId();
	}

	private boolean isExpired(ThrIdmKey key) {
		return key.getCreatedAt().isBefore(Instant.now().minus(IDEMPOTENCY_KEY_TTL));
	}

	private UUID sameThreadOrConflict(ThrIdmKey existing, String title, UUID workspaceId) {
		if (!existing.getTitle().equals(title)) {
			throw new IdempotencyKeyConflictException();
		}
		UUID existingWorkspaceId = thrRepository.findById(existing.getThrId()).map(Thr::getWorkspaceNodeId).orElse(null);
		if (!Objects.equals(existingWorkspaceId, workspaceId)) {
			throw new IdempotencyKeyConflictException();
		}
		return existing.getThrId();
	}

}
