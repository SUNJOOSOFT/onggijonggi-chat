package com.onggijonggi.api.authz;

import com.onggijonggi.api.auth.keycloak.KeycloakTenantUser;
import com.onggijonggi.common.authz.StagingUserCurrentTenant;
import com.onggijonggi.common.authz.StagingUserCurrentTenantRepository;
import com.onggijonggi.common.user.AppUser;
import com.onggijonggi.common.user.AppUserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : CutoverValidationService.java
 * Description : 절체 사전 검증. Keycloak에서 활성인 계정의 현재 Tenant를 `stg_user_cur_tnn`에 기록하고, staging의 Thread
 *               Tenant와 대조해 OWNER 불일치(절체 중단)와 비OWNER 참여자 불일치(회수 예정)를 나눠 보고한다(0001 7.0·8.1).
 *               Flyway SQL은 Keycloak을 부를 수 없어서 이 검증이 배포 1 앱의 PLATFORM_ADMIN API로 존재한다.
 *               Keycloak에서 비활성이거나 없는 계정은 속성을 볼 수 없고 로그인도 못 하므로 기록·대조·회수에서 뺀다.
 *               제외 기준은 Keycloak 상태뿐이고 `app_user.status`는 보지 않는다 — 우리 쪽 비활성 계정도 재활성화될 수
 *               있어서, 그 사람이 DIRECT owner면 Tenant 불일치를 지금 잡아야 한다(0001 3.7.4 「절체 대조 대상」).
 */
@Service
public class CutoverValidationService {

	private static final Pattern TENANT_KEY = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

	private final AppUserRepository appUserRepository;
	private final StagingUserCurrentTenantRepository currentTenantRepository;
	private final JdbcTemplate jdbcTemplate;

