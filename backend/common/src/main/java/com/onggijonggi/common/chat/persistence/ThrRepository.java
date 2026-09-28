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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Class Name : ThrRepository.java
 * Description : thr JPA 레포지토리.
 */
public interface ThrRepository extends JpaRepository<Thr, UUID> {

	/** LOCKED·ARCHIVED로 바뀐 방에서 새 메시지·초대 같은 쓰기 작업을 막는 판정에 쓴다(#131). */
	boolean existsByIdAndStatus(UUID id, ThrStatus status);

	/** 협업방 전용 API가 DIRECT 방을 존재 비노출 404로 막을 때 쓰는 종류 확인이다. */
	boolean existsByIdAndKind(UUID id, ThrKind kind);

	/** Workspace 노드가 Thread에 쓰이는지 — 쓰이는 노드는 reparent할 수 없다(0001 6.4). */
	boolean existsByWorkspaceNodeId(UUID workspaceNodeId);

	/** 기존 1:1 목록 호환 경로는 DIRECT 소유 Thread만 생성 시각 역순으로 읽는다. */
	List<Thr> findByKindAndDrcOwnUserIdOrderByCreatedAtDesc(ThrKind kind, UUID drcOwnUserId);

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
	* 워크스페이스가 정해지지 않은 방을 한 워크스페이스(common)로 옮긴다. bootstrap이 끝날 때 부른다 — 트리가 생기기
	* 전에 만들어진 방이 판정이 켜진 뒤 막히지 않게 한다. 다른 Tenant로 이미 정해진 방은 건드리지 않는다.
	* 자식 행(thr_mbr·msg 등)의 tnn_id는 채우지 않는다. 완료된 msg는 UPDATE 자체가 거부되고(V11 트리거), 자식 행
	* backfill은 Tenant 절체의 몫이다. 이후 새로 생기는 자식 행은 트리거가 여기서 채운 값을 복사한다.
	* @return 옮긴 방 수
	*/
	@Modifying(clearAutomatically = true)
	@Query(value = "update thr set tnn_id = :tenantId, wrk_node_id = :nodeId "
			+ "where wrk_node_id is null and (tnn_id is null or tnn_id = :tenantId)", nativeQuery = true)
	int placeUnassigned(@Param("tenantId") UUID tenantId, @Param("nodeId") UUID nodeId);

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
