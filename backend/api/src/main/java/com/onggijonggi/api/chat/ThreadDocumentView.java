package com.onggijonggi.api.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Class Name : ThreadDocumentView.java
 * Description : 저장소 경로·비밀값 없이 현재 문서 상태와 서버가 확인한 가능 작업을 제공한다.
 */
public record ThreadDocumentView(UUID id, String fileName, long size, String status, boolean pinned,
		boolean own, boolean canPin, boolean canUnpin, boolean canDelete, boolean canReadOriginal, Instant createdAt) {
	/** 방이 LOCKED·ARCHIVED이면 모든 쓰기 capability는 false다. */
	public record Listing(String threadStatus, boolean canUpload, List<ThreadDocumentView> documents) { }
}
