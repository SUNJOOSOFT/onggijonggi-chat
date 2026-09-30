package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.MsgFile;
import com.onggijonggi.common.chat.persistence.MsgFileRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Class Name : MsgFileServiceTest.java
 * Description : 첨부 파일의 텍스트 추출·거절 규칙, 발화에 실을 때의 소유 확인, AI 프롬프트 모양을 검증한다.
 *               PDF·DOCX는 테스트 안에서 실제 파일을 만들어 Tika 추출까지 거친다.
 */
@ExtendWith(MockitoExtension.class)
class MsgFileServiceTest {

	@Mock
	private MsgFileRepository msgFileRepository;

	private MsgFileService service;

	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new MsgFileService(msgFileRepository);
		lenient().when(msgFileRepository.save(any(MsgFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void readsPlainTextAsUtf8() {
		MsgFile saved = service.uploadBlocking(userId, "회의록.md", "# 회의록\n연차는 3일 전에 신청한다.".getBytes(
				StandardCharsets.UTF_8));

		assertThat(saved.getFileName()).isEqualTo("회의록.md");
		assertThat(saved.getFileText()).isEqualTo("# 회의록\n연차는 3일 전에 신청한다.");
		assertThat(saved.getUserId()).isEqualTo(userId);
		assertThat(saved.getMsgId()).isNull();
	}

	@Test
	void extractsTextFromDocx() throws IOException {
		MsgFile saved = service.uploadBlocking(userId, "규정.docx", docx("휴가 규정: 연차는 사흘 전에 신청한다."));

		assertThat(saved.getFileText()).contains("휴가 규정: 연차는 사흘 전에 신청한다.");
	}

	@Test
	void extractsTextFromPdf() throws IOException {
		MsgFile saved = service.uploadBlocking(userId, "policy.PDF", pdf("Leave must be requested three days ahead."));

		assertThat(saved.getFileText()).contains("Leave must be requested three days ahead.");
	}

	@Test
	void rejectsUnsupportedExtensions() {
		assertThatThrownBy(() -> service.uploadBlocking(userId, "사진.png", new byte[] {1, 2, 3}))
				.isInstanceOfSatisfying(MsgFileRejectedException.class, error -> {
					assertThat(error.getCode()).isEqualTo("UNSUPPORTED_FILE");
					assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				});
	}

	@Test
	void rejectsFilesWithoutText() {
		assertThatThrownBy(() -> service.uploadBlocking(userId, "빈파일.txt", "  \n ".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOfSatisfying(MsgFileRejectedException.class,
						error -> assertThat(error.getCode()).isEqualTo("EMPTY_FILE_TEXT"));
	}

	@Test
	void rejectsCorruptedDocuments() {
		byte[] broken = "%PDF-1.7\n1 0 obj << /Type /Catalog".getBytes(StandardCharsets.US_ASCII);

		assertThatThrownBy(() -> service.uploadBlocking(userId, "깨진.pdf", broken))
				.isInstanceOf(MsgFileRejectedException.class);
	}

	@Test
	void truncatesLongTextAndSaysSo() {
		String longText = "가".repeat(MsgFileService.MAX_TEXT_LENGTH + 10);

		MsgFile saved = service.uploadBlocking(userId, "긴글.txt", longText.getBytes(StandardCharsets.UTF_8));

		assertThat(saved.getFileText()).startsWith("가".repeat(MsgFileService.MAX_TEXT_LENGTH))
				.endsWith("(파일이 길어 앞 " + MsgFileService.MAX_TEXT_LENGTH + "자만 넣었습니다.)");
	}

	@Test
	void resolvesOwnUnattachedFilesInRequestedOrder() {
		MsgFile first = new MsgFile(UUID.randomUUID(), userId, "a.txt", "A");
		MsgFile second = new MsgFile(UUID.randomUUID(), userId, "b.txt", "B");
		when(msgFileRepository.findAllById(List.of(second.getId(), first.getId()))).thenReturn(List.of(first, second));

		assertThat(service.resolveForMessageBlocking(userId, List.of(second.getId(), first.getId())))
				.containsExactly(second, first);
	}

	@Test
	void refusesFilesUploadedBySomeoneElse() {
		MsgFile others = new MsgFile(UUID.randomUUID(), UUID.randomUUID(), "a.txt", "A");
		when(msgFileRepository.findAllById(List.of(others.getId()))).thenReturn(List.of(others));

		assertThatThrownBy(() -> service.resolveForMessageBlocking(userId, List.of(others.getId())))
				.isInstanceOfSatisfying(MsgFileRejectedException.class,
						error -> assertThat(error.getCode()).isEqualTo("INVALID_ATTACHMENT"));
	}

	@Test
	void refusesFilesAlreadySentWithAnotherMessage() {
		MsgFile sent = new MsgFile(UUID.randomUUID(), userId, "a.txt", "A");
		sent.attachTo(UUID.randomUUID());
		when(msgFileRepository.findAllById(List.of(sent.getId()))).thenReturn(List.of(sent));

		assertThatThrownBy(() -> service.resolveForMessageBlocking(userId, List.of(sent.getId())))
				.isInstanceOf(MsgFileRejectedException.class);
	}

	@Test
	void refusesMoreFilesThanTheLimit() {
		List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID)
				.limit(MsgFileService.MAX_FILES_PER_MESSAGE + 1).toList();

		assertThatThrownBy(() -> service.resolveForMessageBlocking(userId, tooMany))
				.isInstanceOf(MsgFileRejectedException.class);
	}

	@Test
	void resolvesNothingWhenNoAttachmentIdsAreSent() {
		assertThat(service.resolveForMessageBlocking(userId, null)).isEmpty();
		assertThat(service.resolveForMessageBlocking(userId, List.of())).isEmpty();
	}

	@Test
	void attachesOnlyOwnUnattachedFiles() {
		UUID msgId = UUID.randomUUID();
		MsgFile own = new MsgFile(UUID.randomUUID(), userId, "a.txt", "A");
		MsgFile others = new MsgFile(UUID.randomUUID(), UUID.randomUUID(), "b.txt", "B");
		when(msgFileRepository.findAllById(List.of(own.getId(), others.getId()))).thenReturn(List.of(own, others));

		service.attachBlocking(userId, msgId, List.of(own.getId(), others.getId()));

		assertThat(own.getMsgId()).isEqualTo(msgId);
		assertThat(others.getMsgId()).isNull();
	}

	@Test
	void wrapsEachFileBeforeTheMessage() {
		List<MsgFile> files = List.of(new MsgFile(UUID.randomUUID(), userId, "a.txt", "첫 파일"),
				new MsgFile(UUID.randomUUID(), userId, "b.csv", "둘째,파일"));

		assertThat(MsgFileService.withFiles("요약해줘", files)).isEqualTo("""
				[첨부 파일: a.txt]
				첫 파일
				[첨부 파일 끝: a.txt]

				[첨부 파일: b.csv]
				둘째,파일
				[첨부 파일 끝: b.csv]

				요약해줘""");
		assertThat(MsgFileService.withFiles("그대로", List.of())).isEqualTo("그대로");
		assertThat(MsgFileService.withFiles("그대로", null)).isEqualTo("그대로");
	}

	private static byte[] docx(String text) throws IOException {
		try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			document.createParagraph().createRun().setText(text);
			document.write(out);
			return out.toByteArray();
		}
	}

	private static byte[] pdf(String text) throws IOException {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(72, 700);
				content.showText(text);
				content.endText();
			}
			document.save(out);
			return out.toByteArray();
		}
	}

}
