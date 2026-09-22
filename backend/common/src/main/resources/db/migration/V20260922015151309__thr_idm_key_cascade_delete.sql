-- 04·DATA
-- 변경 이유:
-- thr_idm_key.thr_id·msg_idm_key.thr_id가 ON DELETE NO ACTION(기본값)이라, idempotency
-- 키 행이 남아 있는 상태로 Thread를 지우면 문장 끝 FK 검사에서 삭제 전체가 롤백된다(#251).
-- thr_mbr·msg·thr_inv·thr_risk_crs는 이미 on delete cascade라 idempotency 키만 예외였다.
--
-- 기존 데이터 전제:
-- 두 FK 모두 원래 migration(V13, V20260918020045611)에서 제약 이름을 지정하지 않아
-- PostgreSQL이 자동으로 <table>_<column>_fkey 이름을 붙였다 — 아래 DROP CONSTRAINT가
-- 그 자동 생성 이름을 그대로 참조한다.
--
-- 이관·중단 조건:
-- 이 migration은 데이터를 옮기지 않는다. msg_idm_key -> msg 참조(hmn_msg_id·agn_msg_id)는
-- 건드리지 않는다 — Thread 삭제 한 번으로 msg도 함께 cascade되므로 문장 끝 검사가 그대로
-- 통과한다.

alter table thr_idm_key
    drop constraint thr_idm_key_thr_id_fkey,
    add constraint thr_idm_key_thr_id_fkey foreign key (thr_id) references thr (id) on delete cascade;

alter table msg_idm_key
    drop constraint msg_idm_key_thr_id_fkey,
    add constraint msg_idm_key_thr_id_fkey foreign key (thr_id) references thr (id) on delete cascade;
