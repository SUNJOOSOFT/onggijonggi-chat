package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.AthKind;
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
	* 재접속 따라잡기용(이슈 #190) — 클라이언트가 마지막으로 받은 seq 이후만 돌려준다.
	*
	* seq는 블록 예약이 남긴 구멍 때문에 연속하지 않는다. 그래서 "빠진 번호를 채운다"가 아니라
	* "이 번호보다 큰 것을 준다"로 정의한다 — 구멍이 있어도 호출부가 멈추지 않는다.
	*/
	List<Msg> findByThrIdAndSeqGreaterThanOrderBySeqAsc(UUID thrId, long seq);

	/**
	* @AI 멘션 시 LLM 문맥으로 쓸 최근 메시지를 역순으로 최대 pageable.getPageSize()개 가져온다
	* (이슈 #100). status로 COMPLETE만 걸러 PENDING·FAILED·CANCELLED·DENIED는 문맥에서
	* 제외한다 — 내용이 비어있거나 신뢰할 수 없다.
	*/
	List<Msg> findByThrIdAndStatusOrderBySeqDesc(UUID thrId, MsgStatus status, Pageable pageable);

	/** 위험 발화 사후 검증 배치가 커서 이후의 사람 발화만 스캔한다(#28) — AGENT·SYSTEM은 대상이 아니다. */
	List<Msg> findByThrIdAndAthKindAndSeqGreaterThanOrderBySeqAsc(UUID thrId, AthKind athKind, long seq);

}
