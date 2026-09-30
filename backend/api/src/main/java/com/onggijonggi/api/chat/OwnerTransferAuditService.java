package com.onggijonggi.api.chat;

import com.onggijonggi.common.authz.AuthorizationAudit;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.AuthorizationAuditTargetKind;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : OwnerTransferAuditService.java
 * Description : 협업방 OWNER 이양과 그 감사(OWNER_TRANSFERRED)를 한 트랜잭션으로 한다(#260). Tenant가 정해진 방은 Tenant 행을
 *               먼저 잠가 다른 권한 변경과 직렬로 둔다. 두 참여 행이 정확히 바뀌지 않으면 409이고 감사도 남지 않는다.
 *               Tenant가 없는 옛 방(절체 전)은 감사할 Tenant가 없어 이양만 한다.
 */
@Service
public class OwnerTransferAuditService {
	private final ThrMbrRepository memberships;
	private final ThrRepository threads;
	private final TenantRepository tenants;
	private final AuthorizationAuditRepository audits;
	private final ObjectMapper json;
	private final TransactionTemplate transactions;

	public OwnerTransferAuditService(ThrMbrRepository memberships, ThrRepository threads, TenantRepository tenants,
			AuthorizationAuditRepository audits, ObjectMapper json, PlatformTransactionManager manager) {
		this.memberships = memberships;
		this.threads = threads;
		this.tenants = tenants;
		this.audits = audits;
		this.json = json;
		this.transactions = new TransactionTemplate(manager);
	}

	public void transfer(UUID threadId, UUID fromUserId, UUID toUserId) {
		transactions.executeWithoutResult(status -> {
			Thr thread = threads.findById(threadId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			if (thread.getTenantId() != null) {
				var tenant = tenants.findById(thread.getTenantId())
						.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
				tenants.findByKeyForUpdate(tenant.getKey())
						.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			}
			if (memberships.transferOwnership(threadId, fromUserId, toUserId) != 2) {
				throw new ResponseStatusException(HttpStatus.CONFLICT);
			}
			if (thread.getTenantId() != null) {
				String ref = json.writeValueAsString(Map.of("thread_id", threadId));
				audits.save(AuthorizationAudit.managed(thread.getTenantId(), fromUserId, "[\"OWNER\"]",
						AuthorizationAuditEventKind.OWNER_TRANSFERRED, AuthorizationAuditTargetKind.THREAD, ref,
						thread.getWorkspaceNodeId(),
						json.writeValueAsString(Map.of("ownerUserId", fromUserId)),
						json.writeValueAsString(Map.of("ownerUserId", toUserId)), UUID.randomUUID().toString(), null));
			}
		});
	}
}
