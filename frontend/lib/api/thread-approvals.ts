/********************************************************
 파일명 : thread-approvals.ts (lib/api)
 설 명 : 협업방 승인 요청·처리 REST 클라이언트(이슈 #407). 요청 내용은 WS 프레임에 싣지 않으므로
 요청자·지정된 사람은 여기서 목록으로 읽는다.
 *********************************************************/

import { bffUrl } from './config';
import { friendlyMessageForCode, parseErrorEnvelope } from './errors';
import { authFetch } from './http';

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/**
 * GET .../approvals 항목(백엔드 ThreadApprovalView). 내가 요청했거나 내가 지정된 건만 온다. 표시 이름은 싣지
 * 않으므로 화면이 참여자 명단에서 subject로 찾는다.
 * canDecide는 지금 내가 처리할 수 있는지(지정된 사람이고 대기 중이며 방에 쓸 수 있을 때)다.
 */
export interface ThreadApproval {
  id: string;
  messageId: string;
  requesterSubject: string;
  targetSubject: string;
  status: ApprovalStatus;
  reason: string | null;
  createdAt: string;
  completedAt: string | null;
  canDecide: boolean;
}

/** 반려 사유의 서버 상한. 화면이 입력 단계에서 같은 값으로 막는다. */
export const APPROVAL_REASON_MAX = 500;

/** 화면이 접근권 회수와 일시 실패를 가를 수 있게 상태 코드를 싣는다. message는 사용자 문구다. */
export class ThreadApprovalError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

const approvalsPath = (threadId: string) =>
  `/api/threads/${encodeURIComponent(threadId)}/approvals`;

async function send(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await authFetch(url, init);
  } catch (cause) {
    if (cause instanceof TypeError)
      throw new ThreadApprovalError(
        '서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
      );
    throw cause;
  }
}

// 에러 봉투가 있으면 code로 문구를 고르고, 봉투가 없는 응답(목업·프록시)만 상태 코드로 고른다.
async function checked(response: Response): Promise<Response> {
  if (response.ok) return response;
  const envelope = parseErrorEnvelope(await response.text().catch(() => ''));
  if (envelope?.code)
    throw new ThreadApprovalError(
      friendlyMessageForCode(envelope.code),
      response.status,
    );
  const messages: Record<number, string> = {
    400: '요청 대상이나 입력값을 확인해 주세요.',
    401: '세션이 만료되었어요. 다시 로그인해 주세요.',
    404: '승인 요청을 찾을 수 없어요.',
    409: '요청 상태가 방금 바뀌었어요. 목록을 새로고침한 뒤 다시 시도해 주세요.',
  };
  throw new ThreadApprovalError(
    messages[response.status] ?? '승인 요청을 처리하지 못했어요.',
    response.status,
  );
}

async function json<T>(response: Response): Promise<T> {
  try {
    return (await response.json()) as T;
  } catch {
    throw new ThreadApprovalError(
      '서버 응답을 읽지 못했어요. 잠시 후 다시 시도해 주세요.',
    );
  }
}

export async function listThreadApprovals(
  threadId: string,
  signal?: AbortSignal,
): Promise<ThreadApproval[]> {
  const response = await checked(
    await send(bffUrl(approvalsPath(threadId)), { signal, cache: 'no-store' }),
  );
  return json<ThreadApproval[]>(response);
}

/**
 * 승인을 요청한다. id는 호출부가 만들어 재시도에도 그대로 쓴다 — 서버가 같은 id의 재전송을 하나로 합친다.
 */
export async function requestThreadApproval(
  threadId: string,
  body: { id: string; messageId: string; targetSubject: string },
): Promise<ThreadApproval> {
  const response = await checked(
    await send(bffUrl(approvalsPath(threadId)), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
  );
  return json<ThreadApproval>(response);
}

/** 지정된 사람이 승인 또는 반려한다. reason은 반려할 때만 보내고, 비어 있으면 싣지 않는다. */
export async function decideThreadApproval(
  threadId: string,
  approvalId: string,
  decision: { approved: boolean; reason?: string },
): Promise<ThreadApproval> {
  const reason = decision.approved ? undefined : decision.reason?.trim();
  const response = await checked(
    await send(
      bffUrl(
        `${approvalsPath(threadId)}/${encodeURIComponent(approvalId)}/decision`,
      ),
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          approved: decision.approved,
          ...(reason ? { reason } : {}),
        }),
      },
    ),
  );
  return json<ThreadApproval>(response);
}
