package com.onggijonggi.api.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Class Name : ThreadDocumentView.java
 * Description : 저장소 경로·비밀값 없이 현재 문서 상태와 서버가 확인한 가능 작업을 제공한다.
 */
public record ThreadDocumentView(UUID id, String fileName, long size, String status, boolean pinned,
		boolean own, boolean canPin, boolean canUnpin, boolean canDelete, boolean canReadOriginal,
		boolean canReprocess, Instant createdAt) {
	/** 방이 LOCKED·ARCHIVED이면 모든 쓰기 capability는 false다. attachments는 이 방 메시지에 실려 보낸 채팅 첨부다. */
	public record Listing(String threadStatus, boolean canUpload, List<ThreadDocumentView> documents,
			List<Attachment> attachments) { }

	/**
	 * 이 방에 보낸 채팅 첨부 하나. 원본을 두기 전에 올린 첨부는 size가 null이고 내려받기·등록을 할 수 없다.
	 * 방 문서로 등록하면 첨부 id가 그 방 문서의 id가 된다 — registered면 이미 등록된 것이다(삭제된 것 포함).
	 * senderSubject는 보낸 사람의 Keycloak subject다. 이름은 화면이 이미 받은 참여자 목록에서 찾는다 —
	 * 10초마다 도는 목록 조회에서 Keycloak을 부르지 않으려는 것이다.
	 */
	public record Attachment(UUID id, String fileName, Long size, String senderSubject, boolean own, boolean canReadOriginal,
			boolean canRegister, boolean registered, Instant createdAt) { }
}
