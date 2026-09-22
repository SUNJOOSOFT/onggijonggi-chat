package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.auth.keycloak.KeycloakTenantUser;
import com.onggijonggi.api.authz.CutoverValidationResult;
import com.onggijonggi.api.authz.CutoverValidationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Class Name : CutoverValidationPostgresTest.java
 * Description : 절체 사전 검증을 실제 PostgreSQL 위에서 검증한다(DB-TST-050). Keycloak에서 활성인 계정의 현재 Tenant가
 *               `stg_user_cur_tnn`에 기록되고(JPA로 쓴 행이 같은 호출의 JDBC 조회에 보여야 한다), OWNER 불일치와
 *               비OWNER 불일치가 나뉘고, Keycloak에서 비활성이거나 없는 계정은 어느 목록에도 나오지 않는지 확인한다.
 */
class CutoverValidationPostgresTest extends PostgresSpringTestBase {

	@Autowired
	private CutoverValidationService validation;
	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void cleanThreads() {
		jdbc.update("delete from thr"); // 자식과 stg_thr_tnn은 cascade로 함께 지워진다
		jdbc.update("delete from stg_user_cur_tnn");
	}

	@Test
	void separatesOwnerMismatchesFromRevocationCandidatesAndIgnoresAccountsDisabledInKeycloak() {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		UUID owner = user("owner-" + tag);
		UUID member = user("member-" + tag);
		UUID foreign = user("foreign-" + tag);
		UUID disabled = user("disabled-" + tag);
		UUID noAttribute = user("no-attribute-" + tag);

		UUID collab = thread("COLLAB", null);
		member(collab, owner, "OWNER");
		for (UUID user : List.of(member, foreign, disabled, noAttribute)) member(collab, user, "MEMBER");
		UUID direct = thread("DIRECT", owner);
		UUID directOfDisabled = thread("DIRECT", disabled);
		UUID foreignOwned = thread("COLLAB", null);
		member(foreignOwned, foreign, "OWNER");
		UUID directOfNoAttribute = thread("DIRECT", noAttribute);
		UUID unmapped = thread("COLLAB", null);
		member(unmapped, owner, "OWNER");
		for (UUID thread : List.of(collab, direct, directOfDisabled, foreignOwned, directOfNoAttribute)) stage(thread, "acme");

		// disabled 계정은 Keycloak 활성 목록에 없다. noAttribute는 활성이지만 tenant 속성이 없다.
		List<KeycloakTenantUser> enabled = List.of(
				new KeycloakTenantUser("owner-" + tag, Optional.of("acme")),
				new KeycloakTenantUser("member-" + tag, Optional.of("acme")),
				new KeycloakTenantUser("foreign-" + tag, Optional.of("beta")),
				new KeycloakTenantUser("no-attribute-" + tag, Optional.empty()),
				new KeycloakTenantUser("someone-who-never-logged-in-" + tag, Optional.of("not a slug!")));

		CutoverValidationResult result = validation.validate(enabled);

		// 속성이 없거나 slug가 아닌 활성 계정은 추측하지 않고 보고한다.
		assertThat(result.invalidTenantSubjects()).containsExactly("no-attribute-" + tag,
				"someone-who-never-logged-in-" + tag);
		// OWNER 불일치는 절체를 중단시키는 항목이다. 비활성 계정이 owner인 DIRECT 방은 대조하지 않는다.
		assertThat(result.ownerMismatches()).extracting(CutoverValidationResult.OwnerTenantMismatch::threadId)
				.containsExactlyInAnyOrder(foreignOwned, directOfNoAttribute);
		CutoverValidationResult.OwnerTenantMismatch foreignOwner = result.ownerMismatches().stream()
				.filter(value -> value.threadId().equals(foreignOwned)).findFirst().orElseThrow();
		assertThat(foreignOwner.ownerUserId()).isEqualTo(foreign);
		assertThat(foreignOwner.stagedTenantKey()).isEqualTo("acme");
		assertThat(foreignOwner.currentTenantKey()).isEqualTo("beta");
		CutoverValidationResult.OwnerTenantMismatch missingAttribute = result.ownerMismatches().stream()
				.filter(value -> value.threadId().equals(directOfNoAttribute)).findFirst().orElseThrow();
		assertThat(missingAttribute.currentTenantKey()).isNull();
		// OWNER가 아닌 참여자의 불일치는 중단하지 않고 회수 예정으로만 보고한다.
		assertThat(result.participantRevocationCandidates()).extracting(
				CutoverValidationResult.ParticipantTenantMismatch::threadId,
				CutoverValidationResult.ParticipantTenantMismatch::userId)
				.containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(collab, foreign),
						org.assertj.core.groups.Tuple.tuple(collab, noAttribute));
		// staging에 매핑이 없는 Thread는 배포 2 migration을 실패시키므로 드러낸다.
		assertThat(result.unmappedThreadIds()).containsExactly(unmapped);

