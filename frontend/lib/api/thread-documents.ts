/** Thread 문서는 사용자 토큰으로 BFF만 호출한다. 재시도에도 등록·변경 식별자는 유지한다. */
import { bffUrl } from './config';
import { friendlyMessageForCode, parseErrorEnvelope } from './errors';
import { authFetch } from './http';

export interface ThreadDocument {
  id: string;
  fileName: string;
  size: number;
  status: 'UPLOADING' | 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED';
  pinned: boolean;
  own: boolean;
  canPin: boolean;
  canUnpin: boolean;
  canDelete: boolean;
  canReadOriginal: boolean;
  createdAt: string;
}
export interface ThreadDocumentsListing {
  threadStatus: string;
  canUpload: boolean;
  documents: ThreadDocument[];
}
const path = (thread: string) =>
  `/api/threads/${encodeURIComponent(thread)}/documents`;

/** 화면이 접근권 회수(401·403·404)와 일시 실패를 가를 수 있게 상태 코드를 싣는다. message는 사용자 문구다. */
export class ThreadDocumentError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

async function fetchDocument(
  url: string,
  init?: RequestInit,
): Promise<Response> {
  try {
    return await authFetch(url, init);
  } catch (cause) {
    if (cause instanceof TypeError)
      throw new ThreadDocumentError(
        '문서 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
      );
    throw cause;
  }
}

// 실 BFF는 에러 봉투의 code로 문구를 고른다. 봉투가 없는 응답(목업·프록시)만 상태 코드로 고른다.
async function checked(response: Response): Promise<Response> {
  if (response.ok) return response;
  const envelope = parseErrorEnvelope(await response.text().catch(() => ''));
  if (envelope?.code)
    throw new ThreadDocumentError(
      friendlyMessageForCode(envelope.code),
      response.status,
    );
  const messages: Record<number, string> = {
    400: '파일과 입력값을 확인해 주세요.',
    401: '세션이 만료되었어요. 다시 로그인해 주세요.',
    403: '이 문서를 변경할 권한이 없어요.',
    404: '방이나 문서에 접근할 수 없어요.',
    409: '방이나 문서 상태가 방금 바뀌었어요. 목록을 새로고침한 뒤 다시 시도해 주세요.',
    413: '파일은 10MB까지 등록할 수 있어요.',
    415: 'TXT·MD·CSV·PDF·DOCX 파일만 등록할 수 있어요.',
    503: '문서 저장소에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
  };
  throw new ThreadDocumentError(
    messages[response.status] ?? '문서 요청을 처리하지 못했어요.',
    response.status,
  );
}

/** 프록시가 HTML 200을 돌려주는 등 본문이 JSON이 아니면 파서의 영문 오류 대신 고정 문구를 보인다. */
async function json<T>(response: Response): Promise<T> {
  try {
    return (await response.json()) as T;
  } catch {
    throw new ThreadDocumentError(
      '문서 서비스 응답을 읽지 못했어요. 잠시 후 다시 시도해 주세요.',
    );
  }
}
export async function listThreadDocuments(
  thread: string,
  signal?: AbortSignal,
): Promise<ThreadDocumentsListing> {
  const response = await checked(
    await fetchDocument(bffUrl(path(thread)), { signal, cache: 'no-store' }),
  );
  return json<ThreadDocumentsListing>(response);
}
export async function uploadThreadDocument(
  thread: string,
  id: string,
  file: File,
): Promise<ThreadDocument> {
  const body = new FormData();
  body.append('file', file);
  const response = await checked(
    await fetchDocument(bffUrl(`${path(thread)}?documentId=${id}`), {
      method: 'POST',
      body,
    }),
  );
  return json<ThreadDocument>(response);
}
export async function changeThreadDocument(
  thread: string,
  id: string,
  action: 'pin' | 'unpin' | 'delete',
  requestId: string,
): Promise<void> {
  const base = `${path(thread)}/${encodeURIComponent(id)}`;
  await checked(
    await fetchDocument(
      bffUrl(
        action === 'delete' ? `${base}?requestId=${requestId}` : `${base}/pin`,
      ),
      action === 'delete'
        ? { method: 'DELETE' }
        : {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ requestId, pinned: action === 'pin' }),
          },
    ),
  );
}
export async function downloadThreadDocument(thread: string, id: string) {
  const response = await checked(
    await fetchDocument(
      bffUrl(`${path(thread)}/${encodeURIComponent(id)}/original`),
      { cache: 'no-store' },
    ),
  );
  return response.blob();
}
