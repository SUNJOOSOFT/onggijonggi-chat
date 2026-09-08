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
 *               남지 않는지 검증한다(이슈 #146).
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

		assertThatThrownBy(() -> collabThreadCreationService.createBlocking(UUID.randomUUID(), title))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(thrRepository.findAll())
				.extracting(Thr::getTitle)
				.doesNotContain(title);
	}
}
