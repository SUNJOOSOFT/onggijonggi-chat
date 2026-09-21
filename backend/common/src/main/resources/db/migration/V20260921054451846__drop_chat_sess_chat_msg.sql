-- 04·DATA
-- 변경 이유:
-- #158에서 1:1 대화 정본을 chat_sess/chat_msg에서 thr(DIRECT)/msg로 옮긴 뒤, #164
-- 검증 체크리스트(운영 DB 신규 INSERT 없음, id 기준 건수 완전 일치, 실제 조회 정상,
-- FK 위반 없음)를 통과해 원본 보관 목적이 끝났다.
--
-- 기존 데이터 전제:
-- chat_sess/chat_msg를 참조하는 외부 테이블이 없다(chat_msg.sess_id -> chat_sess만
-- 내부 참조). 두 테이블 모두 애플리케이션 코드에서 더 이상 참조하지 않는다(#229).
--
-- 이관·중단 조건:
-- 이 migration은 데이터를 옮기지 않는다 — #158 migration이 이미 옮겨 놓은 원본을
-- 제거할 뿐이다.

drop table if exists chat_msg;
drop table if exists chat_sess;
