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
  it('방이 잠겨도 이미 반영된 변경의 재전송은 성공하고 새 변경은 막으며 다시 처리를 내주지 않는다', async () => {
    const mock = new ThreadDocumentsMock();
    const id = crypto.randomUUID();
    const body = new FormData();
    body.append('file', new File(['text'], 'x.txt'));
    await mock.handle(
      new Request(
        `http://local/api/threads/${NORMAL_THREAD_ID}/documents?documentId=${id}`,
        { method: 'POST', body },
      ),
      [NORMAL_THREAD_ID, 'documents'],
    );
    mock.fail(NORMAL_THREAD_ID, id);
    const pin = (requestId: string, active: boolean) =>
      mock.handle(
        new Request('http://local', {
          method: 'PUT',
          body: JSON.stringify({ pinned: true, requestId }),
        }),
        [NORMAL_THREAD_ID, 'documents', id, 'pin'],
        'mock-owner',
        true,
        active,
      );
    const applied = crypto.randomUUID();
    expect((await pin(applied, true)).status).toBe(204);
    expect((await pin(applied, false)).status).toBe(204);
    expect((await pin(crypto.randomUUID(), false)).status).toBe(409);
    const locked = await (
      await mock.handle(
        new Request('http://local'),
        [NORMAL_THREAD_ID, 'documents'],
        'mock-owner',
        true,
        false,
      )
    ).json();
    expect(locked.documents[0].status).toBe('FAILED');
    expect(locked.documents[0].canReprocess).toBe(false);
  });
  it('처리 실패 문서만 재처리하고 같은 요청 재전송은 멱등이다', async () => {
    const mock = new ThreadDocumentsMock();
    const id = crypto.randomUUID();
    const body = new FormData();
    body.append('file', new File(['text'], 'x.txt'));
    await mock.handle(
      new Request(
        `http://local/api/threads/${NORMAL_THREAD_ID}/documents?documentId=${id}`,
        { method: 'POST', body },
      ),
      [NORMAL_THREAD_ID, 'documents'],
    );
    const requestId = crypto.randomUUID();
    const reprocess = () =>
      mock.handle(
        new Request('http://local', {
          method: 'POST',
          body: JSON.stringify({ requestId }),
        }),
        [NORMAL_THREAD_ID, 'documents', id, 'reprocess'],
      );
    expect((await reprocess()).status).toBe(409);
    mock.fail(NORMAL_THREAD_ID, id);
    const failed = await (
      await mock.handle(new Request('http://local'), [
        NORMAL_THREAD_ID,
        'documents',
      ])
    ).json();
    expect(failed.documents[0].canReprocess).toBe(true);
    const fresh = crypto.randomUUID();
    expect(
      (
        await mock.handle(
          new Request('http://local', {
            method: 'POST',
            body: JSON.stringify({ requestId: fresh }),
          }),
          [NORMAL_THREAD_ID, 'documents', id, 'reprocess'],
        )
      ).status,
    ).toBe(204);
    expect(
      (
        await mock.handle(
          new Request('http://local', {
            method: 'POST',
            body: JSON.stringify({ requestId: fresh }),
          }),
          [NORMAL_THREAD_ID, 'documents', id, 'reprocess'],
        )
      ).status,
    ).toBe(204);
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
  it('출력은 목업에 생성기가 없어 빈 목록이고 접근 거부된 방은 404다', async () => {
    const mock = new ThreadDocumentsMock();
    const listed = await mock.handle(new Request('http://local'), [
      NORMAL_THREAD_ID,
      'outputs',
    ]);
    expect(listed.status).toBe(200);
    expect(await listed.json()).toEqual([]);
    expect(
      (
        await mock.handle(new Request('http://local'), [
          FORBIDDEN_FRAME_THREAD_ID,
          'outputs',
        ])
      ).status,
    ).toBe(404);
  });
});
