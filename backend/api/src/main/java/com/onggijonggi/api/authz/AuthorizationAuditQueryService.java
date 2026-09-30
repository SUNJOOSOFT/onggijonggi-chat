package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.AuthorizationActorKind;
import com.onggijonggi.common.authz.AuthorizationAuditEventKind;
import com.onggijonggi.common.authz.AuthorizationAuditTargetKind;
import com.onggijonggi.common.authz.TenantRepository;
import com.onggijonggi.common.authz.WorkspaceNode;
import com.onggijonggi.common.authz.WorkspaceNodeRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Class Name : AuthorizationAuditQueryService.java
 * Description : 03·CORE 권한 변경 감사(authz_adt) 조회(#259). 읽기만 하고 감사 행을 새로 남기지 않는다.
 *               - 일반 ADMIN: 대상 Workspace에 직접 MANAGE가 있어야 하고(DirectManageAuthorizer, 스위치와 무관),
 *                 wrk_node_id가 그 Workspace와 정확히 같은 행만 본다. 하위 Workspace·wrk_node_id가 null인 행은 보지 않는다.
 *               - PLATFORM_ADMIN: 경로의 Tenant 전체를 본다. 비활성 Tenant·Workspace의 과거 기록도 포함한다.
 *               범위(tnn_id·wrk_node_id)는 판정한 뒤 SQL 조건으로 건다 — 모든 행을 읽고 메모리에서 거르지 않는다.
 *               커서를 준 요청도 매번 같은 판정과 범위를 다시 적용한다. 정렬은 (created_at desc, id desc)이고 한 행을 더 읽어
 *               다음 페이지가 있는지 본다. JDBC가 블로킹이라 boundedElastic에서 돈다.
 */
@Service
public class AuthorizationAuditQueryService {

	private static final String COLUMNS = "id, tnn_id, act_kind, act_user_id, act_role_json, evt_kind, trg_kind, trg_ref,"
			+ " wrk_node_id, bfr_json, aft_json, req_id, trc_id, dpl_id, cnf_fgpt, created_at";

	private final NamedParameterJdbcTemplate jdbc;
	private final WorkspaceNodeRepository nodes;
	private final TenantRepository tenants;
	private final DirectManageAuthorizer directManageAuthorizer;
	private final ObjectMapper objectMapper;

	public AuthorizationAuditQueryService(JdbcTemplate jdbcTemplate, WorkspaceNodeRepository nodes, TenantRepository tenants,
			DirectManageAuthorizer directManageAuthorizer, ObjectMapper objectMapper) {
		this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
		this.nodes = nodes;
		this.tenants = tenants;
		this.directManageAuthorizer = directManageAuthorizer;
		this.objectMapper = objectMapper;
	}

	/** 없는 Workspace는 404, 비활성이거나 직접 MANAGE가 없으면 403이다. */
	public Mono<AuthorizationAuditPage> forWorkspace(String subject, UUID workspaceId, AuthorizationAuditQuery query) {
		return Mono.fromCallable(() -> {
			WorkspaceNode node = nodes.findById(workspaceId)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
			if (!directManageAuthorizer.hasDirectManage(subject, node)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
			return page(node.getTenantId(), node.getId(), query);
		}).subscribeOn(Schedulers.boundedElastic());
	}

	/** control plane 전용. 없는 Tenant는 404이고, 상태와 무관하게 조회한다. */
	public Mono<AuthorizationAuditPage> forTenant(UUID tenantId, AuthorizationAuditQuery query) {
		return Mono.fromCallable(() -> {
			if (tenants.findById(tenantId).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			return page(tenantId, null, query);
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private AuthorizationAuditPage page(UUID tenantId, UUID workspaceNodeId, AuthorizationAuditQuery query) {
		StringBuilder sql = new StringBuilder("select ").append(COLUMNS).append(" from authz_adt where tnn_id = :tenantId");
		MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
		if (workspaceNodeId != null) condition(sql, params, "wrk_node_id = :nodeId", "nodeId", workspaceNodeId);
		if (query.from() != null) condition(sql, params, "created_at >= :from", "from", utc(query.from()));
		if (query.to() != null) condition(sql, params, "created_at < :to", "to", utc(query.to()));
		if (query.eventKind() != null) condition(sql, params, "evt_kind = :eventKind", "eventKind", query.eventKind().name());
		if (query.targetKind() != null) condition(sql, params, "trg_kind = :targetKind", "targetKind", query.targetKind().name());
		if (query.actorKind() != null) condition(sql, params, "act_kind = :actorKind", "actorKind", query.actorKind().name());
		if (query.actorUserId() != null) condition(sql, params, "act_user_id = :actorUserId", "actorUserId", query.actorUserId());
		if (query.requestId() != null) condition(sql, params, "req_id = :requestId", "requestId", query.requestId());
		if (query.cursor() != null) {
			// 행 비교로 쓴다 — OR로 풀면 인덱스 (…, created_at desc, id desc)의 순서를 그대로 이어 읽지 못할 수 있다.
			sql.append(" and (created_at, id) < (:cursorAt, :cursorId)");
			params.addValue("cursorAt", utc(query.cursor().createdAt())).addValue("cursorId", query.cursor().id());
		}
		sql.append(" order by created_at desc, id desc limit :fetch");
		params.addValue("fetch", query.limit() + 1);

		List<AuthorizationAuditView> rows = new ArrayList<>(jdbc.query(sql.toString(), params, (rs, rowNum) -> view(rs)));
		if (rows.size() <= query.limit()) return new AuthorizationAuditPage(rows, null);
		rows.remove(rows.size() - 1);
		AuthorizationAuditView last = rows.get(rows.size() - 1);
		return new AuthorizationAuditPage(rows, new AuthorizationAuditCursor(last.createdAt(), last.id()).encode());
	}

	private static void condition(StringBuilder sql, MapSqlParameterSource params, String clause, String name, Object value) {
		sql.append(" and ").append(clause);
		params.addValue(name, value);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private AuthorizationAuditView view(ResultSet rs) throws SQLException {
		return new AuthorizationAuditView(rs.getObject("id", UUID.class), rs.getObject("tnn_id", UUID.class),
				AuthorizationActorKind.valueOf(rs.getString("act_kind")), rs.getObject("act_user_id", UUID.class),
				json(rs.getString("act_role_json")), AuthorizationAuditEventKind.valueOf(rs.getString("evt_kind")),
				AuthorizationAuditTargetKind.valueOf(rs.getString("trg_kind")), json(rs.getString("trg_ref")),
				rs.getObject("wrk_node_id", UUID.class), json(rs.getString("bfr_json")), json(rs.getString("aft_json")),
				rs.getString("req_id"), rs.getString("trc_id"), rs.getString("dpl_id"), rs.getString("cnf_fgpt"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private JsonNode json(String value) {
		return value == null ? null : objectMapper.readTree(value);
	}
}
