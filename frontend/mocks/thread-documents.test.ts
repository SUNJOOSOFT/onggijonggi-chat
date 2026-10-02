import { describe, expect, it } from 'vitest';
import { ThreadDocumentsMock } from './thread-documents';
import { NORMAL_THREAD_ID, FORBIDDEN_FRAME_THREAD_ID } from './rooms';

describe('문서 목업 계약', () => {
  it('등록은 PENDING·비고정이며 같은 UUID 재시도는 중복되지 않는다', async () => {
    const mock = new ThreadDocumentsMock();
    const id = crypto.randomUUID();
    const upload = () => {
      const body = new FormData();
      body.append('file', new File(['text'], 'x.txt'));
      return new Request(
        `http://local/api/threads/${NORMAL_THREAD_ID}/documents?documentId=${id}`,
        { method: 'POST', body },
      );
    };
    expect(
      (await mock.handle(upload(), [NORMAL_THREAD_ID, 'documents'])).status,
    ).toBe(201);
    expect(
      (await mock.handle(upload(), [NORMAL_THREAD_ID, 'documents'])).status,
    ).toBe(201);
    const listing = await (
      await mock.handle(new Request('http://local'), [
        NORMAL_THREAD_ID,
        'documents',
      ])
    ).json();
    expect(listing.documents).toHaveLength(1);
    expect(listing.documents[0]).toMatchObject({
      status: 'PENDING',
      pinned: false,
    });
    const requestId = crypto.randomUUID();
    const pin = () =>
      new Request('http://local', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestId, pinned: true }),
      });
    expect(
      (
        await mock.handle(
          pin(),
          [NORMAL_THREAD_ID, 'documents', id, 'pin'],
          'other',
          false,
        )
      ).status,
    ).toBe(204);
    expect(
      (
        await mock.handle(
          pin(),
          [NORMAL_THREAD_ID, 'documents', id, 'pin'],
          'other',
          false,
        )
      ).status,
    ).toBe(204);
    expect(
      (
        await mock.handle(
          new Request(`http://local?requestId=${crypto.randomUUID()}`, {
            method: 'DELETE',
          }),
          [NORMAL_THREAD_ID, 'documents', id],
          'other',
          false,
        )
      ).status,
    ).toBe(403);
    expect(
      (
        await mock.handle(
          new Request(`http://local?requestId=${crypto.randomUUID()}`, {
            method: 'DELETE',
          }),
          [NORMAL_THREAD_ID, 'documents', id],
        )
      ).status,
    ).toBe(204);
    expect(
      (
        await mock.handle(new Request('http://local'), [
          NORMAL_THREAD_ID,
          'documents',
          id,
          'original',
        ])
      ).status,
    ).toBe(404);
    expect(
      (await mock.handle(upload(), [NORMAL_THREAD_ID, 'documents'])).status,
    ).toBe(404);
  });
  it('방향 제어 문자가 든 파일명은 서버와 같이 400이다', async () => {
    const mock = new ThreadDocumentsMock();
    const body = new FormData();
    body.append('file', new File(['text'], 'report\u202Efdp.txt'));
    const response = await mock.handle(
      new Request(
        `http://local/api/threads/${NORMAL_THREAD_ID}/documents?documentId=${crypto.randomUUID()}`,
        { method: 'POST', body },
      ),
      [NORMAL_THREAD_ID, 'documents'],
    );
    expect(response.status).toBe(400);
  });
  it('접근 거부와 읽기 전용 상태에서 쓰기를 차단한다', async () => {
    const mock = new ThreadDocumentsMock();
    expect(
      (
        await mock.handle(new Request('http://local'), [
          FORBIDDEN_FRAME_THREAD_ID,
          'documents',
        ])
      ).status,
    ).toBe(404);
    expect(
      (
        await mock.handle(
          new Request('http://local', { method: 'POST' }),
          [NORMAL_THREAD_ID, 'documents'],
          'mock-owner',
          true,
          false,
        )
      ).status,
    ).toBe(409);
  });
});
