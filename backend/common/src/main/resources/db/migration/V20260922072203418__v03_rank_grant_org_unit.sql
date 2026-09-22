-- 04·DATA
-- 변경 이유: 직급 서열 규칙(rank_grn)에 팀을 선택으로 붙인다. "인사팀의 과장 이상"처럼 팀 안의 관리자급 방을 만들려면
--           직급만으로는 모자란다(다른 팀의 과장 이상도 통과한다). org_unit_id가 비어 있으면 지금처럼 모든 팀이다.
-- 기존 데이터 전제: rank_grn에 행이 있어도 된다. 새 컬럼은 null(모든 팀)로 채워져 기존 규칙의 뜻이 그대로다.
-- 이관·중단 조건: 없다.

alter table rank_grn add column org_unit_id uuid;
alter table rank_grn add constraint fk_rank_grn_org_unit
    foreign key (tnn_id, org_unit_id) references org_unit (tnn_id, id) on delete restrict;

-- 같은 규칙 중복을 막는 유일성에 팀을 넣는다. 팀이 없는 규칙끼리도 중복을 막아야 해서 null을 같은 값으로 본다.
alter table rank_grn drop constraint uq_rank_grn_policy;
alter table rank_grn add constraint uq_rank_grn_policy unique nulls not distinct (tnn_id, rank, wrk_node_id, org_unit_id);

-- 규칙은 ACTIVE이고 ROOT가 아닌 노드에만, 팀이 있으면 ACTIVE 팀에만 만든다. 바꿀 수 있는 것은 rank뿐이다.
create or replace function rank_grn_guard()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    if tg_op = 'UPDATE' and (new.tnn_id <> old.tnn_id or new.wrk_node_id <> old.wrk_node_id
                             or new.org_unit_id is distinct from old.org_unit_id) then
        raise exception 'only the rank of a rank grant can change';
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
