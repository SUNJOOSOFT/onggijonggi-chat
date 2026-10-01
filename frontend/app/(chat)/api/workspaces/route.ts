/********************************************************
 파일명 : route.ts (app/(chat)/api/workspaces)
 설 명 : [MOCK] 볼 수 있는 워크스페이스 목록 목업. 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.

 목업은 고객사 ROOT와 COMMON 하나를 준다 — 모든 방이 워크스페이스에 놓이는 실 BFF 계약과 같다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';
import { MOCK_WORKSPACES } from '@/mocks/workspaces';

export const runtime = 'nodejs';

export async function GET() {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  return Response.json(MOCK_WORKSPACES);
}
