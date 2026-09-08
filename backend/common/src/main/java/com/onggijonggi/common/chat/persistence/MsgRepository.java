package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.Msg;
import com.onggijonggi.common.chat.domain.MsgStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : MsgRepository.java
 * Description : msg JPA 레포지토리. 정렬은 seq가 정본이다(created_at은 동률이 될 수 있다,
 *               V11__message.sql 참고). ux_msg_thr_seq 인덱스와 같은 순서.
 */
public interface MsgRepository extends JpaRepository<Msg, UUID> {

	List<Msg> findByThrIdOrderBySeqAsc(UUID thrId);

	/**
	* @AI 멘션 시 LLM 문맥으로 쓸 최근 메시지를 역순으로 최대 pageable.getPageSize()개 가져온다
	* (이슈 #100). status로 COMPLETE만 걸러 PENDING·FAILED·CANCELLED·DENIED는 문맥에서
	* 제외한다 — 내용이 비어있거나 신뢰할 수 없다.
	*/
	List<Msg> findByThrIdAndStatusOrderBySeqDesc(UUID thrId, MsgStatus status, Pageable pageable);

}
