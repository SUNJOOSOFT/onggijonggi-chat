'use client';

/********************************************************
 파일명 : approval-dialog.tsx (components/collab)
 설 명 : 지정된 사람이 승인 요청을 처리하는 모달(이슈 #407). 요청된 메시지를 보여 주고 승인하거나, 반려 사유를
 (선택으로) 적어 반려한다. 사유는 반려에서만 받고 승인에는 받지 않는다.

 처리가 끝나면 onDecided로 알려 목록을 다시 읽게 한다. 이미 다른 경로로 처리됐거나 방이 잠겨 서버가 거절하면
 그 사유를 모달 안에 보여 주고 열어 둔다 — 닫아 버리면 왜 안 됐는지 알 수 없다.
 *********************************************************/

import { useEffect, useState } from 'react';

import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import {
  APPROVAL_REASON_MAX,
  type ThreadApproval,
  decideThreadApproval,
} from '@/lib/api/thread-approvals';

export function ApprovalDialog({
  threadId,
  approval,
  messageContent,
  requesterName,
  onDecided,
  onClose,
}: {
  threadId: string;
  /** 처리할 요청. null이면 닫혀 있다. */
  approval: ThreadApproval | null;
  /** 요청된 메시지의 본문. 화면이 아직 갖고 있지 않으면 null이다. */
  messageContent: string | null;
  requesterName: string;
  onDecided: () => void;
  onClose: () => void;
}) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const approvalId = approval?.id ?? null;

  // 다른 요청을 열면 이전 요청의 입력과 오류를 남기지 않는다.
  // biome-ignore lint/correctness/useExhaustiveDependencies: 요청이 바뀔 때만 비운다
  useEffect(() => {
    setRejecting(false);
    setReason('');
    setBusy(false);
    setError(null);
  }, [approvalId]);

  async function decide(approved: boolean) {
    if (approval === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      await decideThreadApproval(threadId, approval.id, { approved, reason });
      onDecided();
      onClose();
    } catch (cause) {
      setError(
        cause instanceof Error ? cause.message : '요청을 처리하지 못했어요.',
      );
      setBusy(false);
    }
  }

  return (
    <AlertDialog
      open={approval !== null}
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>승인 요청</AlertDialogTitle>
          <AlertDialogDescription>
            {requesterName}님이 아래 메시지의 승인을 요청했습니다.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <blockquote className="max-h-48 overflow-y-auto whitespace-pre-wrap rounded-md border-l-4 bg-muted/60 px-3 py-2 text-sm">
          {messageContent ?? '요청된 메시지를 불러오지 못했습니다.'}
        </blockquote>

        {rejecting && (
          <Textarea
            aria-label="반려 사유"
            placeholder="반려 사유 (선택)"
            maxLength={APPROVAL_REASON_MAX}
            value={reason}
            disabled={busy}
            onChange={(event) => setReason(event.target.value)}
          />
        )}

        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={onClose}>
            나중에
          </Button>
          {rejecting ? (
            <>
              <Button
                variant="outline"
                disabled={busy}
                onClick={() => setRejecting(false)}
              >
                취소
              </Button>
              <Button
                variant="destructive"
                disabled={busy}
                onClick={() => decide(false)}
              >
                반려 확정
              </Button>
            </>
          ) : (
            <>
              <Button
                variant="destructive"
                disabled={busy}
                onClick={() => setRejecting(true)}
              >
                반려
              </Button>
              <Button disabled={busy} onClick={() => decide(true)}>
                승인
              </Button>
            </>
          )}
        </div>
      </AlertDialogContent>
    </AlertDialog>
  );
}
