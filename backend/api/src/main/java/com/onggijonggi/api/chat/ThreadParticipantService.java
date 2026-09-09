package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.keycloak.KeycloakAdminClient;
import com.onggijonggi.common.chat.domain.ThrInv;
import com.onggijonggi.common.chat.domain.ThrInvStatus;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrInvRepository;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadParticipantService.java
 * Description : 협업 Thread 참가자 명단을 바꾸는 도메인 연산(초대·제거·소유권 위임)과 조회.
 *               테이블 매핑은 04·DATA에 있고 여기엔 판정만 둔다 — 방 입장 인가만 다루는
 *               ThreadMembershipService와 같은 자리, 같은 이유다. JPA는 블로킹이라 전부
 *               boundedElastic으로 오프로딩한다.
 *
 *               세 가지를 서로 다른 상태로 답한다. 호출자가 그 방의 참가자가 아니면 방의 존재
 *               자체를 알리지 않으려고 404, 참가자인데 OWNER가 아니면 403, 자격은 되는데 지금
 *               상태로는 할 수 없는 일(소유자의 자진 탈퇴, 위임 경합)은 409다.
 */
@Service
public class ThreadParticipantService {

	/** end_rsn은 고정 토큰만 쓴다 — 자유 입력을 받으면 나중에 집계·감사가 불가능해진다. */
	private static final String SELF_LEAVE = "SELF_LEAVE";

	private static final String OWNER_REVOKED = "OWNER_REVOKED";

	private final ThrMbrRepository thrMbrRepository;
	private final ThrRepository thrRepository;
	private final AppUserRepository appUserRepository;
	private final ThrInvRepository thrInvRepository;
	private final KeycloakAdminClient keycloakAdminClient;

	public ThreadParticipantService(ThrMbrRepository thrMbrRepository, ThrRepository thrRepository,
			AppUserRepository appUserRepository, ThrInvRepository thrInvRepository,
			KeycloakAdminClient keycloakAdminClient) {
		this.thrMbrRepository = thrMbrRepository;
		this.thrRepository = thrRepository;
		this.appUserRepository = appUserRepository;
		this.thrInvRepository = thrInvRepository;
		this.keycloakAdminClient = keycloakAdminClient;
	}

