package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.MsgFile;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Class Name : MsgFileRepository.java
 * Description : msg_file JPA 레포지토리.
 */
public interface MsgFileRepository extends JpaRepository<MsgFile, UUID> {

	/** 이력·AI 문맥에 붙일 첨부를 메시지 여러 개에 대해 한 번에 읽는다. 순서는 올린 순서다. */
	List<MsgFile> findByMsgIdInOrderByCreatedAtAsc(Collection<UUID> msgIds);

	/** 보내지 않고 남은 첨부를 지운다. 원본(msg_file_org)은 DB cascade로 함께 지워진다. */
	@Modifying
	@Query("delete from MsgFile f where f.msgId is null and f.createdAt < :before")
	int deleteUnsentBefore(@Param("before") Instant before);

}
