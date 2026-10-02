import { beforeEach, expect, it, vi } from 'vitest';
vi.mock('./http', () => ({ authFetch: vi.fn() }));
import { authFetch } from './http';
import {
  changeThreadDocument,
  downloadThreadDocument,
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
