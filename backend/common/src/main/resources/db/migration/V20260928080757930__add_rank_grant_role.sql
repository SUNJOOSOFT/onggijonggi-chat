-- 04·DATA
-- 변경 이유: 직급 서열 규칙(rank_grn)도 팀 규칙(wrk_grn)과 같은 역할(VIEWER·CONTRIBUTOR·ADMIN)을 준다. 지금까지는
--           "볼 수 있다" 하나만 뜻해서 "과장 이상은 방도 만든다" 같은 규칙을 적을 수 없었다. 역할은 규칙의 일부라
--           같은 (Tenant, 직급, 노드, 팀) 조건에 역할이 다른 행이 함께 있을 수 있게 unique에 role을 넣는다.
-- 기존 데이터 전제: 기존 행은 기본값 VIEWER가 되어 지금 뜻(보기만)이 그대로다.
-- 이관·중단 조건: 없다.

alter table rank_grn add column role varchar(16) not null default 'VIEWER'
    constraint rank_grn_role_value check (role in ('VIEWER', 'CONTRIBUTOR', 'ADMIN'));

alter table rank_grn drop constraint uq_rank_grn_policy;
alter table rank_grn add constraint uq_rank_grn_policy
    unique nulls not distinct (tnn_id, rank, wrk_node_id, org_unit_id, role);

create or replace function rank_grn_guard()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    if tg_op = 'UPDATE' and (new.tnn_id <> old.tnn_id or new.wrk_node_id <> old.wrk_node_id
                             or new.org_unit_id is distinct from old.org_unit_id) then
        raise exception 'only the rank or role of a rank grant can change';
    end if;
    perform 1 from wrk_node where id = new.wrk_node_id and tnn_id = new.tnn_id and status = 'ACTIVE' and kind <> 'ROOT' for share;
    if not found then
        raise exception 'rank grant requires an active non-ROOT node';
    end if;
    if new.org_unit_id is not null and (tg_op = 'INSERT') then
        -- 대상 행을 FOR SHARE로 잡아 검사 뒤 커밋 전에 org-unit이 비활성화되는 경쟁을 막는다.
        perform 1 from org_unit where id = new.org_unit_id and tnn_id = new.tnn_id and status = 'ACTIVE' for share;
        if not found then
            raise exception 'rank grant requires an active organization unit';
        end if;
    end if;
    new.updated_at := now();
    return new;
end;
$$;
