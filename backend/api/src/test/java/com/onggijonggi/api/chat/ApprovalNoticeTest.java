package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : ApprovalNoticeTest.java
 * Description : 승인 줄의 pyl_json 저장·복원과, 같은 SYSTEM 메시지에 담기는 입퇴장 줄과 서로 읽히지 않는지를 검증한다(#407).
 */
class ApprovalNoticeTest {

	private final ObjectMapper objectMapper = new JsonMapper();
	private final ApprovalNotice notice = new ApprovalNotice(ApprovalNotice.Event.REJECTED, UUID.randomUUID(),
			"kc-1", "kc-2");

	@Test
	void roundTripsThroughPylJson() {
		String json = notice.toPylJson(objectMapper);
		assertThat(json).contains("\"approval\"");
		assertThat(ApprovalNotice.fromPylJson(objectMapper, json)).isEqualTo(notice);
	}

	@Test
	void emptyOrUnrelatedPayloadsAreNotApprovalLines() {
		assertThat(ApprovalNotice.fromPylJson(objectMapper, null)).isNull();
		assertThat(ApprovalNotice.fromPylJson(objectMapper, "  ")).isNull();
		assertThat(ApprovalNotice.fromPylJson(objectMapper, "{\"agent\":\"FIN\"}")).isNull();
		assertThat(ApprovalNotice.fromPylJson(objectMapper, "not json")).isNull();
	}

	@Test
	void approvalAndMembershipLinesNeverReadEachOther() {
		String membership = new MembershipNotice(MembershipNotice.Event.JOINED, "kc-1").toPylJson(objectMapper);
		assertThat(ApprovalNotice.fromPylJson(objectMapper, membership)).isNull();
		assertThat(MembershipNotice.fromPylJson(objectMapper, notice.toPylJson(objectMapper))).isNull();
	}

}
