-- 04·DATA: 절체 완료 표지의 불변 저장소를 마련하고, 더 이상 읽지 않는 staging을 제거한다.
-- migration은 표지 행을 넣지 않는다. 운영 점검창의 사후 검증과 RBAC 강제 확인 뒤 명시 기록한다.
-- 기존 staging 값은 이관 근거가 아니며, 사전 검증 API 교체와 함께 제거한다.

create table ctv (
    id           smallint    not null primary key check (id = 1),
    tnn_id       uuid        not null references tnn (id) on delete restrict,
    completed_at timestamptz not null default now()
);

create or replace function ctv_immutable_guard() returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
    raise exception 'cutover completion marker is immutable';
end;
$$;

create trigger trg_ctv_no_update_delete before update or delete on ctv
for each row execute function ctv_immutable_guard();
create trigger trg_ctv_no_truncate before truncate on ctv
for each statement execute function ctv_immutable_guard();
alter table ctv enable always trigger trg_ctv_no_update_delete;
alter table ctv enable always trigger trg_ctv_no_truncate;

drop table stg_user_cur_tnn;
drop table stg_thr_tnn;
drop table stg_user_tnn;
