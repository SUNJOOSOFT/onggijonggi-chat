/********************************************************
 파일명 : route.ts (app/(chat)/api/collab/threads/[threadId]/participants)
 설 명 : [MOCK] 참여자 목록 조회 목업(이슈 #23). 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.

 목업엔 참여자 테이블 자체가 없다(mocks/rooms.ts의 roomAccess() 참고 — WS 인가는 예약
 threadId 두 개만 막고 나머지는 전부 허용, role·참여자 개념이 없다). GET /api/collab/threads
 목업도 요청자가 누군지 보지 않고 고정 배열만 돌려준다 — "나"를 구별하는 mock 개념이 아예 없다.
 그래서 이 목업도 self를 실제 로그인 subject와 대조하지 않고 고정 배열의 한 행에 그냥 박아
 둔다(설계 문서 2.5) — 상태 변경이 없는 읽기 전용 화면이라 실제 사용자와 맞출 이유가 없다.

 초대·제거·위임(POST/DELETE/PUT)과 초대 취소는 이 파일에 만들지 않는다 — 핸들러가 없으면
 Next.js가 자동으로 405를 준다. 후보 검색은 읽기라 candidates/route.ts에 따로 흉내 낸다(#172).
 *********************************************************/

import { isMockMode } from '@/lib/api/config';
import type { ThreadParticipant } from '@/lib/api/collab';

/** 목업 방 다섯 개(mocks/rooms.ts) 전부 같은 고정 참여자 배열을 쓴다 — Sheet UI 확인용이라
 * 방마다 다르게 꾸밀 이유가 없다. */
const PARTICIPANTS: ThreadParticipant[] = [
  {
    subject: 'mock-owner',
    role: 'OWNER',
    self: true,
    displayName: '나',
    pending: false,
  },
  {
    subject: 'mock-member',
    role: 'MEMBER',
    self: false,
    displayName: '동료 목업 사용자',
    pending: false,
  },
  // 대기 초대 줄도 하나 둔다(이슈 #172) — 이 목업은 Sheet UI 확인용이라, 실제로 보여야 할
  // 상태가 화면에 안 나오면 목업으로서 쓸모가 없다.
  {
    subject: 'mock-invited',
    role: 'MEMBER',
    self: false,
    displayName: '초대만 해둔 목업 사용자',
    pending: true,
  },
];

export async function GET() {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  return Response.json(PARTICIPANTS);
}
