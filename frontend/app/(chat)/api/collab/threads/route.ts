/********************************************************
 파일명 : route.ts (app/(chat)/api/collab/threads)
 설 명 : [MOCK] 협업 채널 목록 목업(이슈 #19). 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.

 라우트 그룹은 URL에 나타나지 않으므로 (chat) 안에 있어도 실제 경로는 /api/collab/threads
 그대로다 — 1:1 채팅의 목업 라우트와 같은 자리에 뒀다.

 정상·AI 최초 오류·AI 도중 오류·접근 거부 두 방식을 유효한 UUID 방으로 나눠 #47 UI를 수동 검증한다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';
import {
  ERROR_BEFORE_THREAD_ID,
  ERROR_MID_THREAD_ID,
  FORBIDDEN_FRAME_THREAD_ID,
  FORBIDDEN_HANDSHAKE_THREAD_ID,
  NORMAL_THREAD_ID,
} from '@/mocks/rooms';

export const runtime = 'nodejs';

/** 목업 방 다섯 개 — 정상 스트림, 오류 두 시점, 접근 거부 두 방식을 결정적으로 재현한다. */
const THREADS = [
  {
    id: NORMAL_THREAD_ID,
    title: '정상 스트리밍 확인방',
    participants: ['sujin', 'minho'],
  },
  {
    id: ERROR_BEFORE_THREAD_ID,
    title: 'AI 최초 오류 확인방',
    participants: [],
  },
  {
    id: ERROR_MID_THREAD_ID,
    title: 'AI 도중 오류 확인방',
    participants: [],
  },
  {
    id: FORBIDDEN_HANDSHAKE_THREAD_ID,
    title: '접근 거부(핸드셰이크) 확인방',
    participants: [],
  },
  {
    id: FORBIDDEN_FRAME_THREAD_ID,
    title: '접근 거부(프레임) 확인방',
    participants: [],
  },
];

export async function GET() {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  return Response.json(THREADS);
}

/** 실 BFF가 없는 개발 모드에서도 #146 생성 화면을 같은 최소 계약으로 확인한다. */
export async function POST(request: Request) {
  if (!isMockMode()) {
    return new Response('Mock disabled: real BFF is configured', {
      status: 404,
    });
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return errorResponse('MALFORMED_REQUEST', '요청 본문을 읽을 수 없습니다.');
  }
  const title =
    body !== null && typeof body === 'object' && 'title' in body
      ? (body as { title?: unknown }).title
      : undefined;
  if (typeof title !== 'string' || title.trim() === '' || title.length > 255) {
    return errorResponse(
      'VALIDATION_ERROR',
      'title: 올바른 방 제목을 입력해 주세요.',
    );
  }

  const id = crypto.randomUUID();
  THREADS.unshift({ id, title, participants: [] });
  return Response.json({ id }, { status: 201 });
}

function errorResponse(code: string, message: string) {
  return Response.json({ error: { code, message } }, { status: 400 });
}
