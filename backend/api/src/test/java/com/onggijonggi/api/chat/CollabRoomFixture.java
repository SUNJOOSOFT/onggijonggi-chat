package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.UserIdentityService;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Class Name : CollabRoomFixture.java
 * Description : WS 통합 테스트가 붙을 방을 실제 행으로 만들어 준다. 이슈 #22로 방 입장에 참가자
 *               검증이 붙어, 임의의 threadId로는 더 이상 연결이 서지 않는다.
 *
 *               첫 번째 subject가 방을 만든 사람(OWNER)이고 나머지는 MEMBER다. app_user는 WS
 *               핸들러와 같은 경로(UserIdentityService)로 미리 만들어 둔다 — 그래야 연결 시점에
 *               같은 id로 해석돼 참가자 조회가 맞는다.
 */
@TestConfiguration
public class CollabRoomFixture {

	@Bean
	CollabRooms collabRooms(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository,
			UserIdentityService userIdentityService) {
		return new CollabRooms(thrRepository, thrMbrRepository, userIdentityService);
	}

	public static final class CollabRooms {

		private final ThrRepository thrRepository;
		private final ThrMbrRepository thrMbrRepository;
		private final UserIdentityService userIdentityService;

		CollabRooms(ThrRepository thrRepository, ThrMbrRepository thrMbrRepository,
				UserIdentityService userIdentityService) {
			this.thrRepository = thrRepository;
			this.thrMbrRepository = thrMbrRepository;
			this.userIdentityService = userIdentityService;
		}

		public UUID openRoom(String... subjects) {
			UUID ownerId = userIdentityService.resolveOrProvision(subjects[0]).block();
			Thr thr = thrRepository.save(Thr.collab(ownerId, "테스트 협업방"));
			thrMbrRepository.save(new ThrMbr(thr.getId(), ownerId, ThrMbrRole.OWNER, ownerId));
			for (int i = 1; i < subjects.length; i++) {
				UUID memberId = userIdentityService.resolveOrProvision(subjects[i]).block();
				thrMbrRepository.save(new ThrMbr(thr.getId(), memberId, ThrMbrRole.MEMBER, ownerId));
			}
			return thr.getId();
		}

		/** app_user 행만 만든다 — 초대는 되지만 아직 어느 방에도 없는 사람을 세울 때 쓴다. */
		public UUID user(String subject) {
			return userIdentityService.resolveOrProvision(subject).block();
		}

		/** 참여자 관리 계약의 케이스 대부분이 "끝난 참가"를 전제로 해서, 그 상태를 직접 만들어 준다. */
		public void endParticipation(UUID threadId, String subject, ThrMbrStatus endStatus, String reason) {
			ThrMbr member = activeMember(threadId, subject);
			member.end(endStatus, reason);
			thrMbrRepository.save(member);
		}

		/** #131 목록 필터링 테스트가 ARCHIVED 방을 미리 만들어 두는 데 쓴다. */
		public void archiveRoom(UUID threadId) {
			Thr thr = thrRepository.findById(threadId).orElseThrow();
			thr.archive();
			thrRepository.save(thr);
		}

		/** #131 쓰기 차단 테스트가 LOCKED 방을 미리 만들어 두는 데 쓴다. */
		public void lockRoom(UUID threadId) {
			Thr thr = thrRepository.findById(threadId).orElseThrow();
			thr.lock();
			thrRepository.save(thr);
		}

		public ThrMbrRole roleOf(UUID threadId, String subject) {
			return activeMember(threadId, subject).getRole();
		}

		/** 활성 행이 없으면(나갔거나 제거됐으면) 그 마지막 참가 행의 상태를 돌려준다. */
		public Optional<ThrMbr> lastParticipation(UUID threadId, String subject) {
			UUID userId = user(subject);
			return thrMbrRepository.findAll().stream()
					.filter(member -> member.getThrId().equals(threadId) && member.getUserId().equals(userId))
					.max(Comparator.comparing(ThrMbr::getCreatedAt));
		}

		private ThrMbr activeMember(UUID threadId, String subject) {
			UUID userId = user(subject);
			return thrMbrRepository.findByThrIdAndUserIdAndStatus(threadId, userId, ThrMbrStatus.ACTIVE)
					.orElseThrow(() -> new IllegalStateException("활성 참가자가 아닙니다: " + subject));
		}

	}

}
