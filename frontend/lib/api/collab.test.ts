import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./http', () => ({ authFetch: vi.fn() }));

import { createCollabThread, fetchWorkspaces } from './collab';
import { authFetch } from './http';

const mockedAuthFetch = vi.mocked(authFetch);

describe('createCollabThread', () => {
  beforeEach(() => {
    mockedAuthFetch.mockReset();
  });

  it('제목만 담은 POST로 새 방 id를 요청한다', async () => {
    mockedAuthFetch.mockResolvedValue(
      Response.json({ id: '11111111-1111-4111-8111-111111111111' }),
    );

    await expect(createCollabThread('  새 방  ')).resolves.toEqual({
      id: '11111111-1111-4111-8111-111111111111',
    });
    expect(mockedAuthFetch).toHaveBeenCalledExactlyOnceWith(
      '/api/collab/threads',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: '  새 방  ' }),
      },
    );
  });

  it('실패 응답 본문을 호출부가 오류 봉투로 해석할 수 있게 보존한다', async () => {
    mockedAuthFetch.mockResolvedValue(
      Response.json({ error: { code: 'VALIDATION_ERROR' } }, { status: 400 }),
    );

    await expect(createCollabThread('')).rejects.toThrow('VALIDATION_ERROR');
  });

  it('워크스페이스를 고르면 본문에 workspaceId를 싣고, 재시도 키는 헤더로 보낸다', async () => {
    mockedAuthFetch.mockResolvedValue(
      Response.json({ id: '11111111-1111-4111-8111-111111111111' }),
    );

    await createCollabThread('인사팀 방', 'ws-1', 'key-1');

    expect(mockedAuthFetch).toHaveBeenCalledExactlyOnceWith(
      '/api/collab/threads',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'key-1',
        },
        body: JSON.stringify({ title: '인사팀 방', workspaceId: 'ws-1' }),
      },
    );
  });
});

describe('fetchWorkspaces', () => {
  beforeEach(() => {
    mockedAuthFetch.mockReset();
  });

  it('서버가 준 워크스페이스 목록을 그대로 돌려준다', async () => {
    const workspaces = [
      { id: 'c', parentId: null, name: '공용', kind: 'COMMON', depth: 1 },
      { id: 'hr', parentId: null, name: '인사팀', kind: 'ORG', depth: 1 },
    ];
    mockedAuthFetch.mockResolvedValue(Response.json(workspaces));

    await expect(fetchWorkspaces()).resolves.toEqual(workspaces);
    expect(mockedAuthFetch).toHaveBeenCalledExactlyOnceWith('/api/workspaces');
  });
});
