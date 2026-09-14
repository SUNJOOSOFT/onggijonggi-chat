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
		UUID threadId = UUID.randomUUID();
		UUID msgId = UUID.randomUUID();
		WsFrame frame = new ChatAnswerFrame(threadId, msgId, null, "gemini", 7L, "안녕", List.of(), false,
				ChatAnswerStatus.STREAMING);

		String json = objectMapper.writeValueAsString(frame);

		assertThat(json).contains("\"type\":\"chat.answer\"", "\"threadId\":\"" + threadId + "\"",
				"\"msgId\":\"" + msgId + "\"", "\"seq\":7",
				"\"delta\":\"안녕\"", "\"status\":\"streaming\"");
	}

	@Test
	void citationOnlyAnswerFrameIsValid() throws Exception {
		UUID threadId = UUID.randomUUID();
		List<Citation> citations = List.of(new Citation("doc-001", "제목", "발췌", 0.91));
		WsFrame frame = new ChatAnswerFrame(threadId, UUID.randomUUID(), null, "gemini", 0L, "", citations, false, ChatAnswerStatus.STREAMING);

		String json = objectMapper.writeValueAsString(frame);
		WsFrame roundTripped = objectMapper.readValue(json, WsFrame.class);

		assertThat(roundTripped).isEqualTo(frame);
	}

	@Test
	void restrictedResultsOmittedIsIndependentOfEmptyCitations() throws Exception {
		UUID threadId = UUID.randomUUID();
		WsFrame frame = new ChatAnswerFrame(threadId, UUID.randomUUID(), null, "gemini", 0L, "", List.of(), true, ChatAnswerStatus.DONE);

		String json = objectMapper.writeValueAsString(frame);
		WsFrame roundTripped = objectMapper.readValue(json, WsFrame.class);

		assertThat(json).contains("\"restrictedResultsOmitted\":true");
		assertThat(roundTripped).isEqualTo(frame);
	}

	@Test
	void deserializesByTypeTagIntoCorrectSubtype() throws Exception {
		UUID threadId = UUID.randomUUID();
		String json = """
				{"type":"presence.join","threadId":"%s","subject":"kc-1","displayName":"주성민"}"""
				.formatted(threadId);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceJoinFrame(threadId, "kc-1", "주성민"));
	}

	@Test
	void deserializesPresenceLeaveByTypeTag() throws Exception {
		UUID threadId = UUID.randomUUID();
		String json = """
				{"type":"presence.leave","threadId":"%s","subject":"kc-1","displayName":"주성민"}"""
				.formatted(threadId);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceLeaveFrame(threadId, "kc-1", "주성민"));
	}

	@Test
	void deserializesPresenceSnapshotByTypeTag() throws Exception {
		UUID threadId = UUID.randomUUID();
		String json = """
				{"type":"presence.snapshot","threadId":"%s","participants":[				{"subject":"kc-1","displayName":"주성민"},{"subject":"kc-2","displayName":"이한결"}]}"""
				.formatted(threadId);

		WsFrame frame = objectMapper.readValue(json, WsFrame.class);

		assertThat(frame).isEqualTo(new PresenceSnapshotFrame(threadId,
				List.of(new PresenceParticipant("kc-1", "주성민"),
						new PresenceParticipant("kc-2", "이한결"))));
	}

	/** chat.message도 msgId·seq를 실어야 프론트가 REST 이력과 이어붙일 수 있다(이슈 #190). */
	@Test
	void serializesChatMessageWithMsgIdAndSeq() throws Exception {
		UUID threadId = UUID.randomUUID();
		UUID msgId = UUID.randomUUID();
		WsFrame frame = new ChatMessageFrame(threadId, msgId, null, null, 12L, "kc-1", "주성민", "안녕하세요");

		String json = objectMapper.writeValueAsString(frame);

		assertThat(json).contains("\"type\":\"chat.message\"", "\"msgId\":\"" + msgId + "\"",
				"\"seq\":12");
		assertThat(objectMapper.readValue(json, WsFrame.class)).isEqualTo(frame);
	}

	@Test
	void allFrameTypesRoundTripThroughJson() throws Exception {
		UUID threadId = UUID.randomUUID();
		PresenceParticipant participant = new PresenceParticipant("kc-1", "주성민");
		List<WsFrame> frames = List.of(
				new ChatAnswerFrame(threadId, UUID.randomUUID(), null, "gemini", 0L, "delta",
						List.of(new Citation("doc-001", "제목", "발췌", 0.91)), false, ChatAnswerStatus.DONE),
				new PresenceJoinFrame(threadId, participant.subject(), participant.displayName()),
				new PresenceLeaveFrame(threadId, participant.subject(), participant.displayName()),
				new PresenceSnapshotFrame(threadId, List.of(participant)),
				new ChatMessageFrame(threadId, UUID.randomUUID(), null, null, 3L, participant.subject(), participant.displayName(), "content"),
				new SystemNoticeFrame(threadId, "warning", "RISKY_CONTENT", "위험 감지", "trace-2"),
				new ErrorFrame(threadId, "FORBIDDEN", "권한이 없습니다.", "trace-1"),
				new ChatQueuedFrame(threadId, UUID.randomUUID(), ChatQueuedStatus.QUEUED),
				new PongFrame());

		for (WsFrame frame : frames) {
			String json = objectMapper.writeValueAsString(frame);
			WsFrame roundTripped = objectMapper.readValue(json, WsFrame.class);
			assertThat(roundTripped).isEqualTo(frame);
		}
	}

	/** 클라이언트가 만든 식별자는 에코와 답변에 그대로 실린다(이슈 #160). */
	@Test
	void serializesClientIdsOnEchoAndTurnIdAndModelOnAnswer() throws Exception {
		UUID clientMsgId = UUID.randomUUID();
		UUID turnId = UUID.randomUUID();
		String message = objectMapper.writeValueAsString(new ChatMessageFrame(UUID.randomUUID(), UUID.randomUUID(),
				clientMsgId, turnId, 1L, "kc-1", "주성민", "@AI 질문"));
		String answer = objectMapper.writeValueAsString(new ChatAnswerFrame(UUID.randomUUID(), UUID.randomUUID(),
				turnId, "gemini", 2L, "답", List.of(), false, ChatAnswerStatus.STREAMING));

		assertThat(message).contains("\"clientMsgId\":\"" + clientMsgId + "\"", "\"turnId\":\"" + turnId + "\"");
		assertThat(answer).contains("\"turnId\":\"" + turnId + "\"", "\"model\":\"gemini\"");
	}

	@Test
	void serializesQueuedAndPongFramesWithTypeTags() throws Exception {
		String queued = objectMapper.writeValueAsString(
				new ChatQueuedFrame(UUID.randomUUID(), null, ChatQueuedStatus.CANCELLED));
		String pong = objectMapper.writeValueAsString(new PongFrame());

		assertThat(queued).contains("\"type\":\"chat.queued\"", "\"status\":\"cancelled\"", "\"turnId\":null");
		assertThat(pong).isEqualTo("{\"type\":\"pong\"}");
	}

	/** 클라이언트가 올려보내는 프레임은 WsFrame과 목록이 다른 InboundFrame으로 읽는다(이슈 #160). */
	@Test
	void deserializesInboundFramesByTypeTag() throws Exception {
		UUID clientMsgId = UUID.randomUUID();
		UUID turnId = UUID.randomUUID();
		UUID threadId = UUID.randomUUID();

		assertThat(objectMapper.readValue("""
				{"type":"chat.message","content":"@AI 질문","model":"gpt","clientMsgId":"%s","turnId":"%s"}"""
				.formatted(clientMsgId, turnId), InboundFrame.class))
				.isEqualTo(new InboundChatMessage("@AI 질문", "gpt", clientMsgId, turnId));
		assertThat(objectMapper.readValue("{\"type\":\"chat.message\",\"content\":\"hi\"}", InboundFrame.class))
				.isEqualTo(new InboundChatMessage("hi", null, null, null));
		assertThat(objectMapper.readValue("{\"type\":\"chat.cancel\",\"threadId\":\"%s\",\"turnId\":\"%s\"}"
				.formatted(threadId, turnId), InboundFrame.class)).isEqualTo(new InboundChatCancel(threadId, turnId));
		assertThat(objectMapper.readValue("{\"type\":\"room.subscribe\",\"threadId\":\"%s\"}".formatted(threadId),
				InboundFrame.class)).isEqualTo(new InboundRoomSubscribe(threadId));
		assertThat(objectMapper.readValue("{\"type\":\"room.unsubscribe\",\"threadId\":\"%s\"}".formatted(threadId),
				InboundFrame.class)).isEqualTo(new InboundRoomUnsubscribe(threadId));
		assertThat(objectMapper.readValue("{\"type\":\"ping\"}", InboundFrame.class)).isEqualTo(new InboundPing());
	}

	@Test
	void serializesErrorFrameWithNullThreadId() throws Exception {
		WsFrame frame = new ErrorFrame(null, "UNAUTHENTICATED", "인증이 필요합니다.", "trace-2");

		String json = objectMapper.writeValueAsString(frame);
		WsFrame roundTripped = objectMapper.readValue(json, WsFrame.class);

		assertThat(json).contains("\"type\":\"error\"", "\"code\":\"UNAUTHENTICATED\"");
		assertThat(roundTripped).isEqualTo(frame);
	}
}
