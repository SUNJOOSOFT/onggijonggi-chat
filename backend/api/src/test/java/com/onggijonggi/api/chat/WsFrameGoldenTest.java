package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : WsFrameGoldenTest.java
 * Description : WS 프레임 계약의 골든 파일(contracts/ws-frames)을 서버 쪽에서 지킨다(이슈 #160).
 *
 *               같은 계약이 서버 WsFrame·InboundFrame, 프론트 frames.ts, 목업 세 곳에 손으로 복제돼
 *               있는데 테스트가 각자 자기 쪽만 보면 필드 이름이 어긋나도 모두 초록불이다 — #129가
 *               participant.changed를 서버에만 더했을 때 실제로 그랬다. 그래서 서버가 만든 프레임
 *               예시를 파일로 두고, 서버(이 테스트)와 프론트(frames.golden.test.ts)가 같은 파일을
 *               검증한다. 한쪽이 필드를 바꾸면 반대쪽 테스트가 깨진다.
 *
 *               이 테스트가 보는 것:
 *               - 서버가 아는 타입(@JsonSubTypes)과 골든 파일 목록이 정확히 같다 — 타입을 더하고
 *                 파일을 안 만들면 여기서 깨지고, 파일이 생기면 프론트 테스트가 미러링을 요구한다.
 *               - 서버가 예시 값을 직렬화한 결과가 파일과 같다(나가는 방향).
 *               - 파일을 역직렬화하면 같은 값이 된다(들어오는 방향).
 *
 *               계약을 의도해서 바꿨으면 UPDATE_GOLDEN=true로 이 테스트를 돌려 파일을 다시 쓴 뒤 한 번
 *               더 돌려 확인하고, 프론트 frames.ts·parse-frame.ts를 맞춘다. 타입을 없앴으면 그 파일은
 *               손으로 지운다.
 */
class WsFrameGoldenTest {

	private static final boolean UPDATE = "true".equals(System.getenv("UPDATE_GOLDEN"));

	private static final UUID THREAD_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private static final UUID MSG_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private static final UUID CLIENT_MSG_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

	private static final UUID TURN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

	private final ObjectMapper objectMapper = new JsonMapper();

	/** 서버 → 클라이언트 프레임의 예시. 값은 nullable 필드도 채워 둔다 — 프론트가 문자열 자리를 검증하게. */
	private static Map<String, WsFrame> outboundSamples() {
		PresenceParticipant participant = new PresenceParticipant("kc-1", "주성민");
		Map<String, WsFrame> samples = new LinkedHashMap<>();
		samples.put("chat.answer", new ChatAnswerFrame(THREAD_ID, MSG_ID, TURN_ID, "gemini-3.6-flash", 7L, "안녕",
				List.of(new Citation("doc-001", "제목", "발췌", 0.91)), false, ChatAnswerStatus.STREAMING));
		samples.put("chat.message", new ChatMessageFrame(THREAD_ID, MSG_ID, CLIENT_MSG_ID, TURN_ID, 3L,
				participant.subject(), participant.displayName(), "@AI 요약해줘"));
		samples.put("presence.join", new PresenceJoinFrame(THREAD_ID, participant.subject(),
				participant.displayName()));
		samples.put("presence.leave", new PresenceLeaveFrame(THREAD_ID, participant.subject(),
				participant.displayName()));
		samples.put("presence.snapshot", new PresenceSnapshotFrame(THREAD_ID, List.of(participant)));
		samples.put("system.notice", new SystemNoticeFrame(THREAD_ID, "warning", "RISKY_CONTENT",
				"검토가 필요한 내용이 감지되었습니다.", "trace-1"));
		samples.put("error", new ErrorFrame(THREAD_ID, "FORBIDDEN", "권한이 없습니다.", "trace-1"));
		samples.put("participant.changed", new ParticipantChangedFrame(THREAD_ID, ParticipantChangeAction.INVITED,
				"kc-2", "이한결"));
		samples.put("chat.queued", new ChatQueuedFrame(THREAD_ID, TURN_ID, ChatQueuedStatus.QUEUED));
		samples.put("pong", new PongFrame());
		return samples;
	}

	/** 클라이언트 → 서버 프레임의 예시. 선택 필드도 채워 둔다 — 프론트가 필드 이름을 검증하게. */
	private static Map<String, InboundFrame> inboundSamples() {
		Map<String, InboundFrame> samples = new LinkedHashMap<>();
		samples.put("chat.message", new InboundChatMessage("@AI 요약해줘", "gemini-3.6-flash", CLIENT_MSG_ID, TURN_ID));
		samples.put("chat.cancel", new InboundChatCancel(THREAD_ID, TURN_ID));
		samples.put("room.subscribe", new InboundRoomSubscribe(THREAD_ID));
		samples.put("room.unsubscribe", new InboundRoomUnsubscribe(THREAD_ID));
		samples.put("ping", new InboundPing());
		return samples;
	}

	@Test
	void outboundGoldenFilesCoverEveryServerFrameType() throws IOException {
		// 갱신 모드에서는 아래 테스트가 파일을 쓰기 전에 이 검사가 먼저 돌 수 있다 — 쓰고 난 뒤 한 번 더 돌린다.
		assumeFalse(UPDATE);
		assertThat(outboundSamples().keySet()).containsExactlyInAnyOrderElementsOf(typeNames(WsFrame.class));
		assertThat(goldenTypes("outbound")).containsExactlyInAnyOrderElementsOf(typeNames(WsFrame.class));
	}

	@Test
	void inboundGoldenFilesCoverEveryClientFrameType() throws IOException {
		assumeFalse(UPDATE);
		assertThat(inboundSamples().keySet()).containsExactlyInAnyOrderElementsOf(typeNames(InboundFrame.class));
		assertThat(goldenTypes("inbound")).containsExactlyInAnyOrderElementsOf(typeNames(InboundFrame.class));
	}

	@Test
	void outboundFramesSerializeExactlyAsTheGoldenFiles() throws IOException {
		for (Map.Entry<String, WsFrame> sample : outboundSamples().entrySet()) {
			String actual = objectMapper.writeValueAsString(sample.getValue());
			Path golden = goldenFile("outbound", sample.getKey());
			if (UPDATE) {
				write(golden, actual);
			}
			assertThat(objectMapper.readTree(actual))
					.as("outbound/%s.json", sample.getKey())
					.isEqualTo(objectMapper.readTree(Files.readString(golden)));
			assertThat(objectMapper.readValue(Files.readString(golden), WsFrame.class))
					.as("outbound/%s.json 역직렬화", sample.getKey())
					.isEqualTo(sample.getValue());
		}
	}

	@Test
	void inboundGoldenFilesDeserializeIntoTheExpectedFrames() throws IOException {
		for (Map.Entry<String, InboundFrame> sample : inboundSamples().entrySet()) {
			String actual = objectMapper.writerFor(InboundFrame.class).writeValueAsString(sample.getValue());
			Path golden = goldenFile("inbound", sample.getKey());
			if (UPDATE) {
				write(golden, actual);
			}
			assertThat(objectMapper.readValue(Files.readString(golden), InboundFrame.class))
					.as("inbound/%s.json", sample.getKey())
					.isEqualTo(sample.getValue());
			assertThat(objectMapper.readTree(actual))
					.as("inbound/%s.json 필드 구성", sample.getKey())
					.isEqualTo(objectMapper.readTree(Files.readString(golden)));
		}
	}

	private static Set<String> typeNames(Class<?> envelope) {
		return Arrays.stream(envelope.getAnnotation(JsonSubTypes.class).value())
				.map(JsonSubTypes.Type::name)
				.collect(Collectors.toSet());
	}

	private static Set<String> goldenTypes(String direction) throws IOException {
		try (Stream<Path> files = Files.list(goldenRoot().resolve(direction))) {
			return files.map(path -> path.getFileName().toString())
					.filter(name -> name.endsWith(".json"))
					.map(name -> name.substring(0, name.length() - ".json".length()))
					.collect(Collectors.toSet());
		}
	}

	private static Path goldenFile(String direction, String type) {
		return goldenRoot().resolve(direction).resolve(type + ".json");
	}

	/** Gradle은 모듈 디렉터리(backend/api)에서 테스트를 돌린다. 저장소 루트까지 올라가며 찾는다. */
	private static Path goldenRoot() {
		for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			Path candidate = dir.resolve("contracts").resolve("ws-frames");
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("contracts/ws-frames 디렉터리를 찾지 못했습니다");
	}

	private void write(Path golden, String json) throws IOException {
		Files.createDirectories(golden.getParent());
		String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(objectMapper.readTree(json));
		Files.writeString(golden, pretty.replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
	}

}
