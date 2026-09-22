-- 04-DATA: phase 1 nullable tenant and workspace columns for existing chat rows.

alter table thr add column tnn_id uuid;
alter table thr add column wrk_node_id uuid;
alter table thr_mbr add column tnn_id uuid;
alter table msg add column tnn_id uuid;
alter table thr_idm_key add column tnn_id uuid;
alter table msg_idm_key add column tnn_id uuid;
alter table thr_inv add column tnn_id uuid;
alter table thr_risk_crs add column tnn_id uuid;
alter table thr_inv add column pnd_rsn varchar(32);

alter table thr_inv add constraint thr_inv_pending_reason_only
    check (status = 'PENDING' or pnd_rsn is null);
alter table thr_inv add constraint thr_inv_pending_reason_value
    check (pnd_rsn is null or pnd_rsn = 'NO_ACCESS');

create index ix_thr_inv_pending_subj on thr_inv (subj)
    where status = 'PENDING';

-- wrk_node의 reparent·비활성화 검사와 bootstrap reconcile이 "이 노드에 Thread가 있나"를 노드마다 묻는다.
-- 인덱스가 없으면 그때마다 thr 전체를 훑는다. 절체 전에는 대부분 null이라 부분 인덱스로 둔다.
create index ix_thr_wrk_node on thr (wrk_node_id)
    where wrk_node_id is not null;

-- 자식 행의 tnn_id는 코드가 아니라 DB가 Thread에서 복사한다. 절체 뒤 NOT NULL과 (tnn_id, thr_id) 복합 FK가 걸려도
-- 자식을 저장하는 경로(참여자·메시지·idempotency key·초대·위험 검사 커서)를 하나씩 고칠 필요가 없고,
-- tnn_id를 빠뜨린 경로가 구조상 생기지 않는다. 호출자가 넣은 값과 무관하게 Thread 값으로 덮어쓰며,
-- Thread의 tnn_id가 아직 null(절체 전)이면 자식도 null이다. UPDATE에는 걸지 않는다 — backfill이 명시적으로 채운다.
create or replace function copy_thr_tnn_id()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    new.tnn_id := (select t.tnn_id from thr t where t.id = new.thr_id);
    return new;
end;
$$;

create trigger trg_thr_mbr_copy_tnn_id
before insert on thr_mbr
for each row execute function copy_thr_tnn_id();

create trigger trg_msg_copy_tnn_id
before insert on msg
for each row execute function copy_thr_tnn_id();

create trigger trg_thr_idm_key_copy_tnn_id
before insert on thr_idm_key
for each row execute function copy_thr_tnn_id();

create trigger trg_msg_idm_key_copy_tnn_id
before insert on msg_idm_key
for each row execute function copy_thr_tnn_id();

create trigger trg_thr_inv_copy_tnn_id
before insert on thr_inv
for each row execute function copy_thr_tnn_id();

create trigger trg_thr_risk_crs_copy_tnn_id
before insert on thr_risk_crs
for each row execute function copy_thr_tnn_id();
