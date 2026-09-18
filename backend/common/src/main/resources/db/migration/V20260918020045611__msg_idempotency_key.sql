-- 04·DATA — DIRECT 발화(사람 메시지) 저장의 idempotency key(이슈 #233).
--
-- 이름은 scripts/glossary/data-glossary.md 축약 사전을 따른다.
--
-- WS 전송 확인을 잃은 재시도가 내용이 같은 새 HUMAN 메시지를 만드는 걸 막는다. COLLAB 방
-- 생성의 thr_idm_key(V13, #149)와 같은 성격이지만 재사용할 수 없다 — 그쪽은 replay 결과로
-- thr_id 하나만 돌려주면 되지만, DIRECT 발화 replay는 사람 발화·예약된 AGENT 응답 두 메시지의
-- id·seq를 통째로 복원해야 한다. thr_idm_key.title(varchar(255) not null)도 무제한 길이인
-- 메시지 content와 타입·의미가 안 맞는다.
--
-- content를 함께 저장하는 이유는 thr_idm_key.title과 같다 — 같은 키에 다른 내용이 오면
-- (클라이언트 버그·키 재사용 실수) 구분해서 거절하기 위해서다.
--
-- PK를 (user_id, key)가 아니라 대리키로 둔다 — thr_idm_key와 같은 저장소 관례다. 동시 요청
-- 경합은 유니크 인덱스 위반을 잡아 재조회하는 방식으로 애플리케이션이 처리한다.
--
-- TTL은 5분으로, thr_idm_key(24시간)보다 훨씬 짧다 — 채팅 재시도는 보통 수초 안에 일어나는
-- 일이라 짧은 TTL로도 실제 재시도는 다 잡히고, 너무 길면 한참 뒤 같은 내용을 진짜로 다시
-- 보내고 싶은 사용자의 새 메시지까지 막을 수 있다. 만료 정리 배치는 두지 않는다 — 조회
-- 시점에 created_at이 TTL을 넘었으면 애플리케이션이 "새 요청"으로 취급한다(#149와 동일 방식).
--
-- 컬럼명은 idm_key다 — key 단독은 H2(테스트 DB)의 예약어라 스키마 즉석 생성이 깨진다.
create table msg_idm_key (
    id           uuid         not null,
    user_id      uuid         not null references app_user (id),
    idm_key      varchar(255) not null,
    content      text         not null,
    thr_id       uuid         not null references thr (id),
    hmn_msg_id   uuid         not null references msg (id),
    hmn_seq      bigint       not null,
    agn_msg_id   uuid         not null references msg (id),
    agn_seq      bigint       not null,
    created_at   timestamptz  not null default now(),
    primary key (id)
);

create unique index ux_msg_idm_key_user_key on msg_idm_key (user_id, idm_key);
