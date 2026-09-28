package com.onggijonggi.api.chat;

import com.onggijonggi.api.auth.CurrentActorProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Class Name : WorkspaceController.java
 * Description : 채팅 화면이 쓰는 워크스페이스 목록. 협업방을 만들 때 고를 수 있는 곳이자 목록을 묶는 기준이다.
 *               casbin 프로필과 무관하게 늘 있다 — 트리가 없는 배포에서는 빈 목록이고, 화면은 그때 고르는 칸을 숨긴다.
 */
@RestController
public class WorkspaceController {

	private final CurrentActorProvider currentActorProvider;
	private final ThreadWorkspaceService threadWorkspaceService;

	public WorkspaceController(CurrentActorProvider currentActorProvider, ThreadWorkspaceService threadWorkspaceService) {
		this.currentActorProvider = currentActorProvider;
		this.threadWorkspaceService = threadWorkspaceService;
	}

	/** 내가 볼 수 있고 방을 둘 수 있는 워크스페이스를 트리 순서로. ROOT는 빠진다. */
	@GetMapping("/api/workspaces")
	public Flux<ThreadWorkspaceService.WorkspaceView> listWorkspaces() {
		return currentActorProvider.currentActor()
				.flatMap(actor -> threadWorkspaceService.visibleWorkspaces(actor.subject()))
				.flatMapMany(Flux::fromIterable);
	}
}
