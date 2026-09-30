package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.UserIdentityService;
import com.onggijonggi.common.chat.persistence.MsgFileRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class Name : MsgFileControllerTest.java
 * Description : 첨부 업로드(POST /api/attachments)를 실제 서버 기동 상태에서 검증한다 — multipart 수신,
 *               크기 상한, 거절 사유의 에러 봉투, 그리고 1:1 이력 응답에 첨부가 실리는지까지.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class, DefaultWorkspaceFixture.class})
class MsgFileControllerTest {

	@LocalServerPort
	private int port;

	@Autowired
	private UserIdentityService userIdentityService;

	@Autowired
	private DirectChatTurnService directChatTurnService;

	@Autowired
	private MsgFileRepository msgFileRepository;

	private RestTestClient restTestClient;

	@BeforeEach
	void setUp() {
		restTestClient = RestTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.build();
	}

	@Test
	void storesTheExtractedTextAndReturnsOnlyTheIdAndName() {
		MsgFileView uploaded = upload("uploader", "회의록.txt", "연차는 사흘 전에 신청한다.".getBytes(StandardCharsets.UTF_8))
				.expectStatus().isCreated()
				.expectBody(MsgFileView.class)
				.returnResult().getResponseBody();

		assertThat(uploaded).isNotNull();
		assertThat(uploaded.fileName()).isEqualTo("회의록.txt");
		assertThat(msgFileRepository.findById(uploaded.id())).get()
				.satisfies(file -> {
					assertThat(file.getFileText()).isEqualTo("연차는 사흘 전에 신청한다.");
					assertThat(file.getMsgId()).isNull();
				});
	}

	@Test
	void rejectsUnsupportedFilesWithAReason() {
		upload("uploader", "사진.png", new byte[] {1, 2, 3})
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("UNSUPPORTED_FILE")
				.jsonPath("$.error.message").isEqualTo("txt·md·csv·pdf·docx 파일만 올릴 수 있습니다.");
	}

	@Test
	void rejectsFilesOverTheSizeLimit() {
		upload("uploader", "큰파일.txt", new byte[MsgFileService.MAX_FILE_BYTES + 1])
				.expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("FILE_TOO_LARGE");
	}

	@Test
	void rejectsUploadsWithoutToken() {
		MultipartBodyBuilder body = new MultipartBodyBuilder();
		body.part("file", named("a.txt", "hi".getBytes(StandardCharsets.UTF_8)));

		restTestClient.post()
				.uri("/api/attachments")
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body.build())
				.exchange()
				.expectStatus().isUnauthorized();
	}

	/** 발화에 실려 저장된 첨부는 1:1 이력에 이름으로 실린다. 추출한 본문은 내려가지 않는다. */
	@Test
	void listsAttachmentsOnTheDirectHistory() {
		MsgFileView uploaded = upload("history-owner", "규정.md", "비밀 본문".getBytes(StandardCharsets.UTF_8))
				.expectStatus().isCreated()
				.expectBody(MsgFileView.class)
				.returnResult().getResponseBody();
		UUID userId = userIdentityService.resolveOrProvision("history-owner").block();
		UUID threadId = UUID.randomUUID();
		directChatTurnService.prepareOrCreateWithPendingAgentBlocking(threadId, userId, "요약해줘",
				List.of(uploaded.id()), "요약해줘", UUID.randomUUID().toString());

		restTestClient.get()
				.uri("/api/chat/sessions/" + threadId + "/messages")
				.header(HttpHeaders.AUTHORIZATION, bearer("history-owner"))
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$[0].role").isEqualTo("user")
				.jsonPath("$[0].attachments[0].id").isEqualTo(uploaded.id().toString())
				.jsonPath("$[0].attachments[0].fileName").isEqualTo("규정.md")
				.jsonPath("$[1].attachments").isEmpty();
		assertThat(msgFileRepository.findById(uploaded.id())).get()
				.satisfies(file -> assertThat(file.getMsgId()).isNotNull());
	}

	private RestTestClient.ResponseSpec upload(String subject, String fileName, byte[] content) {
		MultipartBodyBuilder body = new MultipartBodyBuilder();
		body.part("file", named(fileName, content));
		return restTestClient.post()
				.uri("/api/attachments")
				.header(HttpHeaders.AUTHORIZATION, bearer(subject))
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body.build())
				.exchange();
	}

	private static ByteArrayResource named(String fileName, byte[] content) {
		return new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return fileName;
			}
		};
	}

	private static String bearer(String subject) {
		return "Bearer " + TestJwtSupport.signedJwt(subject, List.of("USER"));
	}

}
