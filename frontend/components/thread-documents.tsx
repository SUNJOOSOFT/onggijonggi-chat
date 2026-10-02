'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import {
  changeThreadDocument,
  downloadThreadDocument,
  listThreadDocuments,
  uploadThreadDocument,
  type ThreadDocumentsListing,
} from '@/lib/api/thread-documents';

const statuses: Record<string, string> = {
  UPLOADING: '원본 저장 중',
  PENDING: '처리 대기',
  PROCESSING: '처리 중',
  READY: '검색 준비 완료',
  FAILED: '처리 실패',
};

/** 접근권이 사라졌다는 응답이면 목록을 지운다. 그 밖의 실패(네트워크·5xx·429)는 일시적이라 마지막 목록을 유지한다. */
function revoked(cause: unknown): boolean {
  const status = (cause as { status?: unknown } | null)?.status;
  return status === 401 || status === 403 || status === 404;
}
function aborted(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}
function messageOf(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback;
}

/** 일회성 첨부와 별도로, 생성된 방에 남기는 검색용 문서의 등록·고정을 관리한다. */
export function ThreadDocuments({
  threadId,
  available = true,
}: { threadId: string; available?: boolean }) {
  const [open, setOpen] = useState(false);
  const [listing, setListing] = useState<ThreadDocumentsListing | null>(null);
  const [file, setFile] = useState<File | null>(null);
  // 조회 오류는 다음 조회 성공이 지우고, 변경·내려받기 오류는 사용자가 다음 작업을 할 때까지 남긴다.
  const [listError, setListError] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmation, setConfirmation] = useState<string | null>(null);
  const sequence = useRef(0);
  const alive = useRef(true);
  const writing = useRef(false);
  const uploadId = useRef<string | null>(null);
  const changes = useRef(
    new Map<string, { action: 'pin' | 'unpin' | 'delete'; id: string }>(),
  );
  const scope = useRef(0);
  const reading = useRef(false);
  const request = useRef<AbortController | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    const version = ++sequence.current;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    reading.current = true;
    try {
      const next = await listThreadDocuments(threadId, controller.signal);
      if (alive.current && version === sequence.current) {
        setListing(next);
        setConfirmation((id) =>
          id && next.documents.some((doc) => doc.id === id && doc.canDelete)
            ? id
            : null,
        );
        setListError('');
        // 조회가 보여 준 최신 상태를 기준으로 다시 고르게 한다. 실패 뒤 재시도용으로 남긴 요청 UUID를 오래 두면,
        // 그사이 남이 반대로 바꾼 뒤 같은 버튼을 눌렀을 때 서버가 옛 요청의 재전송으로 보고 아무것도 바꾸지 않는다.
        changes.current.clear();
      }
    } catch (cause) {
      if (alive.current && version === sequence.current) {
        if (revoked(cause)) {
          setListing(null);
          setConfirmation(null);
        }
        setListError(messageOf(cause, '목록을 불러오지 못했어요.'));
      }
      throw cause;
    } finally {
      if (version === sequence.current) reading.current = false;
    }
  }, [threadId]);

  useEffect(() => {
    alive.current = true;
    setListing(null);
    setFile(null);
    setConfirmation(null);
    setListError('');
    setError('');
    setBusy(false);
    writing.current = false;
    uploadId.current = null;
    changes.current.clear();
    return () => {
      alive.current = false;
      scope.current += 1;
      request.current?.abort();
      reading.current = false;
      sequence.current += 1;
    };
  }, [threadId]);
  useEffect(() => {
    if (!open || !available) return;
    void refresh().catch(() => {});
    // 다른 참여자의 등록·처리 상태를 따라가는 폴링이다. 탭이 가려진 동안은 쉬고 돌아오면 바로 갱신한다.
    const poll = () => {
      if (document.visibilityState === 'hidden') return;
      if (!writing.current && !reading.current) void refresh().catch(() => {});
    };
    const timer = setInterval(poll, 10_000);
    document.addEventListener('visibilitychange', poll);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', poll);
      sequence.current += 1;
      request.current?.abort();
      reading.current = false;
    };
  }, [open, available, refresh]);

  async function mutate(action: () => Promise<unknown>, uploaded = false) {
    if (writing.current) return;
    writing.current = true;
    request.current?.abort();
    reading.current = false;
    sequence.current += 1;
    const generation = scope.current;
    setBusy(true);
    setError('');
    try {
      await action();
      if (!alive.current || generation !== scope.current) return;
      setConfirmation(null);
      if (uploaded) {
        setFile(null);
        uploadId.current = null;
        if (input.current) input.current.value = '';
      }
      try {
        await refresh();
      } catch (cause) {
        // 패널을 접어 조회가 취소된 것은 실패가 아니다. 다시 펼치면 새로 조회한다.
        if (alive.current && generation === scope.current && !aborted(cause))
          setError(
            '변경은 저장됐지만 목록을 갱신하지 못했어요. 목록을 다시 조회해 주세요.',
          );
      }
    } catch (cause) {
      if (alive.current && generation === scope.current)
        setError(messageOf(cause, '문서를 변경하지 못했어요.'));
    } finally {
      if (alive.current && generation === scope.current) {
        writing.current = false;
        setBusy(false);
      }
    }
  }

  async function download(id: string, name: string) {
    const generation = scope.current;
    setError('');
    try {
      const blob = await downloadThreadDocument(threadId, id);
      if (!alive.current || generation !== scope.current) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = name;
      link.click();
      // 같은 틱에 해제하면 일부 브라우저가 내려받기를 시작하기 전에 취소한다.
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (cause) {
      if (alive.current && generation === scope.current)
        setError(messageOf(cause, '원본을 내려받지 못했어요.'));
    }
  }

  function change(id: string, action: 'pin' | 'unpin' | 'delete') {
    if (writing.current) return;
    const pending = changes.current.get(id);
    const operation =
      pending?.action === action
        ? pending
        : { action, id: crypto.randomUUID() };
    changes.current.set(id, operation);
    void mutate(async () => {
      await changeThreadDocument(threadId, id, action, operation.id);
      if (changes.current.get(id) === operation) changes.current.delete(id);
    });
  }

  const target = listing?.documents.find((doc) => doc.id === confirmation);
  // 삭제 확인창이 열려 있으면 변경 오류는 확인창 안에 보인다(뒤 화면은 화면 낭독기에서 가려진다).
  const errors = [
    { key: 'list', message: listError },
    { key: 'action', message: confirmation ? '' : error },
  ].filter((entry) => entry.message);

  return (
    <section className="border-b px-4 py-2 text-sm" aria-label="방 문서">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="-ml-2"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        {open ? '▾' : '▸'} 방 문서 · 등록 및 고정
      </Button>
      {open && !available && (
        <p className="mt-2 text-muted-foreground">
          첫 메시지를 보내 방을 만든 뒤 등록할 수 있습니다. 일회성 첨부는
          입력창에서 사용해주세요.
        </p>
      )}
      {open && available && (
        // 모바일 높이에서 패널이 채팅 입력창을 화면 밖으로 밀어내지 않게 패널 자체를 스크롤 영역으로 둔다.
        <div className="mt-2 max-h-[40dvh] space-y-3 overflow-y-auto pb-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="flex-1 text-muted-foreground">
              등록만으로 AI가 사용하지 않습니다. 고정된 문서 중 처리가 완료된
              문서만 검색 대상입니다.
            </p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => void refresh().catch(() => {})}
            >
              목록 다시 조회
            </Button>
          </div>
          {errors.map(({ key, message }) => (
            <p key={key} role="alert" className="text-destructive">
              {message}
            </p>
          ))}
          {listing && !listing.canUpload && <p>이 방은 읽기 전용입니다.</p>}
          {listing?.canUpload && (
            <form
              className="flex flex-wrap items-center gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                if (!file) return;
                const id = uploadId.current ?? crypto.randomUUID();
                uploadId.current = id;
                void mutate(
                  () => uploadThreadDocument(threadId, id, file),
                  true,
                );
              }}
            >
              <input
                ref={input}
                type="file"
                aria-label="등록할 방 문서"
                accept=".txt,.md,.csv,.pdf,.docx"
                disabled={busy}
                className="max-w-full text-xs file:mr-2 file:rounded-md file:border file:bg-background file:px-2 file:py-1"
                onChange={(event) => {
                  const chosen = event.target.files?.[0] ?? null;
                  setFile(chosen);
                  uploadId.current = null;
                }}
              />
              <Button type="submit" size="sm" disabled={busy || !file}>
                문서 등록
              </Button>
              <span className="text-xs text-muted-foreground">
                최대 10MB · TXT/MD/CSV/PDF/DOCX
              </span>
            </form>
          )}
          {listing?.documents.length === 0 && <p>등록된 문서가 없습니다.</p>}
          {/* 처리 상태가 바뀌면 화면 낭독기가 알린다. 같은 내용의 폴링은 DOM이 바뀌지 않아 다시 읽지 않는다. */}
          <ul className="space-y-2" aria-live="polite">
            {listing?.documents.map((doc) => (
              <li
                key={doc.id}
                className="flex flex-wrap items-center gap-2 rounded-md border p-3"
              >
                <div className="min-w-0 flex-1">
                  {/* 버튼 이름은 짧게 두고, 어느 문서의 버튼인지는 설명으로 붙인다. */}
                  <span id={`thread-document-${doc.id}`} className="break-all">
                    {doc.fileName}
                  </span>
                  <p className="text-xs text-muted-foreground">
                    {statuses[doc.status] ?? doc.status} ·{' '}
                    {Math.ceil(doc.size / 1024)}KB
                    {doc.pinned ? ' · 고정됨' : ''}
                  </p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  aria-describedby={`thread-document-${doc.id}`}
                  disabled={busy || !doc.canReadOriginal}
                  onClick={() => void download(doc.id, doc.fileName)}
                >
                  원본
                </Button>
                {(doc.pinned ? doc.canUnpin : doc.canPin) && (
                  <Button
                    type="button"
                    variant={doc.pinned ? 'secondary' : 'outline'}
                    size="sm"
                    aria-describedby={`thread-document-${doc.id}`}
                    disabled={busy}
                    onClick={() => change(doc.id, doc.pinned ? 'unpin' : 'pin')}
                  >
                    {doc.pinned ? '고정 해제' : '고정'}
                  </Button>
                )}
                {doc.canDelete && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="text-destructive"
                    aria-describedby={`thread-document-${doc.id}`}
                    disabled={busy}
                    onClick={() => setConfirmation(doc.id)}
                  >
                    삭제
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      <AlertDialog
        open={confirmation !== null}
        onOpenChange={(next) => {
          if (!next && !writing.current) setConfirmation(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>방 문서를 삭제할까요?</AlertDialogTitle>
            <AlertDialogDescription className="break-all">
              {target ? `"${target.fileName}"을(를) ` : ''}삭제하면 이후
              답변에서 사용할 수 없으며 원본도 정리됩니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>취소</AlertDialogCancel>
            <AlertDialogAction
              disabled={busy}
              onClick={(event) => {
                // 실패하면 확인창을 남겨 같은 요청으로 다시 시도하게 한다. 성공하면 mutate가 닫는다.
                event.preventDefault();
                if (confirmation) change(confirmation, 'delete');
              }}
            >
              삭제 확인
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}
