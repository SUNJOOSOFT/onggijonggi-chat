package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.domain.ThrKind;
import com.onggijonggi.common.chat.domain.ThrStatus;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

	/**
	* seq 블록 예약을 위해 thr 행을 비관적으로 잠근다(이슈 #190).
	*
	* allocateNextSeq처럼 UPDATE ... RETURNING을 쓰지 않는다 — H2가 그 문법을 지원하지 않아
	* 통합 테스트(H2 인메모리)가 채번을 한 번도 실제로 실행하지 못한다. 기존 경로는 저장 실패를
	* 삼켜 왔기에 드러나지 않았지만, #190에서는 채번이 방송 앞으로 오므로 드러난다.
	* SELECT ... FOR UPDATE는 H2·PostgreSQL 모두 지원한다.
	*
	* 잠그는 비용은 SEQ_BLOCK_SIZE건에 한 번뿐이라, 블록 예약 방식이라서 감당 가능한 선택이다.
	*/
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Thr t where t.id = :id")
	Optional<Thr> findByIdForSeqUpdate(@Param("id") UUID id);

}
