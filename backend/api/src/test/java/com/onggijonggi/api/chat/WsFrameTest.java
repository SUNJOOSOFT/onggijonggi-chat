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
		String json = """
				{"type":"presence.join","sessionId":"%s","subject":"kc-1","displayName":"주성민"}"""
				.formatted(sessionId);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceJoinFrame(sessionId, "kc-1", "주성민"));
	}

	@Test
	void deserializesPresenceLeaveByTypeTag() throws Exception {
		UUID sessionId = UUID.randomUUID();
		String json = """
				{"type":"presence.leave","sessionId":"%s","subject":"kc-1","displayName":"주성민"}"""
				.formatted(sessionId);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceLeaveFrame(sessionId, "kc-1", "주성민"));
	}

	@Test
	void deserializesPresenceSnapshotByTypeTag() throws Exception {
		UUID sessionId = UUID.randomUUID();
		String json = """
				{"type":"presence.snapshot","sessionId":"%s","participants":[				{"subject":"kc-1","displayName":"주성민"},{"subject":"kc-2","displayName":"이한결"}]}"""
				.formatted(sessionId);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceSnapshotFrame(sessionId,
				List.of(new PresenceParticipant("kc-1", "주성민"),
						new PresenceParticipant("kc-2", "이한결"))));
	}

	@Test
	void allFrameTypesRoundTripThroughJson() throws Exception {
		UUID sessionId = UUID.randomUUID();
		PresenceParticipant participant = new PresenceParticipant("kc-1", "주성민");
		List<WsFrame> frames = List.of(
				new ChatAnswerFrame(sessionId, "delta",
						List.of(new Citation("doc-001", "제목", "발췌", 0.91)), false, ChatAnswerStatus.DONE),
				new PresenceJoinFrame(sessionId, participant.subject(), participant.displayName()),
				new PresenceLeaveFrame(sessionId, participant.subject(), participant.displayName()),
				new PresenceSnapshotFrame(sessionId, List.of(participant)),
				new ChatMessageFrame(sessionId, participant.subject(), participant.displayName(), "content"),
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
