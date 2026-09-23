package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.OrgUnitMember;
import com.onggijonggi.common.authz.OrgUnitMemberRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeKind;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import com.onggijonggi.common.authz.WorkspaceNodeStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : WorkspaceAuthorizer.java
 * Description : 03·CORE "이 사람이 이 workspace를 볼 수 있나" 판정 API. 볼 수 있으면 그 workspace에 방도 만들 수 있다.
 *               권한 판정(app.rbac.enforce)이 꺼져 있으면 늘 true다 — 끄면 지금과 똑같이 동작해야 한다.
 *               켜져 있으면:
 *               - ACTIVE COMMON은 누구나 본다(미배정자도 1:1 채팅을 쓴다).
 *               - 배정(org_unit_mbr)이 없으면 Casbin에 묻지 않고 거부한다 — 서열 0 같은 값으로 넘기면 서열 규칙이 열린다.
 *               - 배정마다 팀·서열을 속성으로 넘겨 묻고 하나라도 통과하면 허용한다. 지금은 겸직이 없어 배정이 늘 하나지만,
 *                 겸직이 생겨도 이 모양 그대로 쓴다.
 *               - Casbin 오류·시간 초과·적재 실패는 모두 거부다.
 *               DB와 gRPC가 블로킹이라 boundedElastic에서 돈다.
 */
@Service
public class WorkspaceAuthorizer {

	private final RbacProperties rbacProperties;
	private final WorkspaceNodeRepository nodes;
	private final OrgUnitMemberRepository members;
	private final CasbinRuleLoader loader;
	private final CasbinClient client;
	private final ObjectMapper objectMapper;

	public WorkspaceAuthorizer(RbacProperties rbacProperties, WorkspaceNodeRepository nodes, OrgUnitMemberRepository members,
			CasbinRuleLoader loader, CasbinClient client, ObjectMapper objectMapper) {
		this.rbacProperties = rbacProperties;
		this.nodes = nodes;
		this.members = members;
		this.loader = loader;
		this.client = client;
		this.objectMapper = objectMapper;
	}

	public Mono<Boolean> canView(String subject, UUID workspaceNodeId) {
		if (!rbacProperties.isEnforce()) return Mono.just(true);
		return Mono.fromCallable(() -> canViewBlocking(subject, workspaceNodeId)).subscribeOn(Schedulers.boundedElastic());
	}

	private boolean canViewBlocking(String subject, UUID workspaceNodeId) {
		Optional<WorkspaceNode> node = nodes.findById(workspaceNodeId);
		if (node.isEmpty() || node.get().getStatus() != WorkspaceNodeStatus.ACTIVE) return false;
		if (node.get().getKind() == WorkspaceNodeKind.COMMON) return true;
		List<OrgUnitMember> assignments = members.findBySubject(subject);
		if (assignments.isEmpty()) return false;
		loader.ensureLoaded();
		for (OrgUnitMember assignment : assignments) {
			String attributes = attributes(assignment);
			if (client.enforce(attributes, workspaceNodeId.toString(), CasbinPolicy.VIEW)) return true;
			// 서버가 재시작해 규칙을 잃었으면 한 번만 다시 넣고 다시 묻는다. 그래도 안 되면 거부다.
			if (!client.isLoaded()) {
				loader.ensureLoaded();
				if (client.enforce(attributes, workspaceNodeId.toString(), CasbinPolicy.VIEW)) return true;
			}
		}
		return false;
	}

	/** r.sub JSON. 서열은 숫자로 넘긴다 — 문자열이면 casbin-server가 비교에서 오류를 낸다. */
	private String attributes(OrgUnitMember assignment) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put(CasbinPolicy.ORG_UNIT, assignment.getOrgUnitId().toString());
		attributes.put(CasbinPolicy.RANK, assignment.getRank().order());
		return objectMapper.writeValueAsString(attributes);
	}
}
