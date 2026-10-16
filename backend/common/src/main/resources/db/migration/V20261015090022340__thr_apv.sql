-- 04·DATA
-- 변경 이유: 협업방의 승인 요청 건(요청자·대상·상태·반려 사유)을 저장한다. 처리한 뒤에도 지우지 않아 승인 이력과 후속 조건으로 쓴다(#407).
-- 기존 데이터 전제: 새 테이블이라 기존 데이터에 영향이 없다.
-- 이관·중단 조건: 이관할 데이터가 없다. 되돌릴 때는 이 테이블만 지운다.

-- #407: 방 안의 메시지(제안)에 대한 승인 요청. 승인할 수 있는 사람은 요청에서 지정한 한 사람(trg_user_id)뿐이다.
-- 방 삭제와 대상 메시지 삭제에는 함께 지워진다. 사용자는 thr_doc처럼 삭제를 막는다(restrict).
create table thr_apv (
    id uuid primary key,
    tnn_id uuid not null,
    thr_id uuid not null,
    msg_id uuid not null,
    req_user_id uuid not null references app_user(id) on delete restrict,
    trg_user_id uuid not null references app_user(id) on delete restrict,
    status varchar(16) not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    rsn varchar(500),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint fk_thr_apv_thr foreign key (tnn_id, thr_id) references thr(tnn_id, id) on delete cascade,
    constraint fk_thr_apv_msg foreign key (tnn_id, thr_id, msg_id) references msg(tnn_id, thr_id, id) on delete cascade,
    -- 대기 중이면 처리 시각이 없고, 처리됐으면 있다.
    constraint ck_thr_apv_completed check ((status = 'PENDING') = (completed_at is null)),
    -- 반려 사유는 반려일 때만 있을 수 있다(선택 입력).
    constraint ck_thr_apv_reason check (rsn is null or (status = 'REJECTED' and char_length(rsn) between 1 and 500)),
    -- 본인에게는 요청할 수 없다.
    constraint ck_thr_apv_target check (req_user_id <> trg_user_id)
);

-- 방 단위 조회(이력)와, 한 사람의 대기 중 요청 조회(접속 직후 보여 주기)를 받친다.
create index ix_thr_apv_thr_created on thr_apv(thr_id, created_at, id);
create index ix_thr_apv_trg_pending on thr_apv(tnn_id, trg_user_id, created_at, id) where status = 'PENDING';
