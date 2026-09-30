package com.onggijonggi.api.chat;

import com.onggijonggi.api.authz.RbacProperties;
import com.onggijonggi.api.authz.WorkspaceAuthorizer;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadMembershipService.java
 * Description : Thread 참가 여부·쓰기 가능 여부 조회. 테이블 매핑은 04·DATA 계층에 있고 이 판정만
 *               03·CORE에 둔다 — 참가자 판정 경계는 이슈 #22를, LOCKED·ARCHIVED가 쓰기를 막는
 *               경계는 이슈 #131을 확인한다. JPA는 블로킹이라 boundedElastic으로 오프로딩한다.
 *
 *               협업방은 참가자라도 그 방의 워크스페이스를 볼 수 있어야 들어간다({@link #canEnterWorkspace}).
 *               참가 기록은 건드리지 않고 판정만 막으므로, 워크스페이스 권한이 돌아오면 그대로 다시 들어간다.
 */
@Service
public class ThreadMembershipService {

	private final ThrMbrRepository thrMbrRepository;

	private final ThrRepository thrRepository;

	private final WorkspaceAuthorizer workspaceAuthorizer;

	private final RbacProperties rbacProperties;
	private final AppUserRepository appUsers;

	public ThreadMembershipService(ThrMbrRepository thrMbrRepository, ThrRepository thrRepository,
			WorkspaceAuthorizer workspaceAuthorizer, RbacProperties rbacProperties, AppUserRepository appUsers) {
		this.thrMbrRepository = thrMbrRepository;
		this.thrRepository = thrRepository;
		this.workspaceAuthorizer = workspaceAuthorizer;
		this.rbacProperties = rbacProperties;
		this.appUsers = appUsers;
	}

	/**
	* 이 사람이 그 방의 워크스페이스를 볼 수 있나. 참가 여부와 따로 묻고 호출부가 둘 다 확인한다.
	* 1:1(DIRECT)도 같다(#299) — 1:1은 요청자 Tenant의 common에 있고, common도 배정이 있어야 본다. 그래서 배정이 없거나
	* Tenant·팀이 비활성이 되면 자기 1:1도 열 수 없다. 워크스페이스가 없는 방(절체 전 방)은 판정이 켜져 있으면 거부한다
	* — 판정은 절체로 모든 방에 워크스페이스를 채운 뒤에만 켜고, 옛 방을 위한 우회는 두지 않는다.
	* 방이 없으면 참이다 — 없는 방은 참가 판정이 이미 거부한다. 판정이 꺼져 있으면 DB를 보지 않고 참이다.
	*/
	public Mono<Boolean> canEnterWorkspace(UUID threadId, String subject) {
		return Mono.fromCallable(() -> appUsers.findByKeycloakSubj(subject)
				.map(user -> user.getStatus() == AppUserStatus.ACTIVE).orElse(true))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(active -> {
					if (!active) return Mono.just(false);
					if (!rbacProperties.isEnforce()) return Mono.just(true);
					return Mono.fromCallable(() -> thrRepository.findById(threadId))
							.subscribeOn(Schedulers.boundedElastic())
							.flatMap(thread -> thread.isEmpty()
									? Mono.just(true)
									: workspaceAuthorizer.canView(subject, thread.get().getWorkspaceNodeId()));
				});
	}

	/**
	* 1:1 구독·발화 권한. 소유자 계약(소유자 열과 ACTIVE OWNER 행)에 더해, 판정이 켜져 있으면 그 방의 common을 볼 수
	* 있어야 한다(#299). 둘은 따로 지킨다 — 소유자가 아니면 워크스페이스를 볼 수 있어도 남의 1:1은 열지 못한다.
	*/
	public Mono<Boolean> canUseDirect(UUID threadId, UUID userId, String subject) {
		return isActiveDirectOwner(threadId, userId)
				.flatMap(owner -> owner ? canEnterWorkspace(threadId, subject) : Mono.just(false));
	}

	/** 끝난 참가 행이 같은 (thr_id, user_id)로 남아 있어서, 활성 행만 참가로 센다. */
	public Mono<Boolean> isActiveParticipant(UUID threadId, UUID userId) {
		return Mono.fromCallable(() ->
						thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, userId, ThrMbrStatus.ACTIVE))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** 기존 협업 이력 별칭만 쓰는 판정이다. 일반 참가 판정은 DIRECT WS 전환을 위해 제한하지 않는다. */
	public Mono<Boolean> isActiveCollabParticipant(UUID threadId, UUID userId) {
		return Mono.fromCallable(() ->
						thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, userId, ThrMbrStatus.ACTIVE)
								&& thrRepository.existsByIdAndKind(threadId, ThrKind.COLLAB))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** DIRECT는 소유자 열과 ACTIVE OWNER 멤버십이 모두 일치해야 한다. */
	public Mono<Boolean> isActiveDirectOwner(UUID threadId, UUID userId) {
		return Mono.fromCallable(() -> thrRepository.findById(threadId)
				.filter(thread -> thread.getKind() == ThrKind.DIRECT
						&& userId.equals(thread.getDrcOwnUserId()))
				.flatMap(thread -> thrMbrRepository.findByThrIdAndUserIdAndRoleAndStatus(threadId, userId,
						ThrMbrRole.OWNER, ThrMbrStatus.ACTIVE))
				.isPresent())
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** LOCKED·ARCHIVED로 바뀐 방은 ACTIVE가 아니므로 새 메시지·초대 같은 쓰기 작업을 막는다(#131). */
	public Mono<Boolean> isOpenForWriting(UUID threadId) {
		return Mono.fromCallable(() -> thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE))
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** dispatcher가 DIRECT·COLLAB 분기를 결정하는 데 쓴다(이슈 #162). 방이 없으면 empty. */
	public Mono<Optional<ThrKind>> kindOf(UUID threadId) {
		return Mono.fromCallable(() -> thrRepository.findById(threadId).map(thr -> thr.getKind()))
				.subscribeOn(Schedulers.boundedElastic());
	}

}
