package com.onggijonggi.common.chat.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class Name : MsgTest.java
 * Description : Msg 팩토리·전이 메서드가 V11__message.sql CHECK 제약과 일치하는 상태를 만드는지
 *               순수 자바 단위로 검증한다(DB 제약·트리거 자체는 별도로 실제 Postgres에서 확인함).
 */
class MsgTest {

	private final UUID thrId = UUID.randomUUID();

	private final UUID thrMbrId = UUID.randomUUID();

	@Test
	void humanMessageIsCompleteWithParticipantAndCompletedAt() {
		Msg msg = Msg.human(thrId, 0, thrMbrId, "안녕하세요");

		assertThat(msg.getThrId()).isEqualTo(thrId);
		assertThat(msg.getSeq()).isZero();
		assertThat(msg.getAthKind()).isEqualTo(AthKind.HUMAN);
		assertThat(msg.getThrMbrId()).isEqualTo(thrMbrId);
		assertThat(msg.getStatus()).isEqualTo(MsgStatus.COMPLETE);
		assertThat(msg.getContent()).isEqualTo("안녕하세요");
		assertThat(msg.getCompletedAt()).isNotNull();
	}

	@Test
	void pendingAgentMessageHasNoParticipantAndNoCompletedAt() {
		Msg msg = Msg.pendingAgent(thrId, 1);

		assertThat(msg.getAthKind()).isEqualTo(AthKind.AGENT);
		assertThat(msg.getThrMbrId()).isNull();
		assertThat(msg.getStatus()).isEqualTo(MsgStatus.PENDING);
		assertThat(msg.getContent()).isEmpty();
		assertThat(msg.getCompletedAt()).isNull();
	}

	@Test
	void completeFillsContentAndCompletedAt() {
		Msg msg = Msg.pendingAgent(thrId, 1);

		msg.complete("답변입니다");

		assertThat(msg.getStatus()).isEqualTo(MsgStatus.COMPLETE);
		assertThat(msg.getContent()).isEqualTo("답변입니다");
		assertThat(msg.getCompletedAt()).isNotNull();
	}

	@Test
	void failSetsTerminalStatusAndCompletedAt() {
		Msg msg = Msg.pendingAgent(thrId, 1);

		msg.fail(MsgStatus.CANCELLED);

		assertThat(msg.getStatus()).isEqualTo(MsgStatus.CANCELLED);
		assertThat(msg.getCompletedAt()).isNotNull();
	}

}
