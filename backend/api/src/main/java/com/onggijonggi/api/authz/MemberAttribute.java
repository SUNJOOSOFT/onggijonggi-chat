package com.onggijonggi.api.authz;

import com.onggijonggi.common.authz.Rank;
import java.util.UUID;

/**
 * Class Name : MemberAttribute.java
 * Description : 03·CORE 사람 한 명의 팀·직급 속성. 사람은 Keycloak subject(sub)로 가리킨다. casbin-server의 p2 행
 *               (subject, org_unit, rank)이 정본이고 우리 DB에는 두지 않는다. tenantId는 p2에 없고 팀(org_unit)이 속한
 *               Tenant를 DB 조직 구조에서 채운다 — 팀은 한 Tenant에만 속한다.
 */
public record MemberAttribute(String subject, UUID tenantId, UUID orgUnitId, Rank rank) {
}
