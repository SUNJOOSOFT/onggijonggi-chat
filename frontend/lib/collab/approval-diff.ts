/********************************************************
 파일명 : approval-diff.ts (lib/collab)
 설 명 : 승인 목록이 바뀐 것을 토스트로 알릴 사건으로 가른다(이슈 #407). approval.changed 프레임은
 방 전체에 가고 요청 내용이 없으므로, 내가 알아야 할 변화인지는 다시 읽은 목록을 이전 목록과 견줘
 정한다 — 서버가 "대상자에게만" 거르지 않는 대신 이 화면이 판단한다.

 이전 목록이 없을 때(방 진입·재연결 직후의 첫 조회)는 아무것도 알리지 않는다. 이미 쌓여 있던 대기 요청은
 토스트가 아니라 목록(승인 대기 표시)으로 보이고, 그때마다 토스트가 쏟아지면 안 되기 때문이다.
 *********************************************************/

import type { ThreadApproval } from '@/lib/api/thread-approvals';

export type ApprovalToast =
  | { kind: 'requested'; approval: ThreadApproval }
  | { kind: 'decided'; approval: ThreadApproval };

/** 내가 처리해야 하는 대기 요청이 새로 생기면 requested, 내가 요청한 건이 처리되면 decided다. */
export function approvalToasts(
  previous: ThreadApproval[] | null,
  next: ThreadApproval[],
  selfSubject: string | null,
): ApprovalToast[] {
  if (previous === null || selfSubject === null) return [];
  const before = new Map(previous.map((approval) => [approval.id, approval]));
  const toasts: ApprovalToast[] = [];
  for (const approval of next) {
    const prior = before.get(approval.id);
    if (approval.canDecide && !prior?.canDecide) {
      toasts.push({ kind: 'requested', approval });
    } else if (
      approval.requesterSubject === selfSubject &&
      prior?.status === 'PENDING' &&
      approval.status !== 'PENDING'
    ) {
      toasts.push({ kind: 'decided', approval });
    }
  }
  return toasts;
}
