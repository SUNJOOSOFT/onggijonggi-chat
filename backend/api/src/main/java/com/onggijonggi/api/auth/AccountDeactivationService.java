package com.onggijonggi.api.auth;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.domain.ThrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import com.onggijonggi.common.user.AppUserStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : AccountDeactivationService.java
 * Description : 계정 비활성화·재활성화(#132). V9__app_user_status.sql이 미뤄둔 "소유 대화 정리·참여
 *               회수 순서"를 여기서 다룬다. 참여 정리는 #20의 end_rsn 관례를 따르되 새 토큰
 *               (ACCOUNT_INACTIVE)을 쓴다 — SELF_LEAVE·OWNER_REVOKED 어느 쪽도 실제 사유와 맞지
 *               않기 때문이다.
 *
 *               OWNER인 방은 #20의 "위임 전엔 나갈 수 없다" 규칙을 그대로 따른다 — 다른 ACTIVE
 *               MEMBER가 있으면 그 사람에게 위임하고, 없으면(OWNER 혼자) #131의 Thr.archive()로
 *               방을 보관한다. HTTP 엔드포인트는 두지 않는다 — 이 저장소는 어드민 UI를 만들지
 *               않기로 확정돼 있어, 관리 도구 연동은 이 서비스가 만들어질 자리만 남기고 후속으로
 *               미룬다.
 */
@Service
public class AccountDeactivationService {

	/** end_rsn 고정 토큰 — ThreadParticipantService의 SELF_LEAVE·OWNER_REVOKED와 같은 자리다. */
	private static final String ACCOUNT_INACTIVE = "ACCOUNT_INACTIVE";

	private final AppUserRepository appUserRepository;

	private final ThrMbrRepository thrMbrRepository;

	private final ThrRepository thrRepository;

	public AccountDeactivationService(AppUserRepository appUserRepository, ThrMbrRepository thrMbrRepository,
			ThrRepository thrRepository) {
		this.appUserRepository = appUserRepository;
		this.thrMbrRepository = thrMbrRepository;
		this.thrRepository = thrRepository;
	}

	/** ACTIVE 계정만 비활성화할 수 있다. 이미 INACTIVE면 409. */
	public Mono<Void> deactivate(UUID userId) {
		return Mono.<Void>fromCallable(() -> {
					AppUser user = requireActiveUser(userId);
					thrMbrRepository.findByUserIdAndStatus(userId, ThrMbrStatus.ACTIVE)
							.forEach(this::endParticipation);
					user.deactivate();
					appUserRepository.save(user);
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	/** INACTIVE 계정만 재활성화할 수 있다. 끝난 참여는 복구하지 않는다 — 다시 필요하면 재초대로 들어온다. */
	public Mono<Void> reactivate(UUID userId) {
		return Mono.<Void>fromCallable(() -> {
					AppUser user = appUserRepository.findById(userId)
							.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
					if (user.getStatus() != AppUserStatus.INACTIVE) {
						throw stateConflict();
					}
					user.reactivate();
					appUserRepository.save(user);
					return null;
				})
				.subscribeOn(Schedulers.boundedElastic());
	}

	private void endParticipation(ThrMbr membership) {
		ThrMbr toEnd = membership.getRole() == ThrMbrRole.OWNER ? handleOwnedThread(membership) : membership;
		toEnd.end(ThrMbrStatus.REVOKED, ACCOUNT_INACTIVE);
		thrMbrRepository.save(toEnd);
	}

	/**
	* 위임 대상이 있으면 넘기고, 없으면(OWNER 혼자 남은 방) 방을 보관한다 — 이미 보관된 방이면
	* 다시 archive()를 부르지 않는다(archived_at을 불필요하게 갱신하지 않기 위해서).
	*
	* transferOwnership()은 원자적 UPDATE라 호출 후 ownerMembership은 role이 실제로 바뀐 상태를
	* 반영하지 못한다(detached 상태로 role=OWNER를 그대로 들고 있다). 그 객체에 그대로 end()해
	* 저장하면 merge가 role을 OWNER로 되돌려 버려서, 위임 뒤 다시 조회해 종료할 대상을 새로
	* 받아온다 — 안 그러면 종료된 참가 기록의 role이 위임 사실과 어긋나게 남는다.
	*/
	private ThrMbr handleOwnedThread(ThrMbr ownerMembership) {
		UUID threadId = ownerMembership.getThrId();
		return thrMbrRepository.findFirstByThrIdAndRoleAndStatus(threadId, ThrMbrRole.MEMBER, ThrMbrStatus.ACTIVE)
				.map(successor -> {
					int updated = thrMbrRepository.transferOwnership(threadId, ownerMembership.getUserId(),
							successor.getUserId());
					if (updated != 2) {
						throw stateConflict();
					}
					return thrMbrRepository
							.findByThrIdAndUserIdAndStatus(threadId, ownerMembership.getUserId(),
									ThrMbrStatus.ACTIVE)
							.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
				})
				.orElseGet(() -> {
					Thr thr = thrRepository.findById(threadId)
							.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
					if (thr.getStatus() != ThrStatus.ARCHIVED) {
						thr.archive();
						thrRepository.save(thr);
					}
					return ownerMembership;
				});
	}

	private AppUser requireActiveUser(UUID userId) {
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (user.getStatus() != AppUserStatus.ACTIVE) {
			throw stateConflict();
		}
		return user;
	}

	private static ResponseStatusException stateConflict() {
		return new ResponseStatusException(HttpStatus.CONFLICT);
	}

}
