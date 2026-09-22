package com.onggijonggi.common.authz;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : AuthorizationAuditRepository.java
 * Description : authz_adt 레포지토리. 행은 수정·삭제할 수 없다(DB trigger가 거부한다).
 */
public interface AuthorizationAuditRepository extends JpaRepository<AuthorizationAudit, UUID> {

	/** reconcile이 이 Tenant에 같은 배포 ID로 이미 적용됐는지 — 같은 배포 ID는 Tenant마다 한 번만 적용한다. */
	boolean existsByTenantIdAndDeploymentId(UUID tenantId, String deploymentId);

	List<AuthorizationAudit> findByTenantIdOrderByCreatedAtAscIdAsc(UUID tenantId);

	Optional<AuthorizationAudit> findFirstByTenantIdOrderByCreatedAtDescIdDesc(UUID tenantId);

	/**
	 * 같은 drift를 다시 적지 않으려고 직전 drift 행만 찾는다. "가장 최근 행"으로 찾으면 같은 실행에서 노드·부여를
	 * 먼저 만든 경우 그 생성 행이 잡혀 중복 판정이 빗나간다.
	 */
	Optional<AuthorizationAudit> findFirstByTenantIdAndEventKindOrderByCreatedAtDescIdDesc(UUID tenantId,
			AuthorizationAuditEventKind eventKind);
}
