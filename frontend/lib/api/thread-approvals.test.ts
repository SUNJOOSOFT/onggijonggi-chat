/********************************************************
 파일명 : thread-approvals.test.ts (lib/api)
 설 명 : 승인 REST 클라이언트가 서버 계약대로 요청을 만들고, 오류를 사용자 문구로 바꾸는지 본다(이슈 #407).
 *********************************************************/

import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./http', () => ({ authFetch: vi.fn() }));

import { authFetch } from './http';
import {
  ThreadApprovalError,
  decideThreadApproval,
  listThreadApprovals,
  requestThreadApproval,
} from './thread-approvals';

const fetcher = vi.mocked(authFetch);
const THREAD = 'room-1';

const view = {
  id: 'apv-1',
  messageId: 'm1',
  requesterSubject: 'sujin',
  targetSubject: 'minho',
  status: 'PENDING',
  reason: null,
  createdAt: '2026-10-16T00:00:00Z',
  completedAt: null,
  canDecide: true,
};

/** authFetch에 마지막으로 넘어간 요청의 URL과 본문. */
function lastCall() {
  const [url, init] = fetcher.mock.calls.at(-1) ?? [];
  const body = init?.body;
  return {
    url,
    method: init?.method,
    body: typeof body === 'string' ? JSON.parse(body) : undefined,
  };
}

beforeEach(() => fetcher.mockReset());

describe('listThreadApprovals', () => {
  it('방의 승인 목록을 그대로 돌려준다', async () => {
    fetcher.mockResolvedValue(Response.json([view]));

    await expect(listThreadApprovals(THREAD)).resolves.toEqual([view]);
    expect(lastCall().url).toBe(`/api/threads/${THREAD}/approvals`);
  });
});

describe('requestThreadApproval', () => {
  it('id·메시지·대상을 POST로 보낸다', async () => {
    fetcher.mockResolvedValue(Response.json(view, { status: 201 }));

    await requestThreadApproval(THREAD, {
      id: 'apv-1',
      messageId: 'm1',
      targetSubject: 'minho',
    });

    expect(lastCall()).toMatchObject({
      url: `/api/threads/${THREAD}/approvals`,
      method: 'POST',
      body: { id: 'apv-1', messageId: 'm1', targetSubject: 'minho' },
    });
  });
});

describe('decideThreadApproval', () => {
  const url = `/api/threads/${THREAD}/approvals/apv-1/decision`;

  it('승인에는 사유를 싣지 않는다', async () => {
    fetcher.mockResolvedValue(Response.json(view));

    await decideThreadApproval(THREAD, 'apv-1', {
      approved: true,
      reason: '남아 있던 사유',
    });

    expect(lastCall()).toMatchObject({
      url,
      method: 'PUT',
      body: { approved: true },
    });
    expect(lastCall().body).not.toHaveProperty('reason');
  });

  it('반려 사유는 앞뒤 공백을 떼고 싣는다', async () => {
    fetcher.mockResolvedValue(Response.json(view));

    await decideThreadApproval(THREAD, 'apv-1', {
      approved: false,
      reason: '  근거를 보강해 주세요  ',
    });

    expect(lastCall().body).toEqual({
      approved: false,
      reason: '근거를 보강해 주세요',
    });
  });

  it('사유가 비어 있으면 반려라도 싣지 않는다', async () => {
    fetcher.mockResolvedValue(Response.json(view));

    await decideThreadApproval(THREAD, 'apv-1', {
      approved: false,
      reason: '   ',
    });

    expect(lastCall().body).toEqual({ approved: false });
  });
});

describe('오류', () => {
  it('봉투가 없는 응답은 상태 코드로 문구를 고르고 상태를 싣는다', async () => {
    fetcher.mockResolvedValue(new Response(null, { status: 409 }));

    const error = await decideThreadApproval(THREAD, 'apv-1', {
      approved: true,
    }).catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ThreadApprovalError);
    expect((error as ThreadApprovalError).status).toBe(409);
    expect((error as ThreadApprovalError).message).toContain(
      '요청 상태가 방금 바뀌었어요',
    );
  });

  it('지정되지 않은 사람에게는 404 문구가 간다', async () => {
    fetcher.mockResolvedValue(new Response(null, { status: 404 }));

    await expect(listThreadApprovals(THREAD)).rejects.toThrow(
      '승인 요청을 찾을 수 없어요.',
    );
  });

  it('본문이 JSON이 아니면 파서 오류 대신 고정 문구가 간다', async () => {
    fetcher.mockResolvedValue(new Response('<html>', { status: 200 }));

    await expect(listThreadApprovals(THREAD)).rejects.toThrow(
      '서버 응답을 읽지 못했어요.',
    );
  });
});
