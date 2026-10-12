'use client';

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from 'react';
import { FileText, MoreHorizontal, Pin, RefreshCw, Upload } from 'lucide-react';
import { useMediaQuery } from 'usehooks-ts';
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
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet';
import { cn } from '@/lib/utils';
import {
  changeThreadDocument,
  downloadThreadDocument,
  listThreadDocuments,
  uploadThreadDocument,
  type ThreadDocument,
  type ThreadDocumentAction,
  type ThreadDocumentsListing,
} from '@/lib/api/thread-documents';

const statuses: Record<string, string> = {
  UPLOADING: '원본 저장 중',
  PENDING: '처리 대기',
  PROCESSING: '처리 중',
  READY: '검색 준비 완료',
  FAILED: '처리 실패',
};

/** 처리 상태와 고정 여부를 "AI가 이 문서를 쓰는가" 기준의 배지 하나로 합친다. */
function StatusBadge({ doc }: { doc: ThreadDocument }) {
  const using = doc.pinned && doc.status === 'READY';
  const tone = using
    ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300'
    : doc.status === 'FAILED'
      ? 'bg-destructive/10 text-destructive'
      : doc.status === 'READY'
        ? 'bg-muted text-muted-foreground'
        : 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300';
  return (
    <span className={cn('rounded-full px-2 py-0.5 font-medium', tone)}>
      {using ? '답변에 사용 중' : (statuses[doc.status] ?? doc.status)}
    </span>
  );
}

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

type ThreadDocumentsContext = {
  available: boolean;
  listing: ThreadDocumentsListing | null;
  total: number;
  pinned: number;
  busy: boolean;
  refresh: () => Promise<void>;
  /** 등록·목록 본문. 시트(좁은 화면)와 오른쪽 패널(넓은 화면) 중 보이는 한 곳에서만 그린다. */
  body: ReactNode;
  sheetOpen: boolean;
  setSheetOpen: (open: boolean) => void;
  setPanelActive: (active: boolean) => void;
};
const Context = createContext<ThreadDocumentsContext | null>(null);

function useDocuments(): ThreadDocumentsContext {
  const value = useContext(Context);
  if (!value) throw new Error('ThreadDocumentsProvider 안에서 써야 합니다.');
  return value;
}

/**
 * 일회성 첨부와 별도로, 생성된 방에 남기는 검색용 문서의 등록·고정을 관리한다.
 * 상태는 방마다 한 곳에 두고, 좁은 화면은 헤더 버튼의 시트(ThreadDocumentsButton)로,
 * 넓은 화면은 오른쪽 패널(ThreadDocumentsPanel)로 같은 목록을 보여 준다. 폴링은 둘 중 하나가 보일 때만 한다.
 */