	public CutoverValidationService(AppUserRepository appUserRepository,
			StagingUserCurrentTenantRepository currentTenantRepository, JdbcTemplate jdbcTemplate) {
		this.appUserRepository = appUserRepository;
		this.currentTenantRepository = currentTenantRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	/** @param enabledKeycloakUsers Keycloak에서 **활성인** 사용자와 그 tenant 속성(없거나 복수면 비어 있다) */
	@Transactional
	public CutoverValidationResult validate(List<KeycloakTenantUser> enabledKeycloakUsers) {
		Map<String, Optional<String>> tenantBySubject = new HashMap<>();
		for (KeycloakTenantUser user : enabledKeycloakUsers) {
			tenantBySubject.put(user.subject(), user.tenant().filter(value -> TENANT_KEY.matcher(value).matches()));
		}
		List<String> invalidSubjects = tenantBySubject.entrySet().stream()
				.filter(entry -> entry.getValue().isEmpty()).map(Map.Entry::getKey).sorted().toList();

		// 대조 대상은 Keycloak에서 활성인 계정이다. 기준을 Keycloak 상태 하나로 두는 이유는, 우리 쪽에서 비활성으로
		// 표시된 계정이라도 Keycloak 속성을 읽을 수 있고 재활성화될 수 있기 때문이다(계정 비활성화는 Keycloak을 끄지
		// 않는다). 그런 사람이 DIRECT owner인데 Tenant가 어긋나면 절체를 막아야 한다 — 되살아난 뒤 자기 방을 잃는다.
		Map<UUID, String> currentTenantByUser = new HashMap<>();
		Set<UUID> comparableUsers = new HashSet<>();
		List<StagingUserCurrentTenant> staged = new ArrayList<>();
		for (AppUser user : appUserRepository.findAll()) {
			Optional<String> tenant = tenantBySubject.get(user.getKeycloakSubj());
			if (tenant == null) continue; // Keycloak에서 비활성이거나 없다 — 대조하지 않는다.
			comparableUsers.add(user.getId());
			if (tenant.isPresent()) {
				currentTenantByUser.put(user.getId(), tenant.get());
				staged.add(new StagingUserCurrentTenant(user.getId(), tenant.get()));
			}
		}
		currentTenantRepository.deleteAllInBatch();
		// 이 트랜잭션 안에서 바로 DB에 내보내 제약 위반을 이 호출에서 드러낸다(staging은 운영자가 이후 backfill 전에 조회한다).
		currentTenantRepository.saveAllAndFlush(staged);

		List<CutoverValidationResult.OwnerTenantMismatch> owners = new ArrayList<>();
		List<CutoverValidationResult.ParticipantTenantMismatch> participants = new ArrayList<>();
		compare(comparableUsers, currentTenantByUser, owners, participants);
		return new CutoverValidationResult(invalidSubjects, owners, participants, unmappedThreads());
	}

	private void compare(Set<UUID> comparableUsers, Map<UUID, String> currentTenantByUser,
			List<CutoverValidationResult.OwnerTenantMismatch> owners,
			List<CutoverValidationResult.ParticipantTenantMismatch> participants) {
		record StagedThread(UUID id, String tenantKey, String kind, UUID directOwner) {
		}
		List<StagedThread> threads = jdbcTemplate.query("""
				select st.thr_id, st.tnn_key, thread.kind, thread.drc_own_user_id
				  from stg_thr_tnn st
				  join thr thread on thread.id = st.thr_id
				 order by st.thr_id
				""", (rows, index) -> new StagedThread(rows.getObject(1, UUID.class), rows.getString(2),
				rows.getString(3), rows.getObject(4, UUID.class)));

		Map<UUID, List<UUID>> activeOwners = new HashMap<>();
		Map<UUID, List<UUID>> activeParticipants = new HashMap<>();
		jdbcTemplate.query("""
				select member.thr_id, member.user_id, member.role
				  from thr_mbr member
				  join stg_thr_tnn st on st.thr_id = member.thr_id
				 where member.status = 'ACTIVE'
				 order by member.thr_id, member.user_id
				""", rows -> {
			UUID threadId = rows.getObject(1, UUID.class);
			UUID userId = rows.getObject(2, UUID.class);
			Map<UUID, List<UUID>> target = "OWNER".equals(rows.getString(3)) ? activeOwners : activeParticipants;
			target.computeIfAbsent(threadId, key -> new ArrayList<>()).add(userId);
		});

		for (StagedThread thread : threads) {
			List<UUID> ownerIds = thread.kind().equals("DIRECT")
					? (thread.directOwner() == null ? List.of() : List.of(thread.directOwner()))
					: activeOwners.getOrDefault(thread.id(), List.of());
			for (UUID owner : ownerIds) {
				if (mismatched(owner, thread.tenantKey(), comparableUsers, currentTenantByUser)) {
					owners.add(new CutoverValidationResult.OwnerTenantMismatch(thread.id(), owner, thread.tenantKey(),
							currentTenantByUser.get(owner)));
				}
			}
			if (thread.kind().equals("COLLAB")) {
				for (UUID participant : activeParticipants.getOrDefault(thread.id(), List.of())) {
					if (mismatched(participant, thread.tenantKey(), comparableUsers, currentTenantByUser)) {
						participants.add(new CutoverValidationResult.ParticipantTenantMismatch(thread.id(), participant,
								thread.tenantKey(), currentTenantByUser.get(participant)));
					}
				}
			}
		}
		owners.sort(Comparator.comparing(CutoverValidationResult.OwnerTenantMismatch::threadId)
				.thenComparing(CutoverValidationResult.OwnerTenantMismatch::ownerUserId));
		participants.sort(Comparator.comparing(CutoverValidationResult.ParticipantTenantMismatch::threadId)
				.thenComparing(CutoverValidationResult.ParticipantTenantMismatch::userId));
	}

	/** 대조 대상 계정이고, 현재 Tenant가 없거나(속성 불량) staging의 Thread Tenant와 다르면 불일치다. */
	private boolean mismatched(UUID userId, String stagedTenantKey, Set<UUID> comparableUsers,
			Map<UUID, String> currentTenantByUser) {
		return comparableUsers.contains(userId) && !stagedTenantKey.equals(currentTenantByUser.get(userId));
	}

	private List<UUID> unmappedThreads() {
		return jdbcTemplate.query("""
				select thread.id from thr thread
				 where not exists (select 1 from stg_thr_tnn st where st.thr_id = thread.id)
				 order by thread.id
				""", (rows, index) -> rows.getObject(1, UUID.class));
	}
}
