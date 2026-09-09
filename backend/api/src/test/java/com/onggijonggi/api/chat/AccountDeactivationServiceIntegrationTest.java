package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.auth.AccountDeactivationService;
import com.onggijonggi.api.auth.UserIdentityService;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.domain.ThrMbrRole;
import com.onggijonggi.common.chat.domain.ThrMbrStatus;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

/**
 * Class Name : AccountDeactivationServiceIntegrationTest.java
 * Description : AccountDeactivationService를 실 JPA(H2)로 검증한다. Mockito 유닛 테스트는 리포지토리를
 *               전부 목으로 두어 transferOwnership()의 원자적 UPDATE 뒤 detached 엔티티를 save()할 때
 *               생기는 merge 문제를 잡지 못한다 — 그 회귀를 여기서 막는다(#132).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ChatControllerTest.FakeChatModelConfig.class, FakeJwtDecoderConfig.class, CollabRoomFixture.class})
class AccountDeactivationServiceIntegrationTest {

	@Autowired
	private AccountDeactivationService accountDeactivationService;

	@Autowired
	private CollabRoomFixture.CollabRooms rooms;

	@Autowired
	private ThrMbrRepository thrMbrRepository;

	@Autowired
	private UserIdentityService userIdentityService;

	/**
	* transferOwnership() 뒤 종료 대상을 다시 조회하지 않으면, save()가 detached 엔티티를 merge하며
	* role을 위임 전 값(OWNER)으로 되돌린다 — 그 회귀를 막는다.
	*/
	@Test
	void endedOwnerParticipationKeepsTheTransferredMemberRole() {
		UUID threadId = rooms.openRoom("deactivate-owner", "deactivate-successor");
		UUID ownerId = userIdentityService.resolveOrProvision("deactivate-owner").block();
		UUID successorId = userIdentityService.resolveOrProvision("deactivate-successor").block();

		StepVerifier.create(accountDeactivationService.deactivate(ownerId)).verifyComplete();

		ThrMbr endedOwnerMembership = thrMbrRepository.findAll().stream()
				.filter(member -> member.getThrId().equals(threadId) && member.getUserId().equals(ownerId))
				.findFirst()
				.orElseThrow();
		assertThat(endedOwnerMembership.getStatus()).isEqualTo(ThrMbrStatus.REVOKED);
		assertThat(endedOwnerMembership.getRole()).isEqualTo(ThrMbrRole.MEMBER);

		ThrMbr successorMembership = thrMbrRepository
				.findByThrIdAndUserIdAndStatus(threadId, successorId, ThrMbrStatus.ACTIVE)
				.orElseThrow();
		assertThat(successorMembership.getRole()).isEqualTo(ThrMbrRole.OWNER);
	}

}
