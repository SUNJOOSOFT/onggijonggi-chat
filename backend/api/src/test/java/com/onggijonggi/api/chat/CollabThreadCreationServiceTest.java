package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrMbr;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Class Name : CollabThreadCreationServiceTest.java
 * Description : 협업방과 최초 OWNER 멤버십이 하나의 트랜잭션으로 생성되어, 멤버십 저장 실패 때 빈 방이
 *               남지 않는지 검증한다(이슈 #146). idempotencyKey 재시도 시 방이 중복 생성되지
 *               않는지도 함께 검증한다(이슈 #149).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ChatControllerTest.FakeChatModelConfig.class, FakeJwtDecoderConfig.class, FakeKeycloakAdminConfig.class})
class CollabThreadCreationServiceTest {

	@Autowired
	private CollabThreadCreationService collabThreadCreationService;

	@Autowired
	private ThrRepository thrRepository;

	@MockitoBean
	private ThrMbrRepository thrMbrRepository;

	@Test
	void rollsBackThreadCreationWhenInitialOwnerMembershipCannotBeSaved() {
		String title = "OWNER 저장 실패 방";

		when(thrMbrRepository.save(any(ThrMbr.class)))
				.thenThrow(new DataIntegrityViolationException("thr_mbr 저장 실패"));

		assertThatThrownBy(() -> collabThreadCreationService.createBlocking(UUID.randomUUID(), title, null))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(thrRepository.findAll())
				.extracting(Thr::getTitle)
				.doesNotContain(title);
	}

	/** idempotencyKey가 없는 호출은 이 계약을 요구하지 않은 것으로 보고, 같은 title이라도 매번 새로 만든다. */
	@Test
	void createsANewThreadEveryTimeWhenNoIdempotencyKeyIsGiven() {
		UUID userId = UUID.randomUUID();
		String title = "키 없는 방";

		UUID first = collabThreadCreationService.createBlocking(userId, title, null);
		UUID second = collabThreadCreationService.createBlocking(userId, title, null);

		assertThat(first).isNotEqualTo(second);
	}

	/** 응답 유실 뒤 같은 키·같은 title로 재시도하면 새 방을 또 만들지 않고 최초 결과를 그대로 돌려준다. */
	@Test
	void reusesTheSameThreadWhenTheSameKeyAndTitleAreRetried() {
		UUID userId = UUID.randomUUID();
		String title = "재시도 방";
		String key = UUID.randomUUID().toString();

		UUID first = collabThreadCreationService.createBlocking(userId, title, key);
		UUID retried = collabThreadCreationService.createBlocking(userId, title, key);

		assertThat(retried).isEqualTo(first);
		assertThat(thrRepository.findAll()).extracting(Thr::getTitle).containsOnlyOnce(title);
	}

	/** 같은 키에 다른 title이 오면 재사용 실수로 보고 거절한다 — 앞서 만든 방을 조용히 돌려주지 않는다. */
	@Test
	void rejectsTheSameKeyWithADifferentTitle() {
		UUID userId = UUID.randomUUID();
		String key = UUID.randomUUID().toString();

		collabThreadCreationService.createBlocking(userId, "첫 제목", key);

		assertThatThrownBy(() -> collabThreadCreationService.createBlocking(userId, "다른 제목", key))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	/** 다른 사용자가 우연히 같은 키 문자열을 써도 서로 다른 요청으로 취급한다 — 키는 사용자 범위다. */
	@Test
	void treatsTheSameKeyAsDifferentRequestsAcrossUsers() {
		String key = UUID.randomUUID().toString();
		String title = "각자의 방";

		UUID firstUsersThread = collabThreadCreationService.createBlocking(UUID.randomUUID(), title, key);
		UUID secondUsersThread = collabThreadCreationService.createBlocking(UUID.randomUUID(), title, key);

		assertThat(firstUsersThread).isNotEqualTo(secondUsersThread);
	}
}
