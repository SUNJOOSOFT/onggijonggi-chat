package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.common.chat.domain.MsgIdmKey;
import com.onggijonggi.common.chat.persistence.MsgIdmKeyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Class Name : DirectChatTurnServiceIntegrationTest.java
 * Description : DirectChatTurnService의 idempotency 키 만료·재사용을 실제 DB(H2)와 실제
 *               영속성 컨텍스트로 검증한다(이슈 #233, PR #239 리뷰). Mockito로 리포지토리를
 *               전부 mock한 DirectChatTurnServiceTest는 Hibernate의 flush 순서(모든 INSERT
 *               먼저, DELETE는 나중)를 재현할 수 없어, "만료된 키를 지우고 같은 트랜잭션에서
 *               새 키를 저장"하는 경로의 유니크 인덱스 충돌을 못 잡는다 — 실제로 리뷰어가
 *               지적하기 전까지 놓쳤다. CollabThreadCreationServiceTest와 같은 패턴이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
class DirectChatTurnServiceIntegrationTest {

	@Autowired
	private DirectChatTurnService directChatTurnService;

	@Autowired
	private MsgIdmKeyRepository msgIdmKeyRepository;

	/**
	* TTL(5분)을 넘긴 키로 재시도하면 옛 행을 즉시 지우고 새 요청으로 진행해야 한다.
	* deleteImmediatelyByUserIdAndKey(즉시 실행되는 @Modifying 삭제) 없이 일반 delete()만
	* 썼다면, 뒤이은 저장이 같은 트랜잭션의 flush 순서 때문에 (user_id, idm_key) 유니크
	* 인덱스와 충돌해 이 재시도가 실패했다.
	*/
	@Test
	void retryingAfterTheKeyExpiresSucceedsWithoutAUniqueConstraintViolation() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		String key = UUID.randomUUID().toString();

		DirectChatTurnService.StoredTurn first = directChatTurnService
				.prepareOrCreateWithPendingAgentBlocking(threadId, userId, "안녕", "안녕", key);

		MsgIdmKey saved = msgIdmKeyRepository.findByUserIdAndKey(userId, key).orElseThrow();
		var createdAt = MsgIdmKey.class.getDeclaredField("createdAt");
		createdAt.setAccessible(true);
		createdAt.set(saved, Instant.now().minus(Duration.ofMinutes(6)));
		msgIdmKeyRepository.save(saved);

		// 여기까지 예외 없이 도달하는 것 자체가 증거다 — 고쳐지기 전엔
		// DataIntegrityViolationException으로 이 호출에서 테스트가 실패했다.
		DirectChatTurnService.StoredTurn retried = directChatTurnService
				.prepareExistingWithPendingAgentBlocking(threadId, userId, "안녕", key);

		assertThat(retried.replay()).isFalse();
		assertThat(retried.humanMessageId()).isNotEqualTo(first.humanMessageId());
	}

}