	/** 참가자면 누구나 볼 수 있다 — 자기 방 구성원을 읽는 것뿐이라 OWNER로 좁히지 않는다. */
	public Mono<List<ThreadParticipant>> list(UUID threadId, UUID actorUserId) {
		return Mono.fromCallable(() -> {
					requireActiveParticipant(threadId, actorUserId);
					return activeParticipants(threadId, actorUserId);
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	* invite: OWNER가 subject로 지목한 사람을 MEMBER로 들인다. 이미 ACTIVE면 아무것도 하지 않고
	* 성공으로 답한다 — 초대가 이루려던 상태가 이미 성립해 있기 때문이다.
	* @param inviteeSubject 초대 대상의 Keycloak subject. 조회만 하고 새 계정을 만들지 않는다
	*/
	public Mono<Void> invite(UUID threadId, UUID actorUserId, String inviteeSubject) {
		return Mono.fromCallable(() -> {
					requireOwnerRole(requireActiveParticipant(threadId, actorUserId));
					requireWritableThread(threadId);
					return appUserRepository.findByKeycloakSubj(inviteeSubject).map(AppUser::getId);
				})
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(invitee -> invitee
						.map(userId -> joinNow(threadId, actorUserId, userId))
						.orElseGet(() -> inviteForFirstLogin(threadId, actorUserId, inviteeSubject)));
	}

	/** 이미 로그인한 적 있는 사람은 지금처럼 곧바로 참가시킨다 — 기존 동작 그대로다(이슈 #127 결정). */
	private Mono<Void> joinNow(UUID threadId, UUID actorUserId, UUID inviteeUserId) {
		return Mono.<Void>fromCallable(() -> {
					if (thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, inviteeUserId,
							ThrMbrStatus.ACTIVE)) {
						return null;
					}
					saveIgnoringDuplicate(new ThrMbr(threadId, inviteeUserId, ThrMbrRole.MEMBER, actorUserId));
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	* 아직 로그인한 적 없는 사람은 대기 초대로 남겨, 그 사람의 첫 로그인 때 참가로 전환한다(이슈 #127).
	*
	* Keycloak에 실재하는 계정인지 먼저 확인한다. 확인 없이 받아주면 오타 하나가 영원히 발동하지 않는
	* 초대로 남는다. exists()는 404만 "없음"으로 보고 나머지 오류는 전파하므로, Admin API 장애가
	* 정상 초대를 조용히 거부하는 일은 없다.
	*/
	private Mono<Void> inviteForFirstLogin(UUID threadId, UUID actorUserId, String inviteeSubject) {
		return keycloakAdminClient.exists(inviteeSubject)
				.flatMap(exists -> exists
						? savePendingInvitation(threadId, actorUserId, inviteeSubject)
						: Mono.error(notParticipant()));
	}

	private Mono<Void> savePendingInvitation(UUID threadId, UUID actorUserId, String inviteeSubject) {
		return Mono.<Void>fromCallable(() -> {
					if (thrInvRepository.findByThrIdAndSubjAndStatus(threadId, inviteeSubject,
							ThrInvStatus.PENDING).isPresent()) {
						return null;
					}
					saveIgnoringDuplicate(new ThrInv(threadId, inviteeSubject, actorUserId));
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	* remove: 대상이 호출자 자신이면 자진 탈퇴, 아니면 OWNER의 제거다. 한 엔드포인트로 묶은 대신
	* 여기서 갈라, 끝난 이유(end_rsn)를 상황에 맞게 채운다.
	*
	* 자기 자신인지는 subject 문자열로 비교한다 — target을 내부 UUID로 미리 바꿔서 비교하면,
	* 그 조회가 권한 검사보다 먼저 실행돼 OWNER가 아닌 사람도 "이 subject가 가입한 적 있는지"를
	* 403/404 차이로 알아낼 수 있다(invite·transferOwner는 이미 권한 검사가 먼저였는데 이 메서드만
	* 순서가 반대였다). actorSubject는 호출자가 이미 아는 자기 값이라 이 비교에 DB 조회가 없다.
	* @param actorSubject 호출자 자신의 Keycloak subject(자진 탈퇴 판정용, 조회하지 않는다)
	*/
	public Mono<Void> remove(UUID threadId, UUID actorUserId, String actorSubject, String targetSubject) {
		return Mono.<Void>fromCallable(() -> {
					ThrMbr actor = requireActiveParticipant(threadId, actorUserId);
					if (targetSubject.equals(actorSubject)) {
						leaveSelf(actor);
						return null;
					}
					requireOwnerRole(actor);
					UUID targetUserId = resolveUserId(targetSubject);
					ThrMbr target = thrMbrRepository
							.findByThrIdAndUserIdAndStatus(threadId, targetUserId, ThrMbrStatus.ACTIVE)
							.orElseThrow(ThreadParticipantService::notParticipant);
					target.end(ThrMbrStatus.REVOKED, OWNER_REVOKED);
					thrMbrRepository.save(target);
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	* transferOwner: OWNER 자리를 같은 방의 ACTIVE MEMBER에게 넘긴다. 두 행을 한 UPDATE로 바꾸고
	* 바뀐 행 수를 확인한다 — 2가 아니면 그 사이에 다른 위임·탈퇴가 끝난 것이므로 실패로 답한다.
	* 조용히 넘기면 역할이 반쪽만 바뀐 상태를 아무도 알아채지 못한다.
	*/
	public Mono<Void> transferOwner(UUID threadId, UUID actorUserId, String targetSubject) {
		return Mono.<Void>fromCallable(() -> {
					requireOwnerRole(requireActiveParticipant(threadId, actorUserId));
					UUID targetUserId = resolveUserId(targetSubject);
					thrMbrRepository.findByThrIdAndUserIdAndRoleAndStatus(threadId, targetUserId,
									ThrMbrRole.MEMBER, ThrMbrStatus.ACTIVE)
							.orElseThrow(ThreadParticipantService::notParticipant);
					if (thrMbrRepository.transferOwnership(threadId, actorUserId, targetUserId) != 2) {
						throw stateConflict();
					}
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	* 참가 행을 먼저 읽고 그 사용자들의 subject를 한 번에 붙인다. 내부 app_user.id는 응답에 담지
	* 않는다 — WS 프레임이 쓰는 식별자와 어느 쪽으로 맞출지는 아직 정해지지 않았다.
	*
	* self는 이미 알고 있는 actorUserId와 각 행의 userId를 비교만 하면 되는 순수 판정이라
	* 추가 조회가 없다(이슈 #23) — requireActiveParticipant가 이미 호출자 자신의 행을 확인했으므로
	* 여기서 다시 DB를 보지 않아도 된다.
	*/
	private List<ThreadParticipant> activeParticipants(UUID threadId, UUID actorUserId) {
		List<ThrMbr> members = thrMbrRepository.findByThrIdAndStatus(threadId, ThrMbrStatus.ACTIVE);
		Map<UUID, String> subjectsByUserId = appUserRepository
				.findAllById(members.stream().map(ThrMbr::getUserId).toList())
				.stream()
				.collect(Collectors.toMap(AppUser::getId, AppUser::getKeycloakSubj));
		return members.stream()
				.map(member -> new ThreadParticipant(subjectsByUserId.get(member.getUserId()), member.getRole(),
						member.getUserId().equals(actorUserId)))
				.sorted(Comparator.comparing(ThreadParticipant::role)
						.thenComparing(ThreadParticipant::subject, Comparator.nullsLast(String::compareTo)))
				.toList();
	}

	/** OWNER는 넘길 사람을 정하기 전에는 나갈 수 없다 — 소유자 없는 방을 만들지 않기 위해서다. */
	private void leaveSelf(ThrMbr actor) {
		if (actor.getRole() == ThrMbrRole.OWNER) {
			throw stateConflict();
		}
		actor.end(ThrMbrStatus.LEFT, SELF_LEAVE);
		thrMbrRepository.save(actor);
	}

	/**
	* 같은 사람을 동시에 두 번 초대하면 둘 다 "없다"를 보고 둘 다 저장을 시도할 수 있다. 그때 나는
	* 활성 참가자 부분 유니크 위반은 실패로 답하지 않는다 — 진 쪽이 이루려던 상태를 이긴 쪽이 이미
	* 만들어 놨다. UserIdentityService가 app_user 동시 생성을 다루는 방식과 같다.
	*/
	private void saveIgnoringDuplicate(ThrMbr member) {
		try {
			thrMbrRepository.save(member);
		} catch (DataIntegrityViolationException raced) {
			// 이긴 쪽이 만든 활성 참가 행이 이미 있으므로 그대로 성공으로 둔다.
		}
	}

	/** 대기 중 초대 부분 유니크 위반도 같은 이유로 성공으로 둔다(ux_thr_inv_pending). */
	private void saveIgnoringDuplicate(ThrInv invitation) {
		try {
			thrInvRepository.save(invitation);
		} catch (DataIntegrityViolationException raced) {
			// 이긴 쪽이 만든 대기 초대가 이미 있다.
		}
	}

	private ThrMbr requireActiveParticipant(UUID threadId, UUID userId) {
		return thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, userId, ThrMbrStatus.ACTIVE)
				.orElseThrow(ThreadParticipantService::notParticipant);
	}

	private void requireOwnerRole(ThrMbr actor) {
		if (actor.getRole() != ThrMbrRole.OWNER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
	}

	/** LOCKED·ARCHIVED로 바뀐 방은 새 참가자를 들이지 않는다(#131). */
	private void requireWritableThread(UUID threadId) {
		if (!thrRepository.existsByIdAndStatus(threadId, ThrStatus.ACTIVE)) {
			throw stateConflict();
		}
	}

	private UUID resolveUserId(String subject) {
		return appUserRepository.findByKeycloakSubj(subject)
				.map(AppUser::getId)
				.orElseThrow(ThreadParticipantService::notParticipant);
	}

	/** 없는 방·남의 방·없는 대상을 구분하지 않고 404로 답한다(존재 비노출). */
	private static ResponseStatusException notParticipant() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	private static ResponseStatusException stateConflict() {
		return new ResponseStatusException(HttpStatus.CONFLICT);
	}

}