export function ThreadDocumentsProvider({
  threadId,
  available = true,
  children,
}: { threadId: string; available?: boolean; children: ReactNode }) {
  const [sheetOpen, setSheetOpen] = useState(false);
  const [panelActive, setPanelActive] = useState(false);
  const open = sheetOpen || panelActive;
  const [listing, setListing] = useState<ThreadDocumentsListing | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
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
    new Map<string, { action: ThreadDocumentAction; id: string }>(),
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
    if (!available) return;
    // 닫힌 동안에도 헤더 버튼의 개수를 위해 한 번 읽는다. 열고 닫을 때마다 다시 읽는다.
    void refresh().catch(() => {});
    if (!open) return;
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

  function change(id: string, action: ThreadDocumentAction) {
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

  /** 파일을 고르면 바로 등록한다. 실패하면 파일과 등록 UUID를 남겨 "다시 시도"가 같은 요청을 보낸다. */
  function upload(chosen: File, retry = false) {
    if (writing.current) return;
    if (!retry) {
      setFile(chosen);
      uploadId.current = null;
    }
    const id = uploadId.current ?? crypto.randomUUID();
    uploadId.current = id;
    void mutate(() => uploadThreadDocument(threadId, id, chosen), true);
  }

  function discard() {
    setFile(null);
    uploadId.current = null;
    setError('');
    if (input.current) input.current.value = '';
  }

  const target = listing?.documents.find((doc) => doc.id === confirmation);
  // 삭제 확인창이 열려 있으면 변경 오류는 확인창 안에 보인다(뒤 화면은 화면 낭독기에서 가려진다).
  const errors = [
    { key: 'list', message: listError },
    { key: 'action', message: confirmation ? '' : error },
  ].filter((entry) => entry.message);
  const total = listing?.documents.length ?? 0;
  const pinned = listing?.documents.filter((doc) => doc.pinned).length ?? 0;

  const body = !available ? (
    <p className="text-sm text-muted-foreground">
      첫 메시지를 보내 방을 만든 뒤 등록할 수 있습니다. 일회성 첨부는 입력창에서
      사용해주세요.
    </p>
  ) : (
    <div className="flex flex-col gap-3 text-sm">
      {errors.map(({ key, message }) => (
        <p key={key} role="alert" className="text-destructive">
          {message}
        </p>
      ))}
      {listing && !listing.canUpload && (
        <p className="text-muted-foreground">이 방은 읽기 전용입니다.</p>
      )}
      {listing?.canUpload && (
        <label
          className={cn(
            'flex cursor-pointer flex-col items-center gap-1 rounded-lg border border-dashed p-4 text-center text-muted-foreground transition-colors hover:bg-muted/50 focus-within:ring-2 focus-within:ring-ring',
            dragging && 'border-primary bg-muted/50',
            busy && 'pointer-events-none opacity-60',
          )}
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            const dropped = event.dataTransfer.files?.[0];
            if (dropped && !busy) upload(dropped);
          }}
        >
          <Upload className="size-5" />
          <span className="text-foreground">
            파일을 끌어다 놓거나 눌러서 선택
          </span>
          <span className="text-xs">최대 10MB · TXT/MD/CSV/PDF/DOCX</span>
          <input
            ref={input}
            type="file"
            aria-label="등록할 방 문서"
            accept=".txt,.md,.csv,.pdf,.docx"
            disabled={busy}
            className="sr-only"
            onChange={(event) => {
              const chosen = event.target.files?.[0];
              if (chosen) upload(chosen);
            }}
          />
        </label>
      )}
      {file && (
        <div className="flex items-center gap-2 rounded-lg border p-3">
          <FileText className="size-4 shrink-0 text-muted-foreground" />
          <span className="min-w-0 flex-1 truncate" title={file.name}>
            {file.name}
          </span>
          {busy ? (
            <span className="text-xs text-muted-foreground">올리는 중…</span>
          ) : (
            <>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => upload(file, true)}
              >
                다시 시도
              </Button>
              <Button type="button" variant="ghost" size="sm" onClick={discard}>
                취소
              </Button>
            </>
          )}
        </div>
      )}
      {listing?.documents.length === 0 && (
        <p className="py-6 text-center text-muted-foreground">
          등록된 문서가 없습니다.
        </p>
      )}
      {/* 처리 상태가 바뀌면 화면 낭독기가 알린다. 같은 내용의 폴링은 DOM이 바뀌지 않아 다시 읽지 않는다. */}
      <ul className="space-y-2" aria-live="polite">
        {listing?.documents.map((doc) => (
          <li
            key={doc.id}
            className="flex items-start gap-2 rounded-lg border p-3"
          >
            <FileText className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
              {/* 버튼 이름은 짧게 두고, 어느 문서의 버튼인지는 설명으로 붙인다. */}
              <p
                id={`thread-document-${doc.id}`}
                className="truncate font-medium"
                title={doc.fileName}
              >
                {doc.fileName}
              </p>
              <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
                <StatusBadge doc={doc} />
                <span className="text-muted-foreground">
                  {Math.ceil(doc.size / 1024)}KB
                  {doc.pinned && doc.status !== 'READY' ? ' · 고정됨' : ''}
                </span>
              </div>
              {doc.status === 'FAILED' && doc.pinned && (
                // 검색 준비가 됐던 문서도 원본에서 다시 만들지 못하면 실패가 된다(#348). 고정돼 있어도 답변에서 빠진다는 것을 알린다.
                <p className="mt-1 text-xs text-destructive">
                  {doc.canReprocess
                    ? '답변에 쓰이지 않습니다. 다시 처리해 주세요.'
                    : '답변에 쓰이지 않습니다. 올린 사람이나 방장이 다시 처리할 수 있어요.'}
                </p>
              )}
              {doc.canReprocess && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="mt-2 h-7"
                  aria-describedby={`thread-document-${doc.id}`}
                  disabled={busy}
                  onClick={() => change(doc.id, 'reprocess')}
                >
                  다시 처리
                </Button>
              )}
            </div>
            {(doc.pinned ? doc.canUnpin : doc.canPin) && (
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className={cn(
                  'size-8 shrink-0',
                  doc.pinned ? 'text-primary' : 'text-muted-foreground',
                )}
                aria-label={doc.pinned ? '고정 해제' : '고정'}
                title={doc.pinned ? '고정 해제' : '고정'}
                aria-describedby={`thread-document-${doc.id}`}
                disabled={busy}
                onClick={() => change(doc.id, doc.pinned ? 'unpin' : 'pin')}
              >
                <Pin className={cn('size-4', doc.pinned && 'fill-current')} />
              </Button>
            )}
            {/* 메뉴에서 삭제 확인창을 열므로 모달로 두지 않는다(닫히는 메뉴가 확인창의 포커스를 빼앗지 않게). */}
            <DropdownMenu modal={false}>
              <DropdownMenuTrigger asChild>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="size-8 shrink-0 text-muted-foreground"
                  aria-label="문서 작업"
                  aria-describedby={`thread-document-${doc.id}`}
                >
                  <MoreHorizontal className="size-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem
                  disabled={busy || !doc.canReadOriginal}
                  onSelect={() => void download(doc.id, doc.fileName)}
                >
                  원본 내려받기
                </DropdownMenuItem>
                {doc.canDelete && (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                      className="text-destructive focus:text-destructive"
                      disabled={busy}
                      onSelect={() => setConfirmation(doc.id)}
                    >
                      삭제
                    </DropdownMenuItem>
                  </>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          </li>
        ))}
      </ul>
    </div>
  );

  return (
    <Context.Provider
      value={{
        available,
        listing,
        total,
        pinned,
        busy,
        refresh,
        body,
        sheetOpen,
        setSheetOpen,
        setPanelActive,
      }}
    >
      {children}
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
    </Context.Provider>
  );
}

