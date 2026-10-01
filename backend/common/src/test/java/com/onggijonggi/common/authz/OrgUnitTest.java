package com.onggijonggi.common.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Class Name : OrgUnitTest.java
 * Description : 배정이 가리키는 팀을 판정에 쓸 수 있는 조건(isActiveIn)을 확인한다. 같은 Tenant의 ACTIVE 팀만 통과한다.
 */
class OrgUnitTest {

	private final UUID tenant = UUID.randomUUID();

	@Test
	void activeUnitOfTheSameTenantPasses() {
		assertThat(new OrgUnit(tenant, "hr", "인사팀", OrgUnitStatus.ACTIVE).isActiveIn(tenant)).isTrue();
	}

	@Test
	void unitOfAnotherTenantFailsEvenWhenActive() {
		assertThat(new OrgUnit(tenant, "hr", "인사팀", OrgUnitStatus.ACTIVE).isActiveIn(UUID.randomUUID())).isFalse();
	}

	@Test
	void inactiveUnitFailsEvenInItsOwnTenant() {
		assertThat(new OrgUnit(tenant, "hr", "인사팀", OrgUnitStatus.INACTIVE).isActiveIn(tenant)).isFalse();
	}

	@Test
	void reactivatedUnitPassesAgain() {
		OrgUnit unit = new OrgUnit(tenant, "hr", "인사팀", OrgUnitStatus.INACTIVE);
		unit.reconcileStatus(OrgUnitStatus.ACTIVE);

		assertThat(unit.isActiveIn(tenant)).isTrue();
	}
}
