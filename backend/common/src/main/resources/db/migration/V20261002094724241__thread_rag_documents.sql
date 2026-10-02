-- 04·DATA
-- 변경 이유:
-- 기존 데이터 전제:
-- 이관·중단 조건:

-- 04·DATA — #338: 기존 문서·직접 첨부와 분리한 Thread RAG 등록·변경 이력·원본 정리.
-- 기존 자료는 이관하지 않는다. Tenant/Thread 복합 FK를 사용하고, 원본 정리 참조는 방 삭제 뒤에도 보존한다.
create table thr_doc (
    id uuid primary key,
    tnn_id uuid not null,
    thr_id uuid not null,
    user_id uuid not null references app_user(id) on delete restrict,
    file_name varchar(255) not null,
    file_size bigint not null check (file_size > 0 and file_size <= 10485760),
    src_key varchar(64) not null check (src_key ~ '^[a-f0-9]{64}$'),
    status varchar(16) not null check (status in ('UPLOADING', 'PENDING', 'PROCESSING', 'READY', 'FAILED', 'DELETED')),
    pnn boolean not null default false,
    err varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint fk_thr_doc_thr foreign key (tnn_id, thr_id) references thr(tnn_id, id) on delete cascade,
    constraint ck_thr_doc_deleted check ((status = 'DELETED') = (deleted_at is not null)),
    constraint ck_thr_doc_pinned check (not pnn or status not in ('UPLOADING', 'DELETED')),
    constraint uq_thr_doc_tnn_thr_id unique (tnn_id, thr_id, id)
);
create index ix_thr_doc_thr_created on thr_doc(thr_id, created_at, id);

-- 문서 삭제 tombstone과 달리 방 삭제는 기존 이력 삭제 정책을 따른다. doc_id는 당시 식별 snapshot이다.
create table thr_doc_evt (
    id uuid primary key,
    tnn_id uuid not null,
    thr_id uuid not null,
    doc_id uuid not null,
    act_user_id uuid not null references app_user(id) on delete restrict,
    evt_kind varchar(16) not null check (evt_kind in ('REGISTERED', 'PINNED', 'UNPINNED', 'DELETED')),
    req_key uuid not null,
    created_at timestamptz not null default now(),
    constraint fk_thr_doc_evt_thr foreign key (tnn_id, thr_id) references thr(tnn_id, id) on delete cascade,
    constraint uq_thr_doc_evt_req unique (doc_id, req_key)
);

-- 삭제/실패 원본의 내구성 있는 정리 참조. Thread FK를 두면 cascade가 정리 대상을 잃으므로 snapshot만 둔다.
create table thr_doc_end (
    doc_id uuid primary key,
    tnn_id uuid not null,
    thr_id uuid not null,
    src_key varchar(64) not null,
    next_at timestamptz not null,
    att_cnt integer not null default 0,
    err varchar(64)
);
create index ix_thr_doc_end_next on thr_doc_end(next_at);

create function queue_thr_doc_end() returns trigger language plpgsql as $$
begin
    insert into thr_doc_end(doc_id, tnn_id, thr_id, src_key, next_at)
        values(old.id, old.tnn_id, old.thr_id, old.src_key,
            case when old.status = 'UPLOADING' then old.updated_at + interval '3 minutes' else now() end)
        on conflict(doc_id) do update set next_at = greatest(thr_doc_end.next_at, excluded.next_at);
    return old;
end $$;
create trigger trg_thr_doc_end before delete on thr_doc for each row execute function queue_thr_doc_end();
