package com.onggijonggi.api.chat;

import java.time.Duration;
import java.util.UUID;

/**
 * Class Name : Eventually.java
 * Description : 방 내보내기가 비동기가 되며(이슈 #405, RoomSessionRegistry의 방별 대기열) "방송 직후 받았다"를
 *               바로 단언할 수 없게 된 테스트가 쓰는 기다리기 도구. 단언 내용은 그대로 두고, 성립할 때까지 짧게
 *               다시 본다. "아무것도 오지 않았다"는 먼저 그 방 대기열이 다 비워질 때까지 기다린 뒤 본다.
 */
final class Eventually {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

	private Eventually() {
	}

	/** assertion이 통과할 때까지 기다린다. 제한 시간이 지나면 마지막 실패를 그대로 던진다. */
	static void eventually(Runnable assertion) {
		long deadline = System.nanoTime() + DEFAULT_TIMEOUT.toNanos();
		while (true) {
			try {
				assertion.run();
				return;
			} catch (AssertionError failure) {
				if (System.nanoTime() > deadline) {
					throw failure;
				}
				sleep();
			}
		}
	}

	/**
	 * 그 방들의 대기열이 다 비워질 때까지 기다린다 — 부정 단언(아무것도 안 왔다) 전에 부른다. 방이 비어 지워졌으면 바로
	 * 통과하므로(지워진 방의 남은 완료·left는 보지 못한다) 방에 누가 남아 있는 상황에서만 쓴다.
	 */
	static void awaitIdle(RoomSessionRegistry registry, UUID... threadIds) {
		eventually(() -> {
			for (UUID threadId : threadIds) {
				if (!registry.idle(threadId)) {
					throw new AssertionError("room outbox still draining: " + threadId);
				}
			}
		});
	}

	private static void sleep() {
		try {
			Thread.sleep(5);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}
}
