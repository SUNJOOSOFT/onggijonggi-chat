/********************************************************
 파일명 : route.ts (app/(chat)/api/workspaces)
 설 명 : [MOCK] 볼 수 있는 워크스페이스 목록 목업. 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.

 목업은 권한 기능이 꺼진 배포처럼 빈 목록을 준다 — 협업방 만들기 화면이 워크스페이스 고르는 칸을
 숨기고 제목만으로 만드는 기존 흐름 그대로 확인된다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';

export const runtime = 'nodejs';

export async function GET() {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  return Response.json([]);
}
