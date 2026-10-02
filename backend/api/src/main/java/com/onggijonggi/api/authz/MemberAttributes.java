package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.OrgUnit;
import com.onggijonggi.common.authz.OrgUnitRepository;
import com.onggijonggi.common.authz.Rank;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Class Name : MemberAttributes.java
 * Description : 03·CORE 사람의 팀·직급 속성을 casbin-server(p2)에서 읽는다. 우리 DB에는 사람 속성이 없다.
 *               casbin 프로필이 꺼져 있으면(기본 배포) 넣은 사람이 없으므로 늘 비어 있다.
 *               적재 전이거나 casbin이 재시작해 잃었으면 한 번 다시 적재하고 다시 읽는다. 그래도 못 읽으면
 *               {@link MemberAttributesUnavailableException}이다 — "속성이 없는 사람"(빈 결과)과 구분한다.
 *               p2에는 Tenant가 없어 팀이 속한 Tenant를 DB 조직 구조에서 채운다. 조직 구조에 없는 팀의 행은 버린다.
 *               DB·gRPC가 블로킹이라 boundedElastic에서 부른다.
 */
@Service
public class MemberAttributes {

	private final CasbinRuleLoader loader;
	private final CasbinClient client;
	private final OrgUnitRepository orgUnits;

	public MemberAttributes(CasbinRuleLoader loader, CasbinClient client, OrgUnitRepository orgUnits) {
		this.loader = loader;
		this.client = client;
		this.orgUnits = orgUnits;
	}

	/** 그 사람의 속성. 겸직이 없어 지금은 많아야 하나지만 목록으로 돌려준다 — 겸직이 생겨도 판정 코드가 목록을 도는 모양 그대로 쓴다. */
	public List<MemberAttribute> findBySubject(String subject) {
		return read(() -> client.memberRowsBySubject(subject));
	}

	/** 그 팀 사람들의 속성. */
	public List<MemberAttribute> findByOrgUnit(UUID orgUnitId) {
		return read(() -> client.memberRowsByOrgUnit(orgUnitId.toString()));
	}

	public List<MemberAttribute> findAll() {
		return read(client::allMemberRows);
	}

	private List<MemberAttribute> read(Supplier<Optional<List<List<String>>>> query) {
		if (!loader.isActive()) return List.of();
		loader.ensureLoaded();
		Optional<List<List<String>>> rows = query.get();
		if (rows.isEmpty() && !client.isLoaded()) {
			// 서버가 재시작해 잃었으면(조회가 적재 상태를 비운다) 한 번만 다시 넣고 다시 읽는다.
			loader.ensureLoaded();
			rows = query.get();
		}
		if (rows.isEmpty()) throw new MemberAttributesUnavailableException();
		return toAttributes(rows.get());
	}

	private List<MemberAttribute> toAttributes(List<List<String>> rows) {
		if (rows.isEmpty()) return List.of();
		Map<UUID, UUID> tenantByOrgUnit = new HashMap<>();
		for (OrgUnit unit : orgUnits.findAll()) tenantByOrgUnit.put(unit.getId(), unit.getTenantId());
		List<MemberAttribute> attributes = new ArrayList<>();
		for (List<String> row : rows) {
			UUID orgUnitId = UUID.fromString(row.get(1));
			UUID tenantId = tenantByOrgUnit.get(orgUnitId);
			if (tenantId == null) continue;
			attributes.add(new MemberAttribute(row.get(0), tenantId, orgUnitId, Rank.valueOf(row.get(2))));
		}
		return attributes;
	}
}
