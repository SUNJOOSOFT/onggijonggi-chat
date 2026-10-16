'use client';

/********************************************************
 파일명 : approval-request-dialog.tsx (components/collab)
 설 명 : 메시지에 대한 승인을 요청할 참여자를 고르는 모달(이슈 #407). 본문의 `@이름`을 해석하지 않고 명단에서
 직접 고르게 한다. 후보는 나를 뺀 활성 참여자 중 방에 들어올 권한이 있는 사람이며, 서버가 같은 조건으로 한 번 더
 검증한다.

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
  onRequested,
  onClose,
}: {
  threadId: string;
  /** 승인을 요청할 메시지. null이면 닫혀 있다. */
  messageId: string | null;
  onRequested: () => void;
  onClose: () => void;
}) {
  const [candidates, setCandidates] = useState<ThreadParticipant[] | null>(
    null,
  );
  const [requestId, setRequestId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 열 때마다 명단을 새로 읽고 요청 id를 새로 만든다.
  // biome-ignore lint/correctness/useExhaustiveDependencies: 열리거나 닫힐 때만 다시 준비한다
  useEffect(() => {
    setError(null);
    setBusy(false);
    setCandidates(null);
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

  async function request(target: ThreadParticipant) {
    if (messageId === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      await requestThreadApproval(threadId, {
        id: requestId,
        messageId,
        targetSubject: target.subject,
      });
      toast.success(`${target.displayName}님에게 승인을 요청했습니다`);
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
          <AlertDialogTitle>승인 요청</AlertDialogTitle>
          <AlertDialogDescription>
            이 메시지를 승인할 사람을 고르세요.
          </AlertDialogDescription>
        </AlertDialogHeader>

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
                variant="ghost"
                className="w-full justify-start"
                disabled={busy}
                title={candidate.subject}
                onClick={() => request(candidate)}
              >
                {candidate.displayName}
              </Button>
            </li>
          ))}
        </ul>

        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}

        <div className="flex justify-end">
          <Button variant="outline" disabled={busy} onClick={onClose}>
            닫기
          </Button>
        </div>
      </AlertDialogContent>
    </AlertDialog>
  );
}
