-- 04·DATA: Thread가 놓인 노드는 ACTIVE로 유지되고, 비활성 노드에는 Thread가 놓이지 않게 DB가 보장한다.
-- 애플리케이션은 노드를 끄기 전에 Thread 유무를, Thread를 놓기 전에 노드 상태를 각각 확인하지만 두 확인이
-- 서로 다른 트랜잭션이라 겹치면 비활성 노드에 Thread가 남을 수 있었다. 복합 FK는 행 존재만 보므로 막지 못한다.
-- 기존 데이터 전제: 절체 migration이 Thread를 유효한 ACTIVE 노드에 놓았거나 절체 전 검증이 이를 확인했다.
-- 이관·중단 조건: 이미 비활성 노드에 놓인 Thread가 있어도 이 migration은 실패하지 않는다(새 쓰기부터 적용).

create or replace function thr_node_active_guard() returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    if tg_op = 'UPDATE' and new.wrk_node_id is not distinct from old.wrk_node_id then
        return new;
    end if;
    -- 노드 행을 FOR SHARE로 잡으면, 동시에 이 노드를 끄는 쪽(FOR UPDATE)은 이 트랜잭션이 끝나길 기다렸다가 Thread를 본다.
    perform 1 from wrk_node where id = new.wrk_node_id and status = 'ACTIVE' for share;
    if not found then
        raise exception 'thread requires an active workspace node';
    end if;
    return new;
end;
$$;

create trigger trg_thr_node_active before insert or update of wrk_node_id on thr
for each row execute function thr_node_active_guard();
alter table thr enable always trigger trg_thr_node_active;

create or replace function wrk_node_thr_guard() returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    if old.status = 'ACTIVE' and new.status = 'INACTIVE' then
        -- trg_wrk_node_guard가 먼저 이 행을 FOR UPDATE로 잡는다(이름 순). 그 뒤라 커밋 전 Thread도 새 스냅샷으로 보인다.
        if exists (select 1 from thr where thr.wrk_node_id = old.id) then
            raise exception 'a workspace with threads cannot be deactivated';
        end if;
    end if;
    return new;
end;
$$;

create trigger trg_wrk_node_thr before update of status on wrk_node
for each row execute function wrk_node_thr_guard();
alter table wrk_node enable always trigger trg_wrk_node_thr;
