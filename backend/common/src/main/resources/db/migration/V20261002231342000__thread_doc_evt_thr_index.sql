-- 04·DATA
-- 변경 이유: Thread 삭제가 thr_doc_evt를 (tnn_id, thr_id) FK cascade로 지울 때와 방 문서 목록이 등록 사건을 찾을 때 전체 스캔하지 않게 한다.
-- 기존 데이터 전제: 데이터 변경 없음. 인덱스만 추가한다.
-- 이관·중단 조건: 없음. 빈 테이블이 아니어도 일반 create index로 충분한 규모다.
create index ix_thr_doc_evt_thr on thr_doc_evt(thr_id);
