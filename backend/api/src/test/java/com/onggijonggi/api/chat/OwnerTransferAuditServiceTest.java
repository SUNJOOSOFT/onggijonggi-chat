package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.common.authz.AuthorizationAudit;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.chat.domain.Thr;
import com.onggijonggi.common.chat.persistence.ThrMbrRepository;
import com.onggijonggi.common.chat.persistence.ThrRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : OwnerTransferAuditServiceTest.java
 * Description : OWNER 이양이 두 행을 바꿨을 때만 감사가 남고, 충돌(409)이면 감사가 없는지 검증한다.
 */
class OwnerTransferAuditServiceTest {
	private final ThrMbrRepository memberships = mock(ThrMbrRepository.class);
	private final ThrRepository threads = mock(ThrRepository.class);
	private final TenantRepository tenants = mock(TenantRepository.class);
	private final AuthorizationAuditRepository audits = mock(AuthorizationAuditRepository.class);
	private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
	private final OwnerTransferAuditService service = new OwnerTransferAuditService(memberships, threads, tenants,
			audits, new JsonMapper(), manager);

	@Test
	void successfulTransferAuditsAndConflictingTransferDoesNot() {
		UUID threadId = UUID.randomUUID();
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();
		Tenant tenant = new Tenant("acme", "Acme", TenantStatus.ACTIVE);
		Thr thread = Thr.collab(from, "room");
		thread.placeIn(tenant.getId(), UUID.randomUUID());
		when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(threads.findById(threadId)).thenReturn(Optional.of(thread));
		when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
		when(tenants.findByKeyForUpdate(tenant.getKey())).thenReturn(Optional.of(tenant));
		when(memberships.transferOwnership(threadId, from, to)).thenReturn(2);
		service.transfer(threadId, from, to);
		verify(audits).save(any(AuthorizationAudit.class));

		UUID another = UUID.randomUUID();
		when(memberships.transferOwnership(threadId, from, another)).thenReturn(1);
		assertThatThrownBy(() -> service.transfer(threadId, from, another))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ResponseStatusException) error).getStatusCode())
						.isEqualTo(HttpStatus.CONFLICT));
		verify(memberships).transferOwnership(threadId, from, another);
		verify(audits, times(1)).save(any(AuthorizationAudit.class));
	}
}
