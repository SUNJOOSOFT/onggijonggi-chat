-- 04·DATA — 위험 발화 사후 검증 배치의 스레드별 스캔 커서(#28).
--
-- 이름은 scripts/glossary/data-glossary.md 축약 사전을 따른다.
--
-- msg에 스캔 여부를 표시하는 컬럼을 두는 방법도 검토했으나, 완료된 메시지의 UPDATE 자체를
-- 막는 트리거(msg_block_terminal_mutation, V11__message.sql)에 막힌다. HUMAN 메시지는 생성
-- 즉시 COMPLETE라 나중에 그 행에 값을 채워 넣을 수 없다. 그래서 msg는 건드리지 않고, 스레드별로
-- 어디까지 스캔했는지만 별도 테이블에 둔다 — thr.next_seq 채번과 같은 결이지만 이쪽은 배치
-- 하나만 쓰는 값이라 원자적 UPDATE...RETURNING까지는 필요 없다.
create table thr_risk_crs (
    thr_id     uuid        not null references thr (id) on delete cascade,
    last_seq   bigint      not null default 0,
    updated_at timestamptz not null default now(),
    primary key (thr_id)
);
