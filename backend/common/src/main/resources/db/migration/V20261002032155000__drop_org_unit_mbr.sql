-- 04·DATA
-- 변경 이유: 사람의 팀·직급은 우리 DB에 두지 않는다. 속성 파일(app.rbac.members-path, 나중엔 고객사가 전달한 정보)에서
--           casbin-server의 사람 속성(p2)으로 적재한다. 배정 표와 그 가드를 지운다.
-- 기존 데이터 전제: org_unit_mbr의 행은 버린다. 같은 배정은 infra/config/members/members.csv로 다시 넣는다.
--           authz_adt의 지난 MEMBER_* 기록과 허용값은 그대로 둔다(감사 조회가 계속 읽는다).
-- 이관·중단 조건: 없다.

drop trigger if exists trg_org_unit_mbr_guard on org_unit_mbr;
drop function if exists org_unit_mbr_guard();
drop table org_unit_mbr;
