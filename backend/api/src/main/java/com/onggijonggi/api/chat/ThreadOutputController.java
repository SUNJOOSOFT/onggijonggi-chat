package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActorProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Class Name : ThreadOutputController.java
 * Description : 방의 "출력"(AI가 만든 문서) 목록 API(#414). 모든 DB 작업은 boundedElastic에서 실행한다.
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
}
