-- 04·DATA
-- 변경 이유: 사람의 팀·직급 배정(org_unit_mbr) 변경을 권한 감사(authz_adt)에 남긴다. 허용 이벤트에 배정 추가·변경·해제를,
--           대상 종류에 MEMBER를 더한다. 배정은 CSV 임포트(SYSTEM)와 데모 배정 화면(USER)이 같은 배정 서비스로 바꾼다.
-- 기존 데이터 전제: 기존 행은 모두 기존 허용값이라 새 CHECK를 그대로 통과한다(허용값을 넓히기만 한다).
-- 이관·중단 조건: 없다.

alter table authz_adt drop constraint authz_adt_evt_value;
alter table authz_adt add constraint authz_adt_evt_value check (evt_kind in (
    'TENANT_CREATED', 'TENANT_RENAMED', 'TENANT_DEACTIVATED', 'TENANT_REACTIVATED',
    'ORG_UNIT_CREATED', 'ORG_UNIT_RENAMED', 'ORG_UNIT_DEACTIVATED', 'ORG_UNIT_REACTIVATED',
    'NODE_CREATED', 'NODE_RENAMED', 'NODE_REPARENTED', 'NODE_DEACTIVATED', 'NODE_REACTIVATED',
    'POLICY_ADDED', 'POLICY_REMOVED', 'POLICY_REPLACED', 'THREAD_MOVED', 'OWNER_TRANSFERRED',
    'TENANT_DRIFT_DETECTED',
    'MEMBER_ASSIGNED', 'MEMBER_CHANGED', 'MEMBER_UNASSIGNED'));

alter table authz_adt drop constraint authz_adt_target_value;
alter table authz_adt add constraint authz_adt_target_value
    check (trg_kind in ('TENANT', 'ORG_UNIT', 'WORKSPACE', 'POLICY', 'THREAD', 'MEMBER'));
