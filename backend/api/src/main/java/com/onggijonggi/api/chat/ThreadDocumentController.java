package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadDocumentController.java
 * Description : 생성된 DIRECT·COLLAB 방의 RAG 문서 API. 모든 DB/원본 통신은 boundedElastic에서 실행한다.
 */
@RestController
public class ThreadDocumentController {
	private final CurrentActorProvider actors;
	private final ThreadDocumentService documents;
	public ThreadDocumentController(CurrentActorProvider actors, ThreadDocumentService documents) {
		this.actors = actors;
		this.documents = documents;
	}

	@GetMapping("/api/threads/{threadId}/documents")
	public Mono<ThreadDocumentView.Listing> list(@PathVariable UUID threadId) {
		return actors.currentActor().flatMap(actor -> Mono.fromCallable(() -> documents.list(threadId, actor))
				.subscribeOn(Schedulers.boundedElastic()));
	}

	@PostMapping(path = "/api/threads/{threadId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<ThreadDocumentView> upload(@PathVariable UUID threadId, @RequestParam UUID documentId,
			@RequestPart("file") FilePart file) {
		return actors.currentActor().flatMap(actor -> DataBufferUtils.join(file.content(), ThreadDocumentService.MAX_FILE_BYTES)
				.map(buffer -> {
					try {
						byte[] bytes = new byte[buffer.readableByteCount()];
						buffer.read(bytes);
						return bytes;
					} finally { DataBufferUtils.release(buffer); }
				})
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST)))
				.onErrorMap(DataBufferLimitException.class, error -> ThreadDocumentException.tooLarge())
				.publishOn(Schedulers.boundedElastic())
				.map(bytes -> documents.upload(threadId, documentId, actor, file.filename(), bytes)));
	}

	/** requestId는 변경 요청의 재전송 식별자이며 사용자 식별자가 아니다. */
	public record ChangeRequest(@NotNull UUID requestId, @NotNull Boolean pinned) { }

	@PutMapping("/api/threads/{threadId}/documents/{documentId}/pin")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> pin(@PathVariable UUID threadId, @PathVariable UUID documentId,
			@Valid @RequestBody ChangeRequest request) {
		return actors.currentActor().flatMap(actor -> Mono.fromRunnable(() -> documents.change(threadId, documentId,
				actor, request.pinned() ? "PINNED" : "UNPINNED", request.requestId())).subscribeOn(Schedulers.boundedElastic())).then();
	}

	@DeleteMapping("/api/threads/{threadId}/documents/{documentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public Mono<Void> delete(@PathVariable UUID threadId, @PathVariable UUID documentId, @RequestParam UUID requestId) {
		return actors.currentActor().flatMap(actor -> Mono.fromRunnable(() -> documents.change(threadId, documentId,
				actor, "DELETED", requestId)).subscribeOn(Schedulers.boundedElastic())).then();
	}

	@GetMapping("/api/threads/{threadId}/documents/{documentId}/original")
	public Mono<ResponseEntity<byte[]>> original(@PathVariable UUID threadId, @PathVariable UUID documentId) {
		return actors.currentActor().flatMap(actor -> Mono.fromCallable(() -> documents.original(threadId, documentId, actor))
				.subscribeOn(Schedulers.boundedElastic()))
				.map(original -> ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
						.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(original.fileName(), StandardCharsets.UTF_8).build().toString())
						.header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(original.bytes()));
	}
}
