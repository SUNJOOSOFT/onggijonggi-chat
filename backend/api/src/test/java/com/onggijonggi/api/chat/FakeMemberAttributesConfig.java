package com.onggijonggi.api.chat;

import com.onggijonggi.api.authz.MemberAttribute;
import com.onggijonggi.api.authz.MemberAttributes;
import com.onggijonggi.common.authz.Rank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Class Name : FakeMemberAttributesConfig.java
 * Description : 사람 속성(MemberAttributes)을 casbin-server 없이 메모리로 돌려주는 빈으로 교체한다. 사람 속성은 Casbin(p2)에만
 *               있고 DB에 없어서, casbin 서버를 띄우지 않는 Postgres 통합 테스트는 이 가짜에 배정한다. 실제 적재·조회는
 *               CasbinServerContainerTest가 확인한다.
 */
@TestConfiguration
class FakeMemberAttributesConfig {

	@Bean
	@Primary
	FakeMemberAttributes fakeMemberAttributes() {
		return new FakeMemberAttributes();
	}

	static class FakeMemberAttributes extends MemberAttributes {

		private final Map<String, MemberAttribute> bySubject = new ConcurrentHashMap<>();

		FakeMemberAttributes() {
			super(null, null, null);
		}

		void assign(String subject, UUID tenantId, UUID orgUnitId, Rank rank) {
			bySubject.put(subject, new MemberAttribute(subject, tenantId, orgUnitId, rank));
		}

		void unassign(String subject) {
			bySubject.remove(subject);
		}

		@Override
		public List<MemberAttribute> findBySubject(String subject) {
			MemberAttribute member = bySubject.get(subject);
			return member == null ? List.of() : List.of(member);
		}

		@Override
		public List<MemberAttribute> findByOrgUnit(UUID orgUnitId) {
			return bySubject.values().stream().filter(member -> member.orgUnitId().equals(orgUnitId)).toList();
		}

		@Override
		public List<MemberAttribute> findAll() {
			return List.copyOf(bySubject.values());
		}
	}
}
