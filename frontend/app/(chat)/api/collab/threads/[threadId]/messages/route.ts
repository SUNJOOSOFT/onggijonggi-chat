/********************************************************
 파일명 : route.ts (app/(chat)/api/collab/threads/[threadId]/messages)
 설 명 : [MOCK] 방 진입 시 과거 대화 조회 목업(이슈 #190). 실 BFF 설정 시 우회된다.

 목업엔 메시지 저장소가 없다 — mocks/ws-server.ts는 받은 프레임을 방송만 하고 남기지 않는다.
 그래서 "이력이 실제로 화면에 붙는가"를 눈으로 보려는 용도로 고정 배열을 돌려준다. 같은 이유로
 방마다 다르게 꾸미지 않는다(participants 목업과 같은 결).

 seq를 0·1·2로 촘촘히 두지 않고 띄엄띄엄 둔 것은 의도적이다. 서버가 seq를 블록으로 예약해
 쓰지 않은 번호가 구멍으로 남는데, 클라이언트가 그 구멍을 "아직 안 온 메시지"로 오해하면
 안 된다 — 목업이 그 상황을 기본값으로 보여준다.
 *********************************************************/

import type { CollabMessageItem } from '@/lib/api/collab';
import { isMockMode } from '@/lib/api/config';

const MESSAGES: CollabMessageItem[] = [
  {
    id: 'mock-msg-1',
    seq: 0,
    athKind: 'HUMAN',
    status: 'COMPLETE',
    content: '지난번 계약서 검토 어디까지 됐나요?',
    authorSubject: 'mock-member',
    authorDisplayName: '동료 목업 사용자',
    createdAt: '2026-09-10T01:00:00Z',
    completedAt: '2026-09-10T01:00:00Z',
  },
  {
    id: 'mock-msg-2',
    seq: 3,
    athKind: 'HUMAN',
    status: 'COMPLETE',
    content: '@AI 3조만 요약해줘',
    authorSubject: 'mock-owner',
    authorDisplayName: '나',
    createdAt: '2026-09-10T01:01:00Z',
    completedAt: '2026-09-10T01:01:00Z',
  },
  {
    id: 'mock-msg-3',
    seq: 4,
    athKind: 'AGENT',
    status: 'COMPLETE',
    content: '「목업 이력」 3조는 지급 조건을 다룹니다.',
    authorSubject: null,
    authorDisplayName: null,
    createdAt: '2026-09-10T01:01:02Z',
    completedAt: '2026-09-10T01:01:09Z',
  },
];

export async function GET() {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  return Response.json(MESSAGES);
}
