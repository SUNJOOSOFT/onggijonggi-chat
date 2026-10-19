-- 04·DATA
-- 변경 이유: 문서 생성 워커가 만든 파일을 협업방의 "출력"으로 남기는 기록을 저장한다(#414). 방 문서(thr_doc)는 사용자가 올린 원본용이라 생성 파일을 담지 못해 따로 둔다.
-- 기존 데이터 전제: 새 테이블이라 기존 데이터에 영향이 없다.
-- 이관·중단 조건: 이관할 데이터가 없다. 되돌릴 때는 이 테이블만 지운다.

-- #414: 방에 남은 생성 문서 한 건. 파일 내용은 문서 워커의 저장소에 있고 여기에는 위치(file_key)만 둔다. 방을 삭제하면 함께 지워진다.
-- 사용자는 thr_doc처럼 삭제를 막는다(restrict). 같은 등록 요청을 다시 보내도 하나만 남도록 요청 키(req_key)에 유니크를 둔다.
create table thr_out (
    id uuid primary key,
    tnn_id uuid not null,
    thr_id uuid not null,
    user_id uuid not null references app_user(id) on delete restrict,
    file_name varchar(255) not null,
    file_kind varchar(8) not null check (file_kind in ('DOCX', 'PDF', 'MD')),
    file_size bigint not null check (file_size > 0),
    file_key varchar(1024) not null,
    req_key uuid not null,
    created_at timestamptz not null default now(),
    constraint fk_thr_out_thr foreign key (tnn_id, thr_id) references thr(tnn_id, id) on delete cascade,
    constraint uq_thr_out_req unique (tnn_id, thr_id, req_key)
);

-- 방 단위 목록 조회(최신순)를 받친다.
create index ix_thr_out_thr_created on thr_out(thr_id, created_at, id);
