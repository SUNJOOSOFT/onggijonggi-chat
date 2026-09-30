package com.onggijonggi.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Class Name : MsgFile.java
 * Description : 채팅 첨부 파일(V20260929055051721__msg_file.sql `msg_file`). 원본은 두지 않고 추출한
 *               텍스트만 든다. 올린 직후에는 msgId가 null이고, 발화에 실려 메시지가 저장될 때
 *               attachTo로 그 메시지를 가리키게 된다.
 */
@Entity
@Table(name = "msg_file")
public class MsgFile {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "msg_id")
	private UUID msgId;

	@Column(name = "file_name", nullable = false)
	private String fileName;

	@Column(name = "file_text", nullable = false)
	private String fileText;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MsgFile() {
	}

	public MsgFile(UUID id, UUID userId, String fileName, String fileText) {
		this.id = id;
		this.userId = userId;
		this.fileName = fileName;
		this.fileText = fileText;
		this.createdAt = Instant.now();
	}

	/**
	* 메시지를 가리키게 한다. 벌크 UPDATE가 아니라 엔티티 변경으로 하는 이유는, 같은 트랜잭션에서
	* 방금 save한 msg 행보다 이 UPDATE가 먼저 나가면 FK가 깨지기 때문이다 — Hibernate는 INSERT를
	* UPDATE보다 먼저 flush한다.
	*/
	public void attachTo(UUID msgId) {
		this.msgId = msgId;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getMsgId() {
		return msgId;
	}

	public String getFileName() {
		return fileName;
	}

	public String getFileText() {
		return fileText;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
