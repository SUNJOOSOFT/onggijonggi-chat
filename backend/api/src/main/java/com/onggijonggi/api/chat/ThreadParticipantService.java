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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(ThreadParticipantService.class);

	/** end_rsn은 고정 토큰만 쓴다 — 자유 입력을 받으면 나중에 집계·감사가 불가능해진다. */
	private static final String SELF_LEAVE = "SELF_LEAVE";

	private static final String OWNER_REVOKED = "OWNER_REVOKED";

	private final ThrMbrRepository thrMbrRepository;
	private final ThrRepository thrRepository;
	private final AppUserRepository appUserRepository;
	private final RoomSessionRegistry roomSessionRegistry;
	private final KeycloakAdminClient keycloakAdminClient;
	private final ThrInvRepository thrInvRepository;
	private final InvitationAcceptanceService invitationAcceptanceService;

	public ThreadParticipantService(ThrMbrRepository thrMbrRepository, ThrRepository thrRepository,
			AppUserRepository appUserRepository, RoomSessionRegistry roomSessionRegistry,
			KeycloakAdminClient keycloakAdminClient, ThrInvRepository thrInvRepository,
			InvitationAcceptanceService invitationAcceptanceService) {
		this.thrMbrRepository = thrMbrRepository;
		this.thrRepository = thrRepository;
		this.appUserRepository = appUserRepository;
		this.roomSessionRegistry = roomSessionRegistry;
		this.keycloakAdminClient = keycloakAdminClient;
		this.thrInvRepository = thrInvRepository;
		this.invitationAcceptanceService = invitationAcceptanceService;
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
	* 성공으로 답한다 — 초대가 이루려던 상태가 이미 성립해 있기 때문이다. 이미 ACTIVE였던 경우는
	* 명단이 실제로 바뀌지 않았으므로 통지하지 않는다(이슈 #129).
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
						.map(userId -> joinNow(threadId, actorUserId, userId, inviteeSubject))
						.orElseGet(() -> inviteForFirstLogin(threadId, actorUserId, inviteeSubject)));
	}

	/** 이미 로그인한 적 있는 사람은 지금처럼 곧바로 참가시킨다 — 기존 동작 그대로다(이슈 #127 결정). */
	private Mono<Void> joinNow(UUID threadId, UUID actorUserId, UUID inviteeUserId, String inviteeSubject) {
		return Mono.fromCallable(() -> {
					if (thrMbrRepository.existsByThrIdAndUserIdAndStatus(threadId, inviteeUserId,
							ThrMbrStatus.ACTIVE)) {
						return false;
					}
					saveIgnoringDuplicate(new ThrMbr(threadId, inviteeUserId, ThrMbrRole.MEMBER, actorUserId));
					return true;
				})
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(changed -> changed
						? notifyParticipantChanged(threadId, ParticipantChangeAction.INVITED, inviteeSubject)
						: Mono.<Void>empty());
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
							ThrInvStatus.PENDING).isEmpty()) {
						saveIgnoringDuplicate(new ThrInv(threadId, inviteeSubject, actorUserId));
					}
					acceptIfAlreadyLoggedIn(threadId, inviteeSubject);
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/**
	* 초대 행을 남긴 <b>뒤에</b> 대상의 app_user 행을 한 번 더 본다. 위쪽 findByKeycloakSubj가
	* 미스였는데 그 사이에 대상이 첫 로그인을 마쳤을 수 있고, 그 창은 좁지 않다 — 두 지점 사이에
	* exists()의 Keycloak HTTP 왕복이 그대로 들어간다.
	*
	* 전환은 app_user 행을 새로 만든 순간에만 도는데, 그쪽이 초대 테이블을 훑을 때 우리 초대 행이
	* 아직 커밋되지 않았다면 그쪽도 우리도 전환하지 않는다 — 전환 시도는 첫 로그인 한 번뿐이라
	* 그 초대는 영구히 대기한다. 각자 자기 쓰기를 커밋한 뒤에 상대를 보므로, 이 재확인이 있으면
	* 적어도 한쪽은 상대를 본다.
	*/
	private void acceptIfAlreadyLoggedIn(UUID threadId, String inviteeSubject) {
		Optional<UUID> inviteeUserId = appUserRepository.findByKeycloakSubj(inviteeSubject)
				.map(AppUser::getId);
		if (inviteeUserId.isEmpty()) {
			return;
		}
		thrInvRepository.findByThrIdAndSubjAndStatus(threadId, inviteeSubject, ThrInvStatus.PENDING)
				.ifPresent(invitation -> invitationAcceptanceService.acceptOneBlocking(
						invitation.getId(), inviteeUserId.get()));
	}

	/**
	* remove: 대상이 호출자 자신이면 자진 탈퇴, 아니면 OWNER의 제거다. 한 엔드포인트로 묶은 대신
	* 여기서 갈라, 끝난 이유(end_rsn)를 상황에 맞게 채운다.
	*
	* 자기 자신인지는 subject 문자열로 비교한다 — target을 내부 UUID로 미리 바꿔서 비교하면,
	* 그 조회가 권한 검사보다 먼저 실행돼 OWNER가 아닌 사람도 "이 subject가 가입한 적 있는지"를
	* 403/404 차이로 알아낼 수 있다(invite·transferOwner는 이미 권한 검사가 먼저였는데 이 메서드만
	* 순서가 반대였다). actorSubject는 호출자가 이미 아는 자기 값이라 이 비교에 DB 조회가 없다.
	*
	* DB 반영·통지 뒤 evict를 부르는 것은 자진 탈퇴·OWNER 제거 둘 다에 적용한다 — 어느 쪽이든
	* targetSubject의 ACTIVE 참가 행이 끝났다는 사실은 같고, 다른 탭으로 이미 연결돼 있으면 그
	* 연결도 같이 끊어야 한다(이슈 #135). evict가 아무 연결도 못 찾아도(애초에 접속한 적 없음)
	* 조용히 false만 돌려주므로 별도 분기가 필요 없다.
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
				.subscribeOn(Schedulers.boundedElastic())
				.then(Mono.defer(
						() -> notifyParticipantChanged(threadId, ParticipantChangeAction.REMOVED, targetSubject)))
				.doOnSuccess(ignored -> roomSessionRegistry.evict(threadId, targetSubject));
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
				.subscribeOn(Schedulers.boundedElastic())
				.then(Mono.defer(() -> notifyParticipantChanged(threadId, ParticipantChangeAction.OWNER_TRANSFERRED,
						targetSubject)));
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

	/**
	* 변경 대상이 지금 접속 중이 아닐 수 있어(초대 시나리오, 이슈 #129 본문) presence처럼 연결이
	* 들고 있는 JWT claim을 재사용할 수 없다 — ParticipantView와 같은 패턴으로 KeycloakAdminClient를
	* 조회해 displayName을 붙인다. 아무도 그 방을 듣고 있지 않으면 RoomSessionRegistry가 조용히
	* 버린다.
	*
	* 호출부는 반드시 {@code Mono.defer(() -> notifyParticipantChanged(...))}로 감싼다 — 그냥
	* {@code .then(notifyParticipantChanged(...))}로 쓰면 Java가 인자를 즉시 평가해 앞선
	* fromCallable이 아직 구독도 되기 전에(즉 remove·transferOwner가 권한 검사·상태 검증을 통과
	* 하기도 전에) keycloakAdminClient를 호출해 버린다.
	*
	* Keycloak 조회 실패는 삼키고 로그만 남긴다 — 이 시점엔 참가자 변경이 이미 DB에 커밋돼 있어,
	* 통지 실패로 호출자에게 5xx를 돌려주면 실제로는 성공한 초대·제거·위임이 실패로 보인다
	* (PersistingChatStreamService의 "저장 실패는 채팅을 막지 않는다"와 같은 원칙).
	*/
	private Mono<Void> notifyParticipantChanged(UUID threadId, ParticipantChangeAction action, String subject) {
		return keycloakAdminClient.displayName(subject)
				.map(displayName -> displayName.orElse(subject))
				.doOnNext(displayName -> roomSessionRegistry.notifyIfListening(threadId,
						new ParticipantChangedFrame(threadId, action, subject, displayName)))
				.onErrorResume(error -> {
					log.error("참여자 변경 통지 실패 threadId={} action={}", threadId, action, error);
					return Mono.empty();
				})
				.then();
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
