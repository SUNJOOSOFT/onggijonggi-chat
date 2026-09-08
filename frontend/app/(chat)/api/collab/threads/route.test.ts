import { describe, expect, it } from 'vitest';

import { GET, POST } from './route';

describe('POST /api/collab/threads mock', () => {
  it('제목 원문을 보존해 만들고 목록에서 다시 돌려준다', async () => {
    const title = '  목업 새 방  ';
    const response = await POST(
      new Request('http://localhost/api/collab/threads', {
        method: 'POST',
        body: JSON.stringify({ title }),
      }),
    );

    expect(response.status).toBe(201);
    const created = (await response.json()) as { id: string };
    expect(created.id).toMatch(/^[0-9a-f-]{36}$/i);

    const threads = (await (await GET()).json()) as Array<{
      id: string;
      title: string;
    }>;
    expect(threads).toContainEqual({
      id: created.id,
      title,
      participants: [],
    });
  });

  it('빈 제목과 잘못된 JSON을 기존 오류 코드로 거부한다', async () => {
    const blank = await POST(
      new Request('http://localhost/api/collab/threads', {
        method: 'POST',
        body: JSON.stringify({ title: '   ' }),
      }),
    );
    const malformed = await POST(
      new Request('http://localhost/api/collab/threads', {
        method: 'POST',
        body: '{',
      }),
    );

    await expect(blank.json()).resolves.toMatchObject({
      error: { code: 'VALIDATION_ERROR' },
    });
    await expect(malformed.json()).resolves.toMatchObject({
      error: { code: 'MALFORMED_REQUEST' },
    });
  });
});
