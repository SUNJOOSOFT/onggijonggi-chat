/********************************************************
 파일명 : page.tsx (app/(chat)/admin/permissions)
 설 명 : 권한 관리 화면. 사람의 팀·직급을 바꾸고 "누가 무엇을 보나"를 실제 판정으로 확인한다.
 조회·변경은 PermissionsAdmin이 클라이언트에서 한다(목업 모드의 상대주소 문제는 lib/api/collab.ts 주석).
 (chat) 라우트 그룹 안에 둬서 같은 사이드바에서 오간다.
 *********************************************************/

import { PermissionsAdmin } from '@/components/permissions/permissions-admin';

export default function Page() {
  return <PermissionsAdmin />;
}
