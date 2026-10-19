import { beforeEach, expect, it, vi } from 'vitest';
vi.mock('./http', () => ({ authFetch: vi.fn() }));
import { authFetch } from './http';
import {
  changeThreadDocument,
  downloadThreadDocument,
  downloadThreadOutput,
  listThreadOutputs,
} from './thread-documents';
const fetcher = vi.mocked(authFetch);
beforeEach(() => fetcher.mockReset());
it('실 BFF 오류는 code 문구를 쓰고 서버 message는 보이지 않는다', async () => {
  fetcher.mockResolvedValue(
    Response.json(
      {
        error: {
          code: 'DOCUMENT_STATE_CONFLICT',
          message: 'internal detail',
          traceId: 'trace',
        },
      },
      { status: 409 },
    ),
  );
  await expect(
    changeThreadDocument('room', 'doc', 'pin', 'request'),
  ).rejects.toThrow('방이나 문서 상태가 방금 바뀌었어요');
  fetcher.mockResolvedValue(
    Response.json(
      {
        error: { code: 'DOCUMENT_STORAGE_UNAVAILABLE', message: 'worker url' },
      },
      { status: 503 },
    ),
  );
  await expect(downloadThreadDocument('room', 'doc')).rejects.toThrow(
    '문서 저장소에 연결하지 못했어요',
  );
});
it('봉투가 없는 응답(목업)은 상태 코드로 문구를 고른다', async () => {
  fetcher.mockResolvedValue(new Response(null, { status: 415 }));
  await expect(downloadThreadDocument('room', 'doc')).rejects.toThrow(
    'TXT·MD·CSV·PDF·DOCX 파일만 등록할 수 있어요.',
  );
});
it('출력 목록과 내려받기는 방 주소 아래 outputs 경로를 쓴다', async () => {
  fetcher.mockResolvedValue(Response.json([]));
  await expect(listThreadOutputs('room 1')).resolves.toEqual([]);
  expect(String(fetcher.mock.calls[0][0])).toMatch(
    /\/api\/threads\/room%201\/outputs$/,
  );
  fetcher.mockResolvedValue(new Response('x'));
  await downloadThreadOutput('room', 'out/1');
  expect(String(fetcher.mock.calls[1][0])).toMatch(
    /\/api\/threads\/room\/outputs\/out%2F1\/download$/,
  );
});
it('출력 내려받기의 저장소 장애는 문서 저장소 문구로 알린다', async () => {
  fetcher.mockResolvedValue(
    Response.json(
      { error: { code: 'DOCUMENT_STORAGE_UNAVAILABLE', message: 'worker' } },
      { status: 503 },
    ),
  );
  await expect(downloadThreadOutput('room', 'out')).rejects.toThrow(
    '문서 저장소에 연결하지 못했어요',
  );
});
