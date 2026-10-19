'use client';

/********************************************************
 파일명 : approval-card.tsx (components/collab)
 설 명 : 협업방 대화 흐름에 남는 승인 요청·처리 줄을 카드로 그린다(이슈 #416). 카드마다 원글의 앞부분을
 붙여 어떤 글에 대한 승인인지 줄만으로 알 수 있게 하고, 상태 칩과 반려 사유를 함께 보인다. 지정된 승인권자
 본인에게는 대기 중인 카드에 승인·반려 버튼을 보이고, 누르면 처리 모달이 열린다.

 원글 id가 없는 옛 줄(필드가 생기기 전에 저장된 것)은 어느 글인지 알 수 없어 예전처럼 한 줄 문구로만 보인다.
 원글을 이 화면이 아직 불러오지 않았으면 인용 없이 카드의 나머지만 그린다.

 대화 흐름의 항목은 모두 entryAnchorId 자리를 달고 있어, 원글 앞부분이나 원글 아래 상태 표시를 누르면 jumpToEntry가
 그 항목으로 스크롤한다.
 *********************************************************/

import { Button } from '@/components/ui/button';
import type { ThreadApproval } from '@/lib/api/thread-approvals';
import type { RoomApprovalNotice } from '@/lib/chat/room-state';
import {
  type OriginApprovalStatus,
  statusOf,
} from '@/lib/collab/approval-status';

/** 대화 흐름 항목(메시지·입퇴장 줄·승인 줄)의 DOM id. 원글로 이동할 때 쓴다. */
export function entryAnchorId(id: string): string {
  return `entry-${id}`;
}

/** 그 항목으로 스크롤하고 잠깐 테두리로 표시한다. 아직 화면에 없으면 아무것도 하지 않는다. */
export function jumpToEntry(id: string): void {
  const target = document.getElementById(entryAnchorId(id));
  if (target === null) return;
  target.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
  target.classList.add('ring-2', 'ring-primary/60');
  window.setTimeout(
    () => target.classList.remove('ring-2', 'ring-primary/60'),
    1400,
  );
}

const STATUS_LABEL: Record<OriginApprovalStatus, string> = {
  pending: '승인 대기',
  approved: '승인됨',
  rejected: '반려됨',
};

const CHIP_CLASS: Record<OriginApprovalStatus, string> = {
  pending:
    'bg-amber-100 text-amber-900 dark:bg-amber-500/20 dark:text-amber-200',
  approved:
    'bg-emerald-100 text-emerald-900 dark:bg-emerald-500/20 dark:text-emerald-200',
  rejected: 'bg-destructive/10 text-destructive',
};

const BORDER_CLASS: Record<OriginApprovalStatus, string> = {
  pending: 'border-amber-400/70',
  approved: 'border-emerald-500/60',
  rejected: 'border-destructive/60',
};

function StatusChip({ status }: { status: OriginApprovalStatus }) {
  return (
    <span
      className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${CHIP_CLASS[status]}`}
    >
      {STATUS_LABEL[status]}
    </span>
  );
}

/** 원글 아래에 붙는 승인 상태 표시. 누르면 그 상태를 만든 승인 카드로 이동한다. */
export function OriginStatusButton({
  status,
  approverName,
  noticeId,
}: {
  status: OriginApprovalStatus;
  approverName: string;
  noticeId: string;
}) {
  return (
    <button
      type="button"
      title="승인 카드로 이동"
      className={`w-fit rounded-full px-2 py-0.5 text-xs font-medium ${CHIP_CLASS[status]}`}
      onClick={() => jumpToEntry(noticeId)}
    >
      {STATUS_LABEL[status]} · {approverName}
    </button>
  );
}

function sentenceOf(notice: RoomApprovalNotice): string {
  const { requester, target } = notice;
  return notice.event === 'requested'
    ? `${requester.displayName}님이 ${target.displayName}님에게 승인을 요청했습니다`
    : `${target.displayName}님이 ${requester.displayName}님의 요청을 ${
        notice.event === 'approved' ? '승인' : '반려'
      }했습니다`;
}

export function ApprovalCard({
  notice,
  origin,
  decidable,
  onReview,
}: {
  notice: RoomApprovalNotice;
  /** 원글. 이 화면이 아직 갖고 있지 않으면 null이다. label은 보낸 사람(또는 AI) 이름이다. */
  origin: { id: string; label: string; content: string } | null;
  /** 이 줄이 내가 지금 처리할 수 있는 대기 요청이면 그 요청이다. */
  decidable: ThreadApproval | null;
  onReview: (approval: ThreadApproval, rejecting: boolean) => void;
}) {
  const { requester, target } = notice;
  const anchor = entryAnchorId(notice.id);

  // 원글을 알 수 없는 옛 줄은 예전 모양 그대로 한 줄로 둔다.
  if (notice.originMsgId === null) {
    return (
      <p
        id={anchor}
        title={`${requester.subject} → ${target.subject}`}
        className="px-3 py-1 text-center text-xs text-muted-foreground"
      >
        {sentenceOf(notice)}
      </p>
    );
  }

  const status = statusOf(notice.event);
  return (
    <div
      id={anchor}
      title={`${requester.subject} → ${target.subject}`}
      className={`mx-auto flex w-full max-w-xl flex-col gap-2 rounded-lg border bg-card px-3 py-2.5 text-sm text-card-foreground transition-shadow ${BORDER_CLASS[status]}`}
    >
      {origin && (
        <button
          type="button"
          title="원글로 이동"
          className="flex flex-col gap-0.5 rounded-md border-l-4 border-primary bg-muted/60 px-2 py-1 text-left"
          onClick={() => jumpToEntry(origin.id)}
        >
          <span className="text-xs font-medium text-primary">
            {origin.label}
          </span>
          <span className="line-clamp-2 whitespace-pre-wrap">
            {origin.content}
          </span>
        </button>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <StatusChip status={status} />
        <span className="min-w-0 flex-1">{sentenceOf(notice)}</span>
      </div>
      {notice.event === 'rejected' && notice.reason !== null && (
        <p className="rounded-md bg-destructive/10 px-2 py-1">
          <span className="mr-1.5 font-medium text-destructive">사유</span>
          {notice.reason}
        </p>
      )}
      {decidable !== null && (
        <div className="flex gap-2">
          <Button
            className="h-8 px-4"
            onClick={() => onReview(decidable, false)}
          >
            승인
          </Button>
          <Button
            variant="outline"
            className="h-8 px-4"
            onClick={() => onReview(decidable, true)}
          >
            반려
          </Button>
        </div>
      )}
    </div>
  );
}
