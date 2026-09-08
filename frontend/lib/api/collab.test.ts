import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./http', () => ({ authFetch: vi.fn() }));

import { createCollabThread } from './collab';
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
});
