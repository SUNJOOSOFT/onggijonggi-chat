package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.api.authz.OrgUnitMemberService;
import com.onggijonggi.api.authz.OrgUnitMemberService.Actor;
import com.onggijonggi.api.authz.OrgUnitMemberService.Change;
import com.onggijonggi.api.authz.OrgUnitMemberService.Outcome;
import com.onggijonggi.common.authz.AuthorizationActorKind;
import com.onggijonggi.common.authz.AuthorizationAudit;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditRepository;
import com.onggijonggi.common.authz.AuthorizationAuditTargetKind;
import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.OrgUnitStatus;
import com.onggijonggi.common.authz.Rank;
import com.onggijonggi.common.authz.Tenant;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.TenantStatus;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : OrgUnitMemberServicePostgresTest.java
 * Description : 배정 서비스를 실제 PostgreSQL 16에서 끝까지 검증한다 — 배정·변경·해제가 이력(authz_adt)과 함께 남는지,
 *               바뀐 것이 없으면 이력도 남지 않는지, 여러 건 중 하나가 틀리면 전부 되돌리는지, 행위자(SYSTEM·USER)가 맞는지.
 *               테스트마다 고유한 Tenant와 subject를 쓴다.
 */
class OrgUnitMemberServicePostgresTest extends PostgresSpringTestBase {

	@Autowired
	private OrgUnitMemberService service;
	@Autowired
	private OrgUnitMemberRepository members;
	@Autowired
	private TenantRepository tenants;
	@Autowired
	private OrgUnitRepository orgUnits;
	@Autowired
	private AuthorizationAuditRepository audits;
	@Autowired
	private AppUserRepository appUsers;
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private DataSource dataSource;

	private Tenant tenant;
	private OrgUnit hr;
	private OrgUnit fin;
	private String kim;

	@BeforeEach
	void setUp() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		tenant = tenants.saveAndFlush(new Tenant("members-" + suffix, "Members", TenantStatus.ACTIVE));
		hr = orgUnits.saveAndFlush(new OrgUnit(tenant.getId(), "hr", "인사팀", OrgUnitStatus.ACTIVE));
		fin = orgUnits.saveAndFlush(new OrgUnit(tenant.getId(), "fin", "재무팀", OrgUnitStatus.ACTIVE));
		kim = "sub-kim-" + suffix;
	}

	@Test
	void assignChangeAndUnassignEachLeaveOneAuditRow() {
		Actor importer = Actor.system("import:abc");

		assertThat(service.apply(Change.assign(kim, hr.getId(), Rank.S), importer).outcome()).isEqualTo(Outcome.ASSIGNED);
		assertThat(service.apply(Change.assign(kim, fin.getId(), Rank.K), importer).outcome()).isEqualTo(Outcome.CHANGED);
		assertThat(members.findBySubject(kim)).singleElement()
				.satisfies(member -> {
					assertThat(member.getOrgUnitId()).isEqualTo(fin.getId());
					assertThat(member.getRank()).isEqualTo(Rank.K);
				});
		assertThat(service.apply(Change.unassign(kim), importer).outcome()).isEqualTo(Outcome.UNASSIGNED);
		assertThat(members.findBySubject(kim)).isEmpty();

		List<AuthorizationAudit> rows = memberAudits();
		assertThat(rows).extracting(AuthorizationAudit::getEventKind).containsExactly(AuthorizationAuditEventKind.MEMBER_ASSIGNED,
				AuthorizationAuditEventKind.MEMBER_CHANGED, AuthorizationAuditEventKind.MEMBER_UNASSIGNED);
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.getActorKind()).isEqualTo(AuthorizationActorKind.SYSTEM);
			assertThat(row.getTargetKind()).isEqualTo(AuthorizationAuditTargetKind.MEMBER);
			assertThat(row.getRequestId()).isEqualTo("import:abc");
			assertThat(objectMapper.readTree(row.getTargetRef()).get("subj").asString()).isEqualTo(kim);
		});
		// 변경 행은 전후를 팀 key와 함께 남긴다.
		AuthorizationAudit changed = rows.get(1);
		assertThat(objectMapper.readTree(changed.getBeforeJson()).get("org_unit_key").asString()).isEqualTo("hr");
		assertThat(objectMapper.readTree(changed.getAfterJson()).get("org_unit_key").asString()).isEqualTo("fin");
		assertThat(objectMapper.readTree(changed.getAfterJson()).get("rank").asString()).isEqualTo("K");
	}

	@Test
	void nothingChangedMeansNoAuditRow() {
		Actor importer = Actor.system("import:same");
		service.apply(Change.assign(kim, hr.getId(), Rank.TL), importer);

		assertThat(service.apply(Change.assign(kim, hr.getId(), Rank.TL), importer).outcome()).isEqualTo(Outcome.UNCHANGED);
		assertThat(service.apply(Change.unassign("nobody-" + kim), importer).outcome()).isEqualTo(Outcome.UNCHANGED);
		assertThat(memberAudits()).hasSize(1);
	}

	@Test
	void aChangeFromTheDemoScreenRecordsTheUserAsActor() {
		AppUser admin = appUsers.saveAndFlush(new AppUser("demo-admin-" + kim));

		service.apply(Change.assign(kim, hr.getId(), Rank.B), Actor.user(admin.getId(), "demo:1"));

		assertThat(memberAudits()).singleElement().satisfies(row -> {
			assertThat(row.getActorKind()).isEqualTo(AuthorizationActorKind.USER);
			assertThat(row.getActorUserId()).isEqualTo(admin.getId());
		});
	}

	@Test
	void oneBadChangeRollsBackTheWholeBatch() {
		new JdbcTemplate(dataSource).update("update org_unit set status = 'INACTIVE', inactive_at = now() where id = ?", fin.getId());
		String lee = "sub-lee-" + kim;

		assertThatThrownBy(() -> service.applyAll(List.of(Change.assign(kim, hr.getId(), Rank.S), Change.assign(lee, fin.getId(), Rank.S)),
				Actor.system("import:bad"))).isInstanceOf(OrgUnitMemberService.InvalidChangeException.class).hasMessageContaining("비활성 팀");

		assertThat(members.findBySubject(kim)).isEmpty();
		assertThat(members.findBySubject(lee)).isEmpty();
		assertThat(memberAudits()).isEmpty();
	}

	@Test
	void theSamePersonTwiceInOneBatchIsRejectedBeforeTouchingTheDatabase() {
		assertThatThrownBy(() -> service.applyAll(List.of(Change.assign(kim, hr.getId(), Rank.S), Change.assign(kim, fin.getId(), Rank.K)),
				Actor.system("import:dup"))).isInstanceOf(OrgUnitMemberService.InvalidChangeException.class).hasMessageContaining("두 번");

		assertThat(members.findBySubject(kim)).isEmpty();
	}

	private List<AuthorizationAudit> memberAudits() {
		return audits.findByTenantIdOrderByCreatedAtAscIdAsc(tenant.getId()).stream()
				.filter(row -> row.getTargetKind() == AuthorizationAuditTargetKind.MEMBER)
				.toList();
	}
}
