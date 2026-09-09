package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Class Name : ThrRepository.java
 * Description : thr JPA 레포지토리.
 */
public interface ThrRepository extends JpaRepository<Thr, UUID> {

	/** LOCKED·ARCHIVED로 바뀐 방에서 새 메시지·초대 같은 쓰기 작업을 막는 판정에 쓴다(#131). */
	boolean existsByIdAndStatus(UUID id, ThrStatus status);

	/** 위험 발화 사후 검증 배치가 스캔할 대상이다(#28) — ARCHIVED는 새 메시지가 없어 제외한다. */
	List<Thr> findByKindAndStatusNot(ThrKind kind, ThrStatus status);

	/**
	* Message 순서 채번(V8__thread.sql). next_seq를 원자적으로 올리고, 이번 메시지가 쓸 값(증가
	* 전 값, 0-based)을 그대로 돌려준다 — 호출부에서 off-by-one을 계산하지 않게 하기 위함이다.
	* RETURNING은 결과 행을 반환하므로 @Modifying 없이 네이티브 쿼리로 조회하듯 호출한다.
	*/
	@Query(value = "update thr set next_seq = next_seq + 1 where id = :id returning next_seq - 1",
			nativeQuery = true)
	Long allocateNextSeq(@Param("id") UUID id);

}
