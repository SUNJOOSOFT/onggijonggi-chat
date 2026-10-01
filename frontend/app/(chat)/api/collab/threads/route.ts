/********************************************************
 파일명 : route.ts (app/(chat)/api/collab/threads)
 설 명 : [MOCK] 협업 채널 목록 목업(이슈 #19). 실 BFF(NEXT_PUBLIC_BFF_BASE_URL) 설정 시 우회된다.

 라우트 그룹은 URL에 나타나지 않으므로 (chat) 안에 있어도 실제 경로는 /api/collab/threads
 그대로다 — 1:1 채팅의 목업 라우트와 같은 자리에 뒀다.

 정상·AI 최초 오류·AI 도중 오류·접근 거부를 유효한 UUID 방으로 나눠 #47 UI를 수동 검증한다.
 *********************************************************/

import { isMockMode } from '@/lib/api/config';
import {
  ERROR_BEFORE_THREAD_ID,
  ERROR_MID_THREAD_ID,
  FORBIDDEN_FRAME_THREAD_ID,
  NORMAL_THREAD_ID,
} from '@/mocks/rooms';
import {
  MOCK_COMMON_ID,
  MOCK_COMMON_NAME,
  MOCK_WORKSPACES,
} from '@/mocks/workspaces';

export const runtime = 'nodejs';

/** 목업 방 네 개 — 정상 스트림, 오류 두 시점, 접근 거부를 결정적으로 재현한다. 방 단위 핸드셰이크
 * 거부는 핸드셰이크에 방이 없어져(이슈 #161) 재현할 대상이 아니다. */
/** 목업 방은 모두 COMMON에 놓인다(mocks/workspaces.ts) — 실 BFF와 같이 방은 워크스페이스에 놓인다. */
const THREADS: Array<{
  id: string;
  title: string;
  participants: string[];
  workspaceId: string | null;
  workspaceName: string | null;
}> = [
  {
    id: NORMAL_THREAD_ID,
    title: '정상 스트리밍 확인방',
    participants: ['sujin', 'minho'],
    workspaceId: MOCK_COMMON_ID,
    workspaceName: MOCK_COMMON_NAME,
  },
  {
    id: ERROR_BEFORE_THREAD_ID,
    title: 'AI 최초 오류 확인방',
    participants: [],
    workspaceId: MOCK_COMMON_ID,
    workspaceName: MOCK_COMMON_NAME,
  },
  {
    id: ERROR_MID_THREAD_ID,
    title: 'AI 도중 오류 확인방',
    participants: [],
    workspaceId: MOCK_COMMON_ID,
    workspaceName: MOCK_COMMON_NAME,
  },
  {
    id: FORBIDDEN_FRAME_THREAD_ID,
    title: '접근 거부 확인방',
    participants: [],
    workspaceId: MOCK_COMMON_ID,
    workspaceName: MOCK_COMMON_NAME,
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

  // 실 BFF는 workspaceId가 없으면 판정이 켜진 배포에서 400이다. 목업은 COMMON에 두되 모르는 워크스페이스는 거절한다.
  const requested =
    body !== null && typeof body === 'object' && 'workspaceId' in body
      ? (body as { workspaceId?: unknown }).workspaceId
      : undefined;
  const workspace =
    requested === undefined || requested === null
      ? MOCK_WORKSPACES.find((node) => node.id === MOCK_COMMON_ID)
      : MOCK_WORKSPACES.find(
          (node) => node.id === requested && node.kind !== 'ROOT',
        );
  if (workspace === undefined) {
    return Response.json(
      {
        error: {
          code: 'FORBIDDEN',
          message: '이 작업을 수행할 권한이 없습니다.',
        },
      },
      { status: 403 },
    );
  }

  const id = crypto.randomUUID();
  THREADS.unshift({
    id,
    title,
    participants: [],
    workspaceId: workspace.id,
    workspaceName: workspace.name,
  });
  return Response.json({ id }, { status: 201 });
}

function errorResponse(code: string, message: string) {
  return Response.json({ error: { code, message } }, { status: 400 });
}
