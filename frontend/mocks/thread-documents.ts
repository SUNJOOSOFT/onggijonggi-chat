/** 문서 목업은 상태·고정·삭제를 재현한다. ETL이 없는 상태에서 READY를 꾸며내지 않는다. */
import { roomAccess } from './rooms';
import type { ThreadDocument } from '@/lib/api/thread-documents';

type Stored = {
  value: ThreadDocument;
  bytes: ArrayBuffer;
  digest: string;
  uploader: string;
};
export class ThreadDocumentsMock {
  private rooms = new Map<string, Map<string, Stored>>();
  private events = new Map<string, { action: string; actor: string }>();
  private deleted = new Set<string>();

  async handle(
    request: Request,
    segments: string[],
    actor = 'mock-owner',
    owner = true,
    active = true,
  ) {
    const [thread, collection, id, operation] = segments;
    if (
      collection !== 'documents' ||
      !/^[a-f0-9-]{36}$/i.test(thread ?? '') ||
      roomAccess(thread) === 'deny'
    )
      return new Response(null, { status: 404 });
    const documents = this.rooms.get(thread) ?? new Map<string, Stored>();
    this.rooms.set(thread, documents);
    const view = (doc: Stored): ThreadDocument => ({
      ...doc.value,
      own: doc.uploader === actor,
      canPin: active,
      canUnpin: active && (owner || doc.uploader === actor),
      canDelete: active && (owner || doc.uploader === actor),
    });
    if (request.method === 'GET' && !id)
      return Response.json({
        threadStatus: active ? 'ACTIVE' : 'LOCKED',
        canUpload: active,
        documents: [...documents.values()].map(view),
      });
    if (request.method === 'GET' && operation === 'original') {
      const doc = documents.get(id);
      return doc
        ? new Response(doc.bytes.slice(0), {
            headers: {
              'Content-Type': 'application/octet-stream',
              'Cache-Control': 'no-store',
            },
          })
        : new Response(null, { status: 404 });
    }
    if (!active) return new Response(null, { status: 409 });
    const url = new URL(request.url);
    if (request.method === 'POST' && !id) {
      const documentId = url.searchParams.get('documentId');
      if (!documentId || !/^[a-f0-9-]{36}$/i.test(documentId))
        return new Response(null, { status: 400 });
      if (this.deleted.has(`${thread}:${documentId}`))
        return new Response(null, { status: 404 });
      const form = await request.formData();
      const file = form.get('file');
      if (
        !(file instanceof File) ||
        !file.size ||
        /[\\/]/.test(file.name) ||
        [...file.name].some(
          (character) =>
            character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127,
        ) ||
        // 서버와 같이 방향 제어 문자(확장자 위장)를 거부한다.
        /[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/.test(file.name) ||
        file.name.length > 255
      )
        return new Response(null, { status: 400 });
      if (file.size > 10 * 1024 * 1024)
        return new Response(null, { status: 413 });
      if (!/\.(txt|md|csv|pdf|docx)$/i.test(file.name))
        return new Response(null, { status: 415 });
      const bytes = await file.arrayBuffer();
      const digest = [
        ...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
      ]
        .map((value) => value.toString(16).padStart(2, '0'))
        .join('');
      const old = documents.get(documentId);
      if (old && old.uploader !== actor)
        return new Response(null, { status: 404 });
      if (old && (old.digest !== digest || old.value.fileName !== file.name))
        return new Response(null, { status: 409 });
      const doc = old ?? {
        value: {
          id: documentId,
          fileName: file.name,
          size: file.size,
          status: 'PENDING' as const,
          pinned: false,
          own: true,
          canPin: true,
          canUnpin: true,
          canDelete: true,
          canReadOriginal: true,
          createdAt: new Date().toISOString(),
        },
        bytes,
        digest,
        uploader: actor,
      };
      documents.set(documentId, doc);
      return Response.json(view(doc), { status: 201 });
    }
    if (request.method !== 'PUT' && request.method !== 'DELETE')
      return new Response(null, { status: 405 });
    const body = request.method === 'PUT' ? await request.json() : null;
    const requestId = body?.requestId ?? url.searchParams.get('requestId');
    if (
      !requestId ||
      !/^[a-f0-9-]{36}$/i.test(requestId) ||
      (request.method === 'PUT' &&
        (operation !== 'pin' || typeof body?.pinned !== 'boolean'))
    )
      return new Response(null, { status: 400 });
    const action =
      request.method === 'DELETE' ? 'delete' : body.pinned ? 'pin' : 'unpin';
    const key = `${thread}:${id}:${requestId}`;
    const previous = this.events.get(key);
    if (previous)
      return new Response(null, {
        status:
          previous.action === action && previous.actor === actor ? 204 : 409,
      });
    const doc = documents.get(id);
    if (!doc) return new Response(null, { status: 404 });
    if (action !== 'pin' && !owner && doc.uploader !== actor)
      return new Response(null, { status: 403 });
    if (
      (action === 'pin' && doc.value.pinned) ||
      (action === 'unpin' && !doc.value.pinned)
    )
      return new Response(null, { status: 409 });
    if (action === 'delete') {
      documents.delete(id);
      this.deleted.add(`${thread}:${id}`);
    } else doc.value.pinned = action === 'pin';
    this.events.set(key, { action, actor });
    return new Response(null, { status: 204 });
  }
}

const globalMock = globalThis as typeof globalThis & {
  threadDocumentsMock?: ThreadDocumentsMock;
};
export function sharedThreadDocumentsMock() {
  if (!globalMock.threadDocumentsMock)
    globalMock.threadDocumentsMock = new ThreadDocumentsMock();
  return globalMock.threadDocumentsMock;
}
