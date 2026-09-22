-- 04-DATA: phase 1 staging tables for the later tenant cutover validation.

create table stg_user_tnn (
    user_id uuid        not null references app_user (id) on delete restrict,
    tnn_key varchar(64) not null,
    primary key (user_id, tnn_key),
    constraint stg_user_tnn_key_format check (tnn_key ~ '^[a-z][a-z0-9-]{0,62}$')
);

-- thr_id는 cascade다. RESTRICT로 두면 절체 전에 staging에 적재된 방을 OWNER가 삭제할 수 없어 방 삭제 API가 막힌다.
-- Thread가 지워지면 그 Tenant 매핑도 함께 사라지는 것이 맞다. user_id는 사용자를 물리 삭제하지 않으므로 RESTRICT다.
create table stg_thr_tnn (
    thr_id  uuid        not null references thr (id) on delete cascade,
    tnn_key varchar(64) not null,
    primary key (thr_id),
    constraint stg_thr_tnn_key_format check (tnn_key ~ '^[a-z][a-z0-9-]{0,62}$')
);

create table stg_user_cur_tnn (
    user_id uuid        not null references app_user (id) on delete restrict,
    tnn_key varchar(64) not null,
    primary key (user_id),
    constraint stg_user_cur_tnn_key_format check (tnn_key ~ '^[a-z][a-z0-9-]{0,62}$')
);