/** 목록 다시 조회 버튼과 고정 안내. 시트와 패널 머리말에 같이 쓴다. */
function Refresh() {
  const { available, busy, refresh } = useDocuments();
  if (!available) return null;
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      className="size-7"
      aria-label="목록 다시 조회"
      title="목록 다시 조회"
      disabled={busy}
      onClick={() => void refresh().catch(() => {})}
    >
      <RefreshCw className="size-3.5" />
    </Button>
  );
}
const guide = (
  <>
    <Pin className="inline size-3.5 align-[-2px]" /> 고정한 문서 중 처리가 끝난
    문서만 AI 답변에 쓰입니다. 등록만 한 문서는 보관만 됩니다.
  </>
);

/** 좁은 화면용: 헤더 버튼(문서·고정 개수)으로 여는 오른쪽 시트. */
export function ThreadDocumentsButton({ className }: { className?: string }) {
  const { listing, total, pinned, body, sheetOpen, setSheetOpen } =
    useDocuments();
  return (
    <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
      <SheetTrigger asChild>
        <Button
          type="button"
          variant="outline"
          className={cn('gap-1.5 px-2 md:h-fit', className)}
          aria-label={
            listing ? `방 문서 ${total}개, 고정 ${pinned}개` : '방 문서'
          }
        >
          <FileText className="size-4" />
          {listing && <span className="tabular-nums">{total}</span>}
          {pinned > 0 && (
            <span className="flex items-center gap-0.5 text-xs text-muted-foreground tabular-nums">
              <Pin className="size-3" />
              {pinned}
            </span>
          )}
        </Button>
      </SheetTrigger>
      <SheetContent className="flex w-full flex-col gap-4 sm:max-w-md">
        <SheetHeader className="space-y-1 pr-6 text-left">
          <div className="flex items-center gap-2">
            <SheetTitle>방 문서</SheetTitle>
            <Refresh />
          </div>
          <SheetDescription>{guide}</SheetDescription>
        </SheetHeader>
        <div className="-mx-6 min-h-0 flex-1 overflow-y-auto px-6 pb-2">
          {body}
        </div>
      </SheetContent>
    </Sheet>
  );
}

/** 넓은 화면(md 이상)에서 보이는지 알린다. 보이는 동안만 폴링하고, 열려 있던 시트는 닫는다(본문을 두 곳에 그리지 않게). */
function usePanelActive() {
  const { setPanelActive, setSheetOpen } = useDocuments();
  const wide = useMediaQuery('(min-width: 768px)', {
    initializeWithValue: false,
  });
  useEffect(() => {
    if (!wide) return;
    setPanelActive(true);
    setSheetOpen(false);
    return () => setPanelActive(false);
  }, [wide, setPanelActive, setSheetOpen]);
}

/** 넓은 화면(md 이상)의 오른쪽 방 문서 패널. 1:1 채팅과 협업방이 같이 쓴다. 좁은 화면에서는 숨고 헤더 버튼의 시트가 대신한다. */
export function ThreadDocumentsPanel() {
  const { body } = useDocuments();
  usePanelActive();
  return (
    <aside
      aria-label="방 문서"
      className="hidden w-80 shrink-0 flex-col gap-2 border-l p-4 md:flex"
    >
      <div className="flex items-center gap-1">
        <h2 className="flex-1 text-sm font-semibold">방 문서</h2>
        <Refresh />
      </div>
      <p className="text-xs text-muted-foreground">{guide}</p>
      <div className="-mx-4 min-h-0 flex-1 overflow-y-auto px-4 pb-2">
        {body}
      </div>
    </aside>
  );
}
