package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActorProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadOutputController.java
 * Description : 방의 "출력"(AI가 만든 문서) 목록·내려받기 API(#414). 모든 DB 작업은 boundedElastic에서 실행한다.
 */
@RestController
public class ThreadOutputController {
	private final CurrentActorProvider actors;
	private final ThreadOutputService outputs;

	public ThreadOutputController(CurrentActorProvider actors, ThreadOutputService outputs) {
		this.actors = actors;
		this.outputs = outputs;
	}

	@GetMapping("/api/threads/{threadId}/outputs")
	public Mono<List<ThreadOutputView>> list(@PathVariable UUID threadId) {
		return actors.currentActor().flatMap(actor -> Mono.fromCallable(() -> outputs.list(threadId, actor))
				.subscribeOn(Schedulers.boundedElastic()));
	}

	@GetMapping("/api/threads/{threadId}/outputs/{outputId}/download")
	public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID threadId, @PathVariable UUID outputId) {
		return actors.currentActor().flatMap(actor -> Mono.fromCallable(() -> outputs.download(threadId, outputId, actor))
				.subscribeOn(Schedulers.boundedElastic()))
				.map(file -> ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
						.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
						.header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes()));
	}
}
