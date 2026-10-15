-- 04·DATA
-- 변경 이유: 채팅 첨부의 원본을 보관해, 보낸 첨부를 방 문서 패널에서 내려받거나 방 문서로 등록할 수 있게 한다.
-- 기존 데이터 전제: 기존 첨부는 원본이 없다. 목록에는 이름만 보이고 내려받기·등록은 할 수 없다.
-- 이관·중단 조건: 데이터 삭제 없음. 새 표와 인덱스만 만든다.

set local lock_timeout = '10s';

-- 첨부 원본. msg_file과 나눈 이유는 이력·문맥을 만들 때 msg_file을 읽어도 원본(최대 10MB)까지 끌려오지 않게 하려는 것이다.
-- 방이 정해지기 전(1:1의 첫 발화)에 올라오므로 방 단위 경로를 쓰는 문서 워커 저장소가 아니라 여기에 둔다.
-- 크기는 octet_length(content)로 읽는다(따로 두지 않는다).
-- 첨부가 지워지면(메시지·방 삭제 cascade, 보내지 않은 첨부 정리) 원본도 함께 지운다.
create table msg_file_org (
    file_id uuid  not null references msg_file (id) on delete cascade,
    content bytea not null,
    primary key (file_id)
);

-- 보내지 않은 첨부를 오래된 것부터 지운다(MsgFileService.cleanupUnsent).
create index ix_msg_file_unsent on msg_file (created_at)
    where msg_id is null;
