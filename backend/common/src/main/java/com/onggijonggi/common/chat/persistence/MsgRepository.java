package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.Msg;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : MsgRepository.java
 * Description : msg JPA 레포지토리. 정렬은 seq가 정본이다(created_at은 동률이 될 수 있다,
 *               V11__message.sql 참고). ux_msg_thr_seq 인덱스와 같은 순서.
 */
public interface MsgRepository extends JpaRepository<Msg, UUID> {

	List<Msg> findByThrIdOrderBySeqAsc(UUID thrId);

}
