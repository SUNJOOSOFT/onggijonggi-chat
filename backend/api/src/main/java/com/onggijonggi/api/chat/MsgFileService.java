package com.onggijonggi.api.chat;

import com.onggijonggi.common.chat.domain.MsgFile;
import com.onggijonggi.common.chat.persistence.MsgFileRepository;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : MsgFileService.java
 * Description : 채팅 첨부 파일(msg_file). 올릴 때 텍스트를 뽑아 저장하고, 발화에 실릴 때 그 메시지에
 *               붙이고, AI 문맥을 만들 때 텍스트를 프롬프트에 끼운다. 원본은 저장하지 않는다.
 *
 *               txt·md·csv는 UTF-8로 그대로 읽는다 — Tika의 문자셋 추정이 짧은 한글 파일을 다른
 *               인코딩으로 볼 수 있어서다. PDF·DOCX만 Tika로 뽑는다.
 *
 *               전부 블로킹 호출이라 메서드 이름에 Blocking을 붙인다(MsgPersistenceService와 같은 관례).
 */
@Service
public class MsgFileService {

	/** 업로드 한 번의 최대 크기. 추출 전 원본 기준이다. */
	static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

	/** 발화 하나에 실을 수 있는 첨부 수. */
	static final int MAX_FILES_PER_MESSAGE = 5;

	/**
	* 파일 하나에서 AI 문맥에 넣는 최대 글자 수. 문맥은 최근 메시지 20개를 통째로 싣기 때문에(app.thread.ai.
	* max-context-messages) 파일마다 상한이 없으면 한 대화의 토큰이 끝없이 커진다.
	*/
	static final int MAX_TEXT_LENGTH = 50_000;

	private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md", "csv");

	private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "docx");

	private final MsgFileRepository msgFileRepository;

	public MsgFileService(MsgFileRepository msgFileRepository) {
		this.msgFileRepository = msgFileRepository;
	}

	/** 텍스트를 뽑아 아직 어느 메시지에도 붙지 않은 첨부로 저장한다. */
	@Transactional
	public MsgFile uploadBlocking(UUID userId, String fileName, byte[] content) {
		String name = fileName == null ? "" : fileName.strip();
		if (name.isEmpty() || name.length() > 255) {
			throw rejected(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE", "파일 이름이 비었거나 너무 깁니다.");
		}
		String text = extractText(extensionOf(name), content).strip();
		if (text.isEmpty()) {
			throw rejected(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE_TEXT",
					"파일에서 글자를 찾지 못했습니다. 스캔한 PDF처럼 이미지로만 된 파일은 읽을 수 없습니다.");
		}
		if (text.length() > MAX_TEXT_LENGTH) {
			text = text.substring(0, MAX_TEXT_LENGTH) + "\n\n(파일이 길어 앞 " + MAX_TEXT_LENGTH + "자만 넣었습니다.)";
		}
		return msgFileRepository.save(new MsgFile(UUID.randomUUID(), userId, name, text));
	}

	/**
	* 발화에 실린 첨부를 확인한다 — 본인이 올렸고 아직 다른 메시지에 붙지 않은 것만 받는다. 방송 프레임에
	* 파일 이름을 실어야 해서 저장 전에 미리 읽는다. 돌려주는 순서는 요청한 순서다.
	*/
	@Transactional(readOnly = true)
	public List<MsgFile> resolveForMessageBlocking(UUID userId, List<UUID> fileIds) {
		if (fileIds == null || fileIds.isEmpty()) {
			return List.of();
		}
		if (fileIds.size() > MAX_FILES_PER_MESSAGE || new HashSet<>(fileIds).size() != fileIds.size()) {
			throw rejected(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
					"첨부는 한 번에 " + MAX_FILES_PER_MESSAGE + "개까지 보낼 수 있습니다.");
		}
		Map<UUID, MsgFile> byId = msgFileRepository.findAllById(fileIds).stream()
				.collect(Collectors.toMap(MsgFile::getId, Function.identity()));
		return fileIds.stream().map(id -> {
			MsgFile file = byId.get(id);
			if (file == null || !file.getUserId().equals(userId) || file.getMsgId() != null) {
				throw rejected(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
						"첨부 파일을 찾을 수 없습니다. 다시 올려 주세요.");
			}
			return file;
		}).toList();
	}

	/**
	* 방금 저장한 메시지에 첨부를 붙인다. 호출부의 트랜잭션 안에서 불러야 한다 — 메시지 INSERT와 같은
	* 트랜잭션이어야 FK가 맞는다(MsgFile.attachTo 참고). 확인 뒤 그 사이 다른 메시지에 붙은 것은 건너뛴다.
	*/
	@Transactional
	public void attachBlocking(UUID userId, UUID msgId, List<UUID> fileIds) {
		if (fileIds == null || fileIds.isEmpty()) {
			return;
		}
		msgFileRepository.findAllById(fileIds).stream()
				.filter(file -> file.getMsgId() == null && file.getUserId().equals(userId))
				.forEach(file -> file.attachTo(msgId));
	}

	/** 메시지별 첨부. 첨부가 없는 메시지는 결과에 없다. */
	@Transactional(readOnly = true)
	public Map<UUID, List<MsgFile>> byMessageBlocking(Collection<UUID> msgIds) {
		if (msgIds.isEmpty()) {
			return Map.of();
		}
		return msgFileRepository.findByMsgIdInOrderByCreatedAtAsc(msgIds).stream()
				.collect(Collectors.groupingBy(MsgFile::getMsgId));
	}

	/** AI에 보낼 사용자 메시지 본문 — 첨부 텍스트를 본문 앞에 파일별로 감싸 붙인다. */
	static String withFiles(String content, List<MsgFile> files) {
		if (files == null || files.isEmpty()) {
			return content;
		}
		StringBuilder prompt = new StringBuilder();
		for (MsgFile file : files) {
			prompt.append("[첨부 파일: ").append(file.getFileName()).append("]\n")
					.append(file.getFileText())
					.append("\n[첨부 파일 끝: ").append(file.getFileName()).append("]\n\n");
		}
		return prompt.append(content).toString();
	}

	private static String extractText(String extension, byte[] content) {
		if (PLAIN_TEXT_EXTENSIONS.contains(extension)) {
			return new String(content, StandardCharsets.UTF_8);
		}
		if (!DOCUMENT_EXTENSIONS.contains(extension)) {
			throw rejected(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE",
					"txt·md·csv·pdf·docx 파일만 올릴 수 있습니다.");
		}
		try {
			return new TikaDocumentReader(new ByteArrayResource(content)).get().stream()
					.map(Document::getText)
					.filter(Objects::nonNull)
					.collect(Collectors.joining("\n"));
		} catch (RuntimeException e) {
			throw rejected(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_FILE",
					"파일을 읽지 못했습니다. 손상되었거나 암호가 걸린 파일일 수 있습니다.");
		}
	}

	private static String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static MsgFileRejectedException rejected(HttpStatus status, String code, String message) {
		return new MsgFileRejectedException(status, code, message);
	}

}
