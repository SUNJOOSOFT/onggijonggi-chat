-- 04·DATA
-- 변경 이유: 원본 저장 시도별 경로를 분리해 오래된 PUT/DELETE가 새 재시도의 원본에 영향을 주지 않게 한다.
-- 기존 데이터 전제: 기존 NULL 시도 식별자는 기존 경로를 유지한다. 등록된 원본을 임의 이동하지 않는다.
-- 이관·중단 조건: 원본 바이트 이관은 없다. 실패 등록은 기존 정리 완료 뒤 새 시도 식별자로 재시도한다.
alter table thr_doc add column src_att_id uuid;
alter table thr_doc_end add column src_att_id uuid;

create or replace function queue_thr_doc_end() returns trigger language plpgsql as $$
begin
    insert into thr_doc_end(doc_id, tnn_id, thr_id, src_key, src_att_id, next_at)
        values(old.id, old.tnn_id, old.thr_id, old.src_key, old.src_att_id,
            case when old.status = 'UPLOADING' then old.updated_at + interval '3 minutes' else now() end)
        on conflict(doc_id) do update set next_at = greatest(thr_doc_end.next_at, excluded.next_at);
    return old;
end $$;
