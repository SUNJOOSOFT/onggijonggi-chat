-- 04·DATA — 채팅 첨부 파일(msg_file).
--
-- 이름은 scripts/glossary/data-glossary.md 축약 사전을 따른다. attachment를 축약하면 attempt의
-- att와 겹쳐서 이미 있는 file을 쓴다.
--
-- 원본 파일은 두지 않고 추출한 텍스트만 둔다 — AI 문맥에 넣는 것이 목적이고, 내려받기는 아직 없다.
--
-- 올리는 순간에는 메시지가 없어서 msg_id가 null이다. 발화에 실려 메시지가 저장될 때 그 메시지를
-- 가리키게 된다. 1:1의 첫 발화는 방도 그때 생기므로 thr_id를 따로 두지 않는다 — 방은 msg를 거쳐 안다.
-- 메시지가 지워지면(방 삭제 cascade) 첨부도 함께 지운다.
--
-- user_id는 올린 사람이다. 본인이 올린 것만 자기 발화에 실을 수 있다.
create table msg_file (
    id         uuid          not null,
    user_id    uuid          not null references app_user (id),
    msg_id     uuid          references msg (id) on delete cascade,
    file_name  varchar(255)  not null,
    file_text  text          not null,
    created_at timestamptz   not null default now(),
    primary key (id)
);

create index ix_msg_file_msg on msg_file (msg_id)
    where msg_id is not null;
