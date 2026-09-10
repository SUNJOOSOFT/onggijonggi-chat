-- 04·DATA — 아직 로그인한 적 없는 사람에 대한 협업 Thread 초대(thr_inv). 이슈 #127.
--
-- 이름은 scripts/glossary/data-glossary.md 축약 사전을 따른다.
--
-- 왜 thr_mbr에 얹지 않고 별도 테이블인가.
-- thr_mbr.user_id는 app_user(id)를 참조하는 not null이라, app_user 행이 아직 없는 사람은
-- 그 테이블에 담을 수 없다. 담으려면 user_id를 nullable로 낮춰야 하는데, 그러면 "참가 행이
-- 있으면 곧 실재 유저"라는 보장이 사라진다 — msg.thr_mbr_id와 msg_human_has_participant가
-- 그 보장 위에 서 있다. 게다가 thr_mbr의 두 CHECK는 "ACTIVE가 아니면 반드시 끝난 것"이라는
-- 이분법이라, 활성도 종료도 아닌 대기 상태가 들어갈 자리가 없다.
--
-- 초대와 참가는 수명도 다르다. 오타로 만든 초대나 영영 로그인하지 않는 초대는 지워도 되지만,
-- 참가 기록은 누가 언제 왜 나갔는지를 남기는 감사 대상이다. 한 테이블에 섞으면 정리 정책도
-- 섞인다.
--
-- 대상을 subject 문자열로 들고 FK를 걸지 않는 것이 이 테이블의 존재 이유다. 초대자
-- (created_by_user_id)는 지금 로그인해 있는 사람이라 app_user 행이 반드시 있어 FK로 건다 —
-- 이 비대칭이 의도된 설계다.
--
-- 상태 전이는 PENDING → ACCEPTED(첫 로그인 전환) 또는 PENDING → REVOKED(취소·초대자 퇴사)다.
-- 끝난 초대의 행을 지우지 않는 것은 thr_mbr과 같은 이유다(ThrMbrStatus 주석 참조).
create table thr_inv (
    id                 uuid         not null,
    thr_id             uuid         not null references thr (id) on delete cascade,
    -- 초대 대상의 Keycloak subject. app_user 행이 없는 사람이라 FK가 아니다.
    subj               varchar(255) not null,
    status             varchar(16)  not null default 'PENDING'
                       check (status in ('PENDING', 'ACCEPTED', 'REVOKED')),
    created_by_user_id uuid         not null references app_user (id),
    created_at         timestamptz  not null default now(),
    ended_at           timestamptz,
    end_rsn            varchar(64),
    primary key (id),
    -- thr_mbr과 같은 방향 — "그 상태면 그 정보가 있다"만 강제하고 되돌릴 여지는 남기지 않는다.
    constraint thr_inv_pending_has_no_end_info
        check (status <> 'PENDING' or (ended_at is null and end_rsn is null)),
    constraint thr_inv_ended_has_end_info
        check (status = 'PENDING' or (ended_at is not null and end_rsn is not null))
);

-- 같은 사람을 같은 방에 두 번 대기시키지 않는다. 거둬지거나 수락된 과거 행은 이 인덱스 밖이라
-- 그대로 남는다(thr_mbr의 활성 유니크와 같은 방식).
create unique index ux_thr_inv_pending
    on thr_inv (thr_id, subj)
    where status = 'PENDING';

-- 첫 로그인 전환은 subject로 대기 초대를 찾는다. 그 경로가 로그인마다 도는 것은 아니지만
-- (app_user를 새로 만든 경우에만), 방 수가 늘면 훑는 비용이 커진다.
create index idx_thr_inv_subj_status on thr_inv (subj, status);

-- 초대자가 퇴사하면 그 사람이 보낸 대기 초대를 거둔다(#127 결정) — 그 조회 경로.
create index idx_thr_inv_created_by_status on thr_inv (created_by_user_id, status);