		// 현재 Tenant는 활성이고 속성이 유효한 계정만 기록한다(비활성·속성 불량은 기록하지 않는다).
		Map<UUID, String> staged = jdbc.query("select user_id, tnn_key from stg_user_cur_tnn", rows -> {
			Map<UUID, String> values = new java.util.HashMap<>();
			while (rows.next()) values.put(rows.getObject(1, UUID.class), rows.getString(2));
			return values;
		});
		assertThat(staged.entrySet().stream().filter(entry -> List.of(owner, member, foreign, disabled, noAttribute)
				.contains(entry.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
				.containsOnly(Map.entry(owner, "acme"), Map.entry(member, "acme"), Map.entry(foreign, "beta"));
	}

	@Test
	void rerunningReplacesThePreviousStagingAndReflectsFixedAttributes() {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		UUID owner = user("rerun-owner-" + tag);
		UUID thread = thread("COLLAB", null);
		member(thread, owner, "OWNER");
		stage(thread, "acme");

		CutoverValidationResult wrong = validation.validate(List.of(new KeycloakTenantUser("rerun-owner-" + tag, Optional.of("beta"))));
		assertThat(wrong.ownerMismatches()).hasSize(1);

		// 운영자가 Keycloak 속성을 고친 뒤 다시 실행하면 이전 기록이 새 값으로 바뀌고 불일치가 사라진다.
		CutoverValidationResult fixed = validation.validate(List.of(new KeycloakTenantUser("rerun-owner-" + tag, Optional.of("acme"))));
		assertThat(fixed.ownerMismatches()).isEmpty();
		assertThat(jdbc.queryForObject("select tnn_key from stg_user_cur_tnn where user_id = ?", String.class, owner)).isEqualTo("acme");
	}

	@Test
	void comparesAccountsThatAreInactiveHereButStillEnabledInKeycloak() {
		// 계정 비활성화는 Keycloak을 끄지 않고 재활성화 경로도 있다. 그런 사람이 DIRECT owner인데 Tenant가 어긋나면
		// 지금 막지 않으면 되살아난 뒤 자기 방을 잃는다. 제외 기준은 Keycloak 상태뿐이다(0001 3.7.4).
		String tag = UUID.randomUUID().toString().substring(0, 8);
		UUID dormant = user("dormant-" + tag);
		jdbc.update("update app_user set status = 'INACTIVE', inactive_at = now() where id = ?", dormant);
		UUID direct = thread("DIRECT", dormant);
		stage(direct, "acme");

		CutoverValidationResult result = validation
				.validate(List.of(new KeycloakTenantUser("dormant-" + tag, Optional.of("beta"))));

		assertThat(result.ownerMismatches()).extracting(CutoverValidationResult.OwnerTenantMismatch::threadId)
				.contains(direct);
		assertThat(jdbc.queryForObject("select tnn_key from stg_user_cur_tnn where user_id = ?", String.class, dormant))
				.isEqualTo("beta");
	}

	private UUID user(String subject) {
		UUID id = UUID.randomUUID();
		jdbc.update("insert into app_user (id, keycloak_subj) values (?, ?)", id, subject);
		return id;
	}

	private UUID thread(String kind, UUID directOwner) {
		UUID id = UUID.randomUUID();
		UUID creator = directOwner != null ? directOwner : user("creator-" + id);
		jdbc.update("insert into thr (id, kind, created_user_id, drc_own_user_id, title) values (?, ?, ?, ?, 't')",
				id, kind, creator, directOwner);
		return id;
	}

	private void member(UUID thread, UUID user, String role) {
		jdbc.update("insert into thr_mbr (id, thr_id, user_id, role, created_by_user_id) values (?, ?, ?, ?, ?)",
				UUID.randomUUID(), thread, user, role, user);
	}

	private void stage(UUID thread, String tenantKey) {
		jdbc.update("insert into stg_thr_tnn (thr_id, tnn_key) values (?, ?)", thread, tenantKey);
	}
}
