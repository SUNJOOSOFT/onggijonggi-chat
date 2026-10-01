-- 04·DATA: 기존 Thread와 운영 보조 행을 단일 ACTIVE Tenant로 귀속한다.
-- 기존 Thread가 없는 신규 DB는 Tenant가 없어도 스키마 설치를 계속한다.
-- 기존 Thread가 있으면 ACTIVE Tenant와 ACTIVE COMMON이 각각 하나여야 한다.
-- 이미 배치된 유효한 COLLAB Workspace는 보존하고, 모호한 배치는 덮어쓰지 않고 중단한다.

do $$
declare
    active_count bigint;
    target_tenant uuid;
    common_workspace uuid;
begin
    if exists (select 1 from thr) then
        select count(*) into active_count from tnn where status = 'ACTIVE';
        if active_count <> 1 then
            raise exception 'thread cutover requires exactly one ACTIVE tenant, found %; run the RBAC bootstrap first: start the app once with spring.flyway.target=20260929055051721 (SPRING_FLYWAY_TARGET) so it creates the tenant, then migrate again', active_count;
        end if;
        select id into target_tenant from tnn where status = 'ACTIVE';
        select count(*) into active_count
          from wrk_node node
          join wrk_node root on root.id = node.prn_id and root.tnn_id = node.tnn_id
         where node.tnn_id = target_tenant and node.kind = 'COMMON' and node.status = 'ACTIVE'
           and root.kind = 'ROOT' and root.status = 'ACTIVE';
        if active_count <> 1 then
            raise exception 'thread cutover requires exactly one ACTIVE COMMON workspace';
        end if;
        select node.id into common_workspace
          from wrk_node node
          join wrk_node root on root.id = node.prn_id and root.tnn_id = node.tnn_id
         where node.tnn_id = target_tenant and node.kind = 'COMMON' and node.status = 'ACTIVE'
           and root.kind = 'ROOT' and root.status = 'ACTIVE';
        if exists (select 1 from thr where tnn_id is not null and tnn_id <> target_tenant) then
            raise exception 'thread tenant conflicts with the single ACTIVE tenant';
        end if;
        if exists (select 1 from thr where kind = 'DIRECT' and wrk_node_id is not null
                   and wrk_node_id <> common_workspace) then
            raise exception 'DIRECT thread is placed outside COMMON';
        end if;
        if exists (select 1 from thr thread left join wrk_node node on node.id = thread.wrk_node_id
                    where thread.kind = 'COLLAB' and thread.wrk_node_id is not null
                      and (node.id is null or node.tnn_id <> target_tenant or node.status <> 'ACTIVE'
                           or node.kind = 'ROOT')) then
            raise exception 'COLLAB thread has an invalid workspace placement';
        end if;
        update thr set tnn_id = target_tenant,
                       wrk_node_id = coalesce(wrk_node_id, common_workspace)
         where tnn_id is null or wrk_node_id is null;
    end if;
end;
$$;
-- 완료된 메시지는 평소 변경 불가다. 이 migration 동안 null Tenant만 부모 값으로 채울 수 있게
-- 예외를 한정하고, 같은 Flyway 트랜잭션 안에서 원래 guard를 복원한다.
create or replace function msg_block_terminal_mutation() returns trigger
language plpgsql as $$
begin
    if tg_op = 'UPDATE' and old.status <> 'PENDING'
       and not (old.tnn_id is null and new.tnn_id is not null
                and to_jsonb(new) - 'tnn_id' = to_jsonb(old) - 'tnn_id') then
        raise exception 'completed message cannot be modified during cutover: id=%', old.id
            using errcode = 'check_violation';
    end if;
    if tg_op = 'DELETE' and pg_trigger_depth() <= 1 then
        raise exception 'message cannot be deleted individually: id=%', old.id
            using errcode = 'check_violation';
    end if;
    return coalesce(new, old);
end;
$$;
do $$
declare
    child_table text;
    conflicting boolean;
begin
    foreach child_table in array array['thr_mbr', 'msg', 'thr_idm_key', 'msg_idm_key', 'thr_inv', 'thr_risk_crs'] loop
        execute format('select exists (select 1 from %I child join thr thread on thread.id = child.thr_id
                         where child.tnn_id is not null and child.tnn_id <> thread.tnn_id)', child_table)
            into conflicting;
        if conflicting then
            raise exception '% contains a tenant different from its thread', child_table;
        end if;
        execute format('update %I child set tnn_id = thread.tnn_id from thr thread
                         where thread.id = child.thr_id and child.tnn_id is null', child_table);
        execute format('select exists (select 1 from %I where tnn_id is null)', child_table)
            into conflicting;
        if conflicting then
            raise exception '% contains an unassigned tenant after backfill', child_table;
        end if;
    end loop;
end;
$$;

create or replace function msg_block_terminal_mutation() returns trigger
language plpgsql as $$
begin
    if tg_op = 'UPDATE' and old.status <> 'PENDING' then
        raise exception '완료된 메시지는 수정할 수 없습니다: id=%', old.id
            using errcode = 'check_violation';
    end if;
    if tg_op = 'DELETE' and pg_trigger_depth() <= 1 then
        raise exception '메시지는 개별 삭제할 수 없습니다: id=%', old.id
            using errcode = 'check_violation';
    end if;
    return coalesce(new, old);
end;
$$;
