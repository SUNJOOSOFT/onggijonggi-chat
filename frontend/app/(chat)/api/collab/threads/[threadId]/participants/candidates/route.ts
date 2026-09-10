/********************************************************
 파일명 : route.ts (app/(chat)/api/collab/threads/[threadId]/participants/candidates)
 설 명 : [MOCK] 초대 후보 검색 목업(이슈 #172). 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.

 형제 participants/route.ts와 같은 방침이다 — 목업은 읽기만 흉내 낸다. 검색도 읽기라 여기서
 받는다. 이게 없으면 목업 모드에서 초대창에 글자를 칠 때마다 404가 나 오류 토스트가 뜨는데,
 그러면 Sheet UI를 목업으로 확인한다는 목적 자체가 깨진다.

 목업엔 계정 개념이 없어(mocks/rooms.ts는 요청자를 구분하지 않는다) Keycloak을 흉내 내지
 않고 고정 배열을 검색어로 거른다. 이미 방에 있거나 이미 초대한 사람을 빼는 것은 실제로는
 서버 몫인데, 여기서도 같은 subject를 배열에서 빼두어 화면 동작이 같아지게 했다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';
import type { InviteCandidate } from '@/lib/api/collab';

/** participants 목업의 mock-owner·mock-member·mock-invited와 겹치지 않는 사람들만 둔다. */
const CANDIDATES: InviteCandidate[] = [
  { subject: 'mock-candidate-1', displayName: '김목업' },
  { subject: 'mock-candidate-2', displayName: '이목업' },
  { subject: 'mock-candidate-3', displayName: '박테스트' },
];

export async function GET(request: Request) {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  const query = (
    new URL(request.url).searchParams.get('q') ?? ''
  ).trim();
  // 서버와 같은 하한 — 두 글자 미만은 오류가 아니라 빈 목록이다.
  if (query.length < 2) {
    return Response.json([]);
  }

  return Response.json(
    CANDIDATES.filter((candidate) => candidate.displayName.includes(query)),
  );
}
