package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : WsFrameTest.java
 * Description : WsFrame의 type 태그 다형성 직렬화가 초안 스펙과 맞는지 순수 단위 테스트로
 *               검증한다. Spring 컨텍스트 없이, 실제 런타임과 동일한 Jackson 3
 *               ObjectMapper(JsonMapper)로 확인한다.
 */
class WsFrameTest {

	private final ObjectMapper objectMapper = new JsonMapper();

	@Test
	void serializesChatAnswerWithTypeTag() throws Exception {
		UUID sessionId = UUID.randomUUID();
		WsFrame frame = new ChatAnswerFrame(sessionId, "안녕", List.of(), false, ChatAnswerStatus.STREAMING);

		String json = objectMapper.writeValueAsString(frame);

		assertThat(json).contains("\"type\":\"chat.answer\"", "\"sessionId\":\"" + sessionId + "\"",
				"\"delta\":\"안녕\"", "\"status\":\"streaming\"");
	}

	@Test
	void citationOnlyAnswerFrameIsValid() throws Exception {
		UUID sessionId = UUID.randomUUID();
		List<Citation> citations = List.of(new Citation("doc-001", "제목", "발췌", 0.91));
		WsFrame frame = new ChatAnswerFrame(sessionId, "", citations, false, ChatAnswerStatus.STREAMING);

		String json = objectMapper.writeValueAsString(frame);
		WsFrame roundTripped = objectMapper.readValue(json, WsFrame.class);

		assertThat(roundTripped).isEqualTo(frame);
	}

	@Test
	void restrictedResultsOmittedIsIndependentOfEmptyCitations() throws Exception {
		UUID sessionId = UUID.randomUUID();
		WsFrame frame = new ChatAnswerFrame(sessionId, "", List.of(), true, ChatAnswerStatus.DONE);

		String json = objectMapper.writeValueAsString(frame);
		WsFrame roundTripped = objectMapper.readValue(json, WsFrame.class);

		assertThat(json).contains("\"restrictedResultsOmitted\":true");
		assertThat(roundTripped).isEqualTo(frame);
	}

	@Test
	void deserializesByTypeTagIntoCorrectSubtype() throws Exception {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		String json = "{\"type\":\"presence.join\",\"sessionId\":\"%s\",\"userId\":\"%s\"}"
				.formatted(sessionId, userId);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceJoinFrame(sessionId, userId));
	}

	@Test
	void deserializesPresenceLeaveByTypeTag() throws Exception {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		String json = "{\"type\":\"presence.leave\",\"sessionId\":\"%s\",\"userId\":\"%s\"}"
				.formatted(sessionId, userId);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceLeaveFrame(sessionId, userId));
	}

	@Test
	void deserializesPresenceSnapshotByTypeTag() throws Exception {
		UUID sessionId = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		String json = "{\"type\":\"presence.snapshot\",\"sessionId\":\"%s\",\"participants\":[\"%s\",\"%s\"]}"
				.formatted(sessionId, first, second);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceSnapshotFrame(sessionId, List.of(first, second)));
	}

	@Test
	void allFrameTypesRoundTripThroughJson() throws Exception {
		UUID sessionId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		List<WsFrame> frames = List.of(
				new ChatAnswerFrame(sessionId, "delta",
						List.of(new Citation("doc-001", "제목", "발췌", 0.91)), false, ChatAnswerStatus.DONE),
				new PresenceJoinFrame(sessionId, userId),
				new PresenceLeaveFrame(sessionId, userId),
				new PresenceSnapshotFrame(sessionId, List.of(userId)),
				new ChatMessageFrame(sessionId, userId, "content"),
				new ErrorFrame(sessionId, "FORBIDDEN", "권한이 없습니다.", "trace-1"));

		for (WsFrame frame : frames) {
			String json = objectMapper.writeValueAsString(frame);
			WsFrame roundTripped = objectMapper.readValue(json, WsFrame.class);
			assertThat(roundTripped).isEqualTo(frame);
		}
	}

	@Test
	void serializesErrorFrameWithNullSessionId() throws Exception {
		WsFrame frame = new ErrorFrame(null, "UNAUTHENTICATED", "인증이 필요합니다.", "trace-2");

		String json = objectMapper.writeValueAsString(frame);
		WsFrame roundTripped = objectMapper.readValue(json, WsFrame.class);

		assertThat(json).contains("\"type\":\"error\"", "\"code\":\"UNAUTHENTICATED\"");
		assertThat(roundTripped).isEqualTo(frame);
	}
}
