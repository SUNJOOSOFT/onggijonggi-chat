-- 04·DATA
-- 변경 이유:
-- thr_mbr에 낙관적 잠금(JPA @Version)용 컬럼을 추가한다(이슈 #137).
-- remove()의 강퇴(target.end)와 leaveSelf()의 자진 탈퇴가 같은 ThrMbr 행을 읽고 각자
-- end()로 덮어써도 지금은 막을 방법이 없다 — DB CHECK는 위반되지 않고 양쪽 다 204로
-- 성공하지만, 최종 status·end_rsn은 나중에 커밋한 쪽으로 비결정적으로 남아 감사 기록이
-- 실제 일어난 일과 달라질 수 있다. ver이 있으면 두 번째로 저장을 시도하는 쪽이
-- ObjectOptimisticLockingFailureException을 받는다.
--
-- 기존 데이터 전제:
-- 기존 행은 전부 0으로 채운다 — 낙관적 잠금은 "이 행을 읽은 뒤로 아무도 안 건드렸다"만
-- 보장하면 되므로 과거 갱신 횟수를 소급해서 맞출 필요가 없다.
--
-- 이관·중단 조건:
-- 되돌릴 필요가 생기면 컬럼을 드롭하면 된다 — 애플리케이션 코드에서 @Version을 먼저
-- 제거해야 안전하다(엔티티가 없는 컬럼을 버전으로 기대하면 기동 시 매핑 오류).
alter table thr_mbr
    add column ver bigint not null default 0;
