'use client';

/********************************************************
 파일명 : approval-request-dialog.tsx (components/collab)
 설 명 : 메시지에 대한 승인을 요청할 참여자를 고르는 모달(이슈 #407). 본문의 `@이름`을 해석하지 않고 명단에서
 직접 고르게 한다. 후보는 나를 뺀 활성 참여자 중 방에 들어올 권한이 있는 사람이며, 서버가 같은 조건으로 한 번 더
 검증한다.

 사람을 누르는 것만으로는 요청이 나가지 않는다 — 고른 뒤 "요청 보내기"를 눌러야 나간다(이슈 #416). 위에 요청할
 메시지의 앞부분을 보여 어느 글에 대한 요청인지 알 수 있게 하고, 그 글에 이미 대기 중인 요청이 있으면 후보 전원을
 비활성으로 두고 요청이 간 사람 옆에 표시한다.

 요청 id는 모달을 열 때마다 새로 만든다. 응답을 잃고 다시 눌러도 같은 id라 서버가 요청 하나로 합친다.
 *********************************************************/

import { useEffect, useState } from 'react';
import { toast } from 'sonner';

import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import {
  type ThreadParticipant,
  fetchThreadParticipants,
} from '@/lib/api/collab';
import { requestThreadApproval } from '@/lib/api/thread-approvals';

/** 승인권자로 지정할 수 있는 사람. */
export function approvalCandidates(
  participants: ThreadParticipant[],
): ThreadParticipant[] {
  return participants.filter(
    (participant) =>
      !participant.self && !participant.pending && participant.workspaceAccess,
  );
}

export function ApprovalRequestDialog({
  threadId,
  messageId,
  preview = null,
  pendingTargetSubject = null,
  onRequested,
  onClose,
}: {
  threadId: string;
  /** 승인을 요청할 메시지. null이면 닫혀 있다. */
  messageId: string | null;
  /** 요청할 메시지의 보낸 사람과 본문. 화면이 아직 갖고 있지 않으면 null이다. */
  preview?: { label: string; content: string } | null;
  /** 이 메시지에 이미 대기 중인 요청이 있으면 그 요청의 승인권자 subject. 없으면 null이다. */
  pendingTargetSubject?: string | null;
  onRequested: () => void;
  onClose: () => void;
}) {
  const [candidates, setCandidates] = useState<ThreadParticipant[] | null>(
    null,
  );
  const [selected, setSelected] = useState<ThreadParticipant | null>(null);
  const [requestId, setRequestId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const alreadyPending = pendingTargetSubject !== null;

  // 열 때마다 명단을 새로 읽고 요청 id를 새로 만든다.
  // biome-ignore lint/correctness/useExhaustiveDependencies: 열리거나 닫힐 때만 다시 준비한다
  useEffect(() => {
    setError(null);
    setBusy(false);
    setCandidates(null);
    setSelected(null);
    if (messageId === null) return;
    setRequestId(crypto.randomUUID());
    let alive = true;
    fetchThreadParticipants(threadId)
      .then((participants) => {
        if (alive) setCandidates(approvalCandidates(participants));
      })
      .catch(() => {
        if (alive) setError('참여자 명단을 불러오지 못했어요.');
      });
    return () => {
      alive = false;
    };
  }, [threadId, messageId]);

  async function request() {
    if (messageId === null || selected === null || busy || alreadyPending)
      return;
    setBusy(true);
    setError(null);
    try {
      await requestThreadApproval(threadId, {
        id: requestId,
        messageId,
        targetSubject: selected.subject,
      });
      toast.success(`${selected.displayName}님에게 승인을 요청했습니다`);
      onRequested();
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '요청하지 못했어요.');
      setBusy(false);
    }
  }

  return (
    <AlertDialog
      open={messageId !== null}
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>승인 요청 보내기</AlertDialogTitle>
          <AlertDialogDescription>
            이 메시지를 승인할 사람을 고르세요.
          </AlertDialogDescription>
        </AlertDialogHeader>

        {preview && (
          <div className="flex flex-col gap-0.5 rounded-md border-l-4 border-primary bg-muted/60 px-3 py-2">
            <span className="text-xs font-medium text-primary">
              {preview.label}
            </span>
            <span className="line-clamp-2 whitespace-pre-wrap text-sm">
              {preview.content}
            </span>
          </div>
        )}

        {alreadyPending && (
          <p
            role="status"
            className="rounded-md bg-amber-100 px-3 py-2 text-sm text-amber-900 dark:bg-amber-500/20 dark:text-amber-200"
          >
            이 글에는 이미 대기 중인 요청이 있습니다. 처리된 뒤에 다시 요청할 수
            있습니다.
          </p>
        )}

        {candidates === null && !error && (
          <p className="text-sm text-muted-foreground">불러오는 중…</p>
        )}
        {candidates?.length === 0 && (
          <p className="text-sm text-muted-foreground">
            요청할 수 있는 참여자가 없습니다.
          </p>
        )}
        <ul className="flex max-h-60 flex-col gap-1 overflow-y-auto">
          {candidates?.map((candidate) => (
            <li key={candidate.subject}>
              <Button
                variant={
                  selected?.subject === candidate.subject
                    ? 'secondary'
                    : 'ghost'
                }
                aria-pressed={selected?.subject === candidate.subject}
                className="w-full justify-between"
                disabled={busy || alreadyPending}
                title={candidate.subject}
                onClick={() => setSelected(candidate)}
              >
                {candidate.displayName}
                {candidate.subject === pendingTargetSubject && (
                  <span className="text-xs font-medium text-amber-700 dark:text-amber-300">
                    이미 요청함
                  </span>
                )}
              </Button>
            </li>
          ))}
        </ul>

        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={onClose}>
            닫기
          </Button>
          <Button
            disabled={selected === null || busy || alreadyPending}
            onClick={request}
          >
            {selected === null
              ? '요청 보내기'
              : `${selected.displayName}에게 요청 보내기`}
          </Button>
        </div>
      </AlertDialogContent>
    </AlertDialog>
  );
}
