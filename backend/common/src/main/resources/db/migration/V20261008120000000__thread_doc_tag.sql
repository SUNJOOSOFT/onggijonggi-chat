-- 04·DATA
-- 변경 이유: #362 — 방 문서의 처리 회차마다 LLM이 뽑은 카테고리·핵심 키워드·요약(태그)을 남긴다. 검색의 태그 채널과 이후 근거 없음 처리가 쓴다.
-- 기존 데이터 전제: 기존 회차에는 태그가 없다. ETL 태깅 작업이 READY 문서의 현재 회차부터 태그를 붙인다(조각·임베딩은 다시 하지 않는다).
-- 이관·중단 조건: 데이터 삭제 없음. 새 표만 만든다.

set local lock_timeout = '10s';

-- 처리 회차별 태그. 조각과 같은 단위로 두고 검색은 "현재 회차"의 태그만 읽는다(새 회차에는 READY 뒤 태깅 작업이 태그를 붙인다).
-- thr_doc_run처럼 FK 없이 snapshot으로 둔다 — 문서·방이 지워져도 태그 색인 정리 대상을 잃지 않는다(회차 정리 때 함께 지운다).
-- status: DONE(태그 있음 — 카테고리가 UNCLASSIFIED면 미분류), FAILED(태깅 서버 장애 등, next_at 뒤 또는 설정이 바뀌면 다시 시도).
-- tag_cnf: 뽑을 때의 태깅 설정 지문(프롬프트 버전·모델·카테고리 목록·키워드 수·요약 길이). 지금 설정과 다르면 태그만 다시 뽑는다.
create table thr_doc_tag (
    id uuid primary key,
    doc_id uuid not null,
    tnn_id uuid not null,
    thr_id uuid not null,
    run_seq integer not null check (run_seq > 0),
    status varchar(16) not null check (status in ('DONE', 'FAILED')),
    ctg varchar(64),
    kyw text[] not null default '{}',
    smm text,
    tag_cnf varchar(255) not null,
    att_cnt integer not null default 0 check (att_cnt >= 0),
    next_at timestamptz not null default now(),
    err varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_thr_doc_tag_run unique (doc_id, run_seq),
    check ((status = 'DONE') = (ctg is not null))
);
