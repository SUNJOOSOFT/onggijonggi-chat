-- 04·DATA — 협업방 생성 요청의 idempotency key(이슈 #149).
--
-- 이름은 scripts/glossary/data-glossary.md 축약 사전을 따른다.
--
-- 응답 유실 뒤 재시도가 안전하려면, 같은 사용자·같은 키의 두 번째 요청이 새 thr을 또 만드는 대신
-- 최초 결과를 그대로 돌려줘야 한다. title도 함께 저장하는 이유는 같은 키에 다른 title이 오는
-- 경우(클라이언트 버그·재사용 실수)를 구분하기 위해서다 — title이 idempotency key 자체는
-- 아니지만, 저장해두지 않으면 "같은 키인데 내용이 다른 요청"을 그냥 통과시키게 된다.
--
-- PK를 (user_id, key)가 아니라 대리키로 둔다 — 이 저장소 관례(thr_mbr과 같은 이유)를 따른다.
-- 동시 요청 경합은 유니크 인덱스 위반을 잡아 재조회하는 방식(UserIdentityService와 같은 패턴)으로
-- 애플리케이션이 처리하므로, DB 쪽은 유니크 인덱스만 있으면 된다.
--
-- 만료 정리 배치는 이번 단계에 넣지 않는다. 조회 시점에 created_at이 TTL을 넘었으면 응용
-- 코드가 "새 요청"으로 취급한다(#149 코멘트) — 정리는 행이 실제로 쌓여 문제가 될 때 별도
-- 이슈로 연다.
-- 컬럼명은 idm_key다 — key 단독은 H2(테스트 DB)의 예약어라 스키마 즉석 생성이 깨진다. Postgres는
-- key를 예약어로 두지 않아 이 문제가 없지만, 두 DB 모두에서 같은 이름을 쓴다.
create table thr_idm_key (
    id         uuid         not null,
    user_id    uuid         not null references app_user (id),
    idm_key    varchar(255) not null,
    title      varchar(255) not null,
    thr_id     uuid         not null references thr (id),
    created_at timestamptz  not null default now(),
    primary key (id)
);

create unique index ux_thr_idm_key_user_key on thr_idm_key (user_id, idm_key);
