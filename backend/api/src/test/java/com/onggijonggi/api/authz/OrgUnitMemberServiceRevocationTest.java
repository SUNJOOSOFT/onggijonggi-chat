package com.onggijonggi.api.authz;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onggijonggi.api.authz.OrgUnitMemberService.Actor;
import com.onggijonggi.api.authz.OrgUnitMemberService.Change;
import com.onggijonggi.api.chat.CollabAuthorizationRevoker;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Class Name : OrgUnitMemberServiceRevocationTest.java
 * Description : 조직·직급이 바뀌면 커밋 뒤 그 사람의 협업방 권한을 거두는지 검증한다(#299). 권한이 줄 수 있는 변경(팀·직급
 *               변경, 배정 해제)에만 거두고, 새 배정이나 바뀐 것이 없을 때, 변경이 거부됐을 때는 거두지 않는다.
 *               실제 저장은 OrgUnitMemberServicePostgresTest가 확인하므로 여기서는 저장소를 목으로 둔다.
 */
class OrgUnitMemberServiceRevocationTest {

	private static final String SUBJECT = "sub-kim";

	private final OrgUnitMemberRepository members = mock(OrgUnitMemberRepository.class);
	private final OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
	private final AuthorizationAuditRepository audits = mock(AuthorizationAuditRepository.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private final CollabAuthorizationRevoker revoker = mock(CollabAuthorizationRevoker.class);
	private final OrgUnitMemberService service = new OrgUnitMemberService(members, orgUnits, audits, new JsonMapper(),
			transactions, revoker);

	private final UUID tenantId = UUID.randomUUID();
	private OrgUnit hr;
	private OrgUnit finance;

	@BeforeEach
	void setUp() {
		hr = new OrgUnit(tenantId, "hr", "인사팀", OrgUnitStatus.ACTIVE);
		finance = new OrgUnit(tenantId, "finance", "재무팀", OrgUnitStatus.ACTIVE);
		when(orgUnits.findById(hr.getId())).thenReturn(Optional.of(hr));
		when(orgUnits.findById(finance.getId())).thenReturn(Optional.of(finance));
	}

	@Test
	void changingTheTeamRevokesCollabAccessAfterTheCommit() {
		assigned(hr, Rank.K);

		service.apply(Change.assign(SUBJECT, finance.getId(), Rank.K), Actor.system("import:move"));

		// 커밋 전에 거두면 다른 스레드가 옛 배정으로 다시 들어올 수 있다. 커밋 뒤에 거둔다.
		InOrder order = inOrder(transactions, revoker);
		order.verify(transactions).commit(any());
		order.verify(revoker).revoke(SUBJECT);
	}

	@Test
	void changingOnlyTheRankAlsoRevokes() {
		assigned(hr, Rank.K);

		service.apply(Change.assign(SUBJECT, hr.getId(), Rank.D), Actor.system("import:rank"));

		verify(revoker).revoke(SUBJECT);
	}

	@Test
	void unassigningRevokes() {
		assigned(hr, Rank.K);

		service.apply(Change.unassign(SUBJECT), Actor.system("import:unassign"));

		verify(revoker).revoke(SUBJECT);
	}

	@Test
	void aNewAssignmentOrNoChangeDoesNotRevoke() {
		when(members.findBySubject(SUBJECT)).thenReturn(List.of());
		service.apply(Change.assign(SUBJECT, hr.getId(), Rank.K), Actor.system("import:new"));

		assigned(hr, Rank.K);
		service.apply(Change.assign(SUBJECT, hr.getId(), Rank.K), Actor.system("import:same"));

		verify(revoker, never()).revoke(any());
	}

	@Test
	void aRejectedCrossTenantMoveDoesNotRevoke() {
		assigned(hr, Rank.K);
		OrgUnit elsewhere = new OrgUnit(UUID.randomUUID(), "elsewhere", "다른 회사 팀", OrgUnitStatus.ACTIVE);
		when(orgUnits.findById(elsewhere.getId())).thenReturn(Optional.of(elsewhere));

		assertThatThrownBy(() -> service.apply(Change.assign(SUBJECT, elsewhere.getId(), Rank.K), Actor.system("import:move")))
				.isInstanceOf(OrgUnitMemberService.InvalidChangeException.class);
		verify(revoker, never()).revoke(any());
	}

	@Test
	void aFailedRevocationDoesNotSkipLaterSubjectsAndIsReportedAfterCommit() {
		assigned(hr, Rank.K);
		String otherSubject = "sub-lee";
		when(members.findBySubject(otherSubject)).thenReturn(List.of(
				new OrgUnitMember(tenantId, hr.getId(), otherSubject, Rank.K)));
		doThrow(new IllegalStateException("eviction failed")).when(revoker).revoke(SUBJECT);

		assertThatThrownBy(() -> service.applyAll(List.of(
				Change.assign(SUBJECT, finance.getId(), Rank.K),
				Change.assign(otherSubject, finance.getId(), Rank.K)), Actor.system("import:batch")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(SUBJECT)
				.hasMessageContaining("저장됐지만");

		InOrder order = inOrder(transactions, revoker);
		order.verify(transactions).commit(any());
		order.verify(revoker).revoke(SUBJECT);
		order.verify(revoker).revoke(otherSubject);
	}

	private void assigned(OrgUnit unit, Rank rank) {
		when(members.findBySubject(SUBJECT)).thenReturn(List.of(new OrgUnitMember(tenantId, unit.getId(), SUBJECT, rank)));
	}
}
