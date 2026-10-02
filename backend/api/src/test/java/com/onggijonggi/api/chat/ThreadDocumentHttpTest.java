package com.onggijonggi.api.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : ThreadDocumentHttpTest.java
 * Description : 방 문서 API가 문서 고유의 거부 사유를 공통 에러 봉투의 별도 code로 내보내는지, 원본 다운로드 응답이
 *               첨부·캐시 금지 헤더를 붙이는지 실제 서버 기동 상태에서 확인한다. 인가·상태 전이는 ThreadDocumentPostgresTest가 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class})
class ThreadDocumentHttpTest {

	@LocalServerPort
	private int port;

	@MockitoBean
	private ThreadDocumentService documents;

	private RestTestClient client;
	private final UUID room = UUID.randomUUID();
	private final UUID document = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	/** 요청 본문·메서드가 서비스의 변경 종류로 바뀌는 곳은 컨트롤러뿐이라 여기서 대응을 못 박는다. */
	@Test
	void requestsMapToTheServiceChangeKinds() {
		UUID pin = UUID.randomUUID(), unpin = UUID.randomUUID(), delete = UUID.randomUUID();
		for (var entry : List.of(List.of(pin, true), List.of(unpin, false)))
			client.put().uri("/api/threads/" + room + "/documents/" + document + "/pin")
					.header(HttpHeaders.AUTHORIZATION, bearer())
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"requestId\":\"" + entry.get(0) + "\",\"pinned\":" + entry.get(1) + "}")
					.exchange()
					.expectStatus().isNoContent();
		client.delete().uri("/api/threads/" + room + "/documents/" + document + "?requestId=" + delete)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.exchange()
				.expectStatus().isNoContent();

		verify(documents).change(eq(room), eq(document), any(), eq("PINNED"), eq(pin));
		verify(documents).change(eq(room), eq(document), any(), eq("UNPINNED"), eq(unpin));
		verify(documents).change(eq(room), eq(document), any(), eq("DELETED"), eq(delete));
	}

	@Test
	void uploadPassesTheFileNameAndBytesAndAMissingRequestIdIsRejected() {
		when(documents.upload(any(), any(), any(), anyString(), any())).thenReturn(new ThreadDocumentView(document, "회의록.txt", 6,
				"PENDING", false, true, false, false, true, false, java.time.Instant.now()));
		MultipartBodyBuilder body = new MultipartBodyBuilder();
		body.part("file", new ByteArrayResource("본문".getBytes(StandardCharsets.UTF_8)) {
			@Override public String getFilename() { return "회의록.txt"; }
		});
		client.post().uri("/api/threads/" + room + "/documents?documentId=" + document)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body.build())
				.exchange()
				.expectStatus().isCreated();
		verify(documents).upload(eq(room), eq(document), any(), eq("회의록.txt"), eq("본문".getBytes(StandardCharsets.UTF_8)));

		client.put().uri("/api/threads/" + room + "/documents/" + document + "/pin")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"pinned\":true}")
				.exchange()
				.expectStatus().isBadRequest();
		verify(documents, never()).change(any(), any(), any(), any(), any());
	}

	/** 참여자 상태 충돌(PARTICIPANT_STATE_CONFLICT)로 나가면 화면이 "참여자 정보가 바뀌었다"고 잘못 안내한다. */
	@Test
	void documentStateConflictHasItsOwnCode() {
		doThrow(ThreadDocumentException.conflict()).when(documents).change(eq(room), eq(document), any(), eq("PINNED"), any());

		client.put().uri("/api/threads/" + room + "/documents/" + document + "/pin")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"requestId\":\"" + UUID.randomUUID() + "\",\"pinned\":true}")
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.CONFLICT)
				.expectBody().jsonPath("$.error.code").isEqualTo("DOCUMENT_STATE_CONFLICT");
	}

	/** 원본 저장소 장애는 서버 설정 문제(SERVICE_UNAVAILABLE)와 달리 재시도로 풀릴 수 있어 별도 code다. 원인은 응답에 싣지 않는다. */
	@Test
	void storageOutageHasItsOwnCodeWithoutInternalDetail() {
		when(documents.original(eq(room), eq(document), any()))
				.thenThrow(ThreadDocumentException.storageUnavailable(new IllegalStateException("http://document-worker:8100 거절")));

		client.get().uri("/api/threads/" + room + "/documents/" + document + "/original")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
				.expectBody()
				.jsonPath("$.error.code").isEqualTo("DOCUMENT_STORAGE_UNAVAILABLE")
				.jsonPath("$.error.message").isEqualTo("문서 저장소를 사용할 수 없습니다.");
	}

	@Test
	void originalIsAnUncachedAttachmentWithTheRegisteredName() {
		when(documents.original(eq(room), eq(document), any()))
				.thenReturn(new ThreadDocumentService.Original("회의록 1.txt", "본문".getBytes(StandardCharsets.UTF_8)));

		client.get().uri("/api/threads/" + room + "/documents/" + document + "/original")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.exchange()
				.expectStatus().isOk()
				.expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
				.expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment; .*filename\\*=UTF-8''%ED%9A%8C%EC%9D%98%EB%A1%9D%201\\.txt")
				.expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
				.expectHeader().valueEquals("X-Content-Type-Options", "nosniff");
	}

	/** 상한을 넘는 본문은 서비스(인가·저장)에 닿기 전에 첨부와 같은 code로 거절한다. */
	@Test
	void oversizedUploadIsRejectedBeforeTheService() {
		MultipartBodyBuilder body = new MultipartBodyBuilder();
		body.part("file", new ByteArrayResource(new byte[ThreadDocumentService.MAX_FILE_BYTES + 1]) {
			@Override public String getFilename() { return "큰파일.txt"; }
		});

		client.post().uri("/api/threads/" + room + "/documents?documentId=" + document)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body.build())
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
				.expectBody().jsonPath("$.error.code").isEqualTo("FILE_TOO_LARGE");
		verify(documents, never()).upload(any(), any(), any(), anyString(), any());
	}

	/** 파서 단계 상한(11MB)을 넘는 본문은 임시 디스크를 더 쓰기 전에 끊고 서비스에 닿지 않는다. */
	@Test
	void bodyBeyondTheParserLimitNeverReachesTheService() {
		MultipartBodyBuilder body = new MultipartBodyBuilder();
		body.part("file", new ByteArrayResource(new byte[12 * 1024 * 1024]) {
			@Override public String getFilename() { return "huge.txt"; }
		});

		client.post().uri("/api/threads/" + room + "/documents?documentId=" + document)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body.build())
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
				.expectBody().jsonPath("$.error.code").isEqualTo("FILE_TOO_LARGE");
		verify(documents, never()).upload(any(), any(), any(), anyString(), any());
	}

	private static String bearer() {
		return "Bearer " + TestJwtSupport.signedJwt("doc-http-user", List.of("USER"));
	}
}
