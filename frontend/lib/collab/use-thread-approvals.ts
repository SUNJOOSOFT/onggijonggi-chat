/********************************************************
 파일명 : use-thread-approvals.ts (lib/collab)
 설 명 : 협업방의 승인 요청 목록을 들고 토스트를 띄우는 훅(이슈 #407). approval.changed 프레임에는 요청 내용이
 없으므로, 방에 들어올 때·연결이 다시 열릴 때·프레임이 올 때마다(revision) REST 목록을 다시 읽는다. 접속하지
 않았던 동안의 대기 요청도 이 조회로 보인다.

 토스트 여부는 서버가 거르지 않고 이 화면이 이전 목록과 견줘 정한다(approval-diff.ts). 이름은 참여자 명단에서
 subject로 찾으며, 명단에 없는 사람(이미 나간 사람)은 subject를 그대로 보인다.
 *********************************************************/

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';

import { fetchThreadParticipants } from '@/lib/api/collab';
import {
  type ThreadApproval,
  listThreadApprovals,
} from '@/lib/api/thread-approvals';
import { approvalToasts } from './approval-diff';

export interface ThreadApprovals {
  approvals: ThreadApproval[];
  /** 내가 지금 승인·반려할 수 있는 요청. 오래된 것부터다. */
  decidable: ThreadApproval[];
  /** subject의 표시 이름. 명단에 없으면 subject다. */
  nameOf: (subject: string) => string;
  /** 목록을 다시 읽는다. 내가 요청하거나 처리한 직후 호출한다. */
  reload: () => void;
}

export function useThreadApprovals(
  threadId: string,
  {
    revision,
    open,
    participantsRevision,
    onOpenRequest,
  }: {
    /** approval.changed를 받을 때마다 오르는 눈금(Room.approvalsRevision). */
    revision: number;
    /** WS가 열려 있는지. 닫혔다 다시 열리면 끊긴 동안의 변화를 따라잡아야 한다. */
    open: boolean;
    participantsRevision: number;
    /** 토스트의 "확인"을 눌렀을 때 그 요청을 연다. */
    onOpenRequest: (approval: ThreadApproval) => void;
  },
): ThreadApprovals {
  const [approvals, setApprovals] = useState<ThreadApproval[]>([]);
  const [names, setNames] = useState<Map<string, string>>(new Map());
  const [self, setSelf] = useState<string | null>(null);
  const [reloadTick, setReloadTick] = useState(0);
  /** 마지막으로 읽은 목록. null이면 아직 한 번도 못 읽어 첫 조회에서 토스트를 띄우지 않는다. */
  const previousRef = useRef<ThreadApproval[] | null>(null);
  const selfRef = useRef<string | null>(null);
  selfRef.current = self;
  const nameOfRef = useRef<(subject: string) => string>((subject) => subject);
  const onOpenRequestRef = useRef(onOpenRequest);
  onOpenRequestRef.current = onOpenRequest;

  const nameOf = useCallback(
    (subject: string) => names.get(subject) ?? subject,
    [names],
  );
  nameOfRef.current = nameOf;

  // 방을 바꾸면 이전 방의 목록과 비교하지 않는다.
  // biome-ignore lint/correctness/useExhaustiveDependencies: threadId가 바뀔 때만 비운다
  useEffect(() => {
    previousRef.current = null;
    setApprovals([]);
    setNames(new Map());
    setSelf(null);
  }, [threadId]);

  // biome-ignore lint/correctness/useExhaustiveDependencies: participantsRevision은 재조회 신호다
  useEffect(() => {
    let alive = true;
    fetchThreadParticipants(threadId)
      .then((participants) => {
        if (!alive) return;
        setNames(new Map(participants.map((p) => [p.subject, p.displayName])));
        setSelf(participants.find((p) => p.self)?.subject ?? null);
      })
      // 이름은 부가 정보다 — 못 얻으면 subject로 보인다.
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [threadId, participantsRevision]);

  // biome-ignore lint/correctness/useExhaustiveDependencies: revision·reloadTick·open은 재조회 신호다
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    listThreadApprovals(threadId, controller.signal)
      .then((next) => {
        const toasts = approvalToasts(
          previousRef.current,
          next,
          selfRef.current,
        );
        previousRef.current = next;
        setApprovals(next);
        for (const entry of toasts) {
          const { approval } = entry;
          if (entry.kind === 'requested') {
            toast(
              `${nameOfRef.current(approval.requesterSubject)}님이 승인을 요청했습니다`,
              {
                action: {
                  label: '확인',
                  onClick: () => onOpenRequestRef.current(approval),
                },
              },
            );
          } else {
            const result = approval.status === 'APPROVED' ? '승인' : '반려';
            toast(
              `${nameOfRef.current(approval.targetSubject)}님이 요청을 ${result}했습니다`,
              approval.reason ? { description: approval.reason } : undefined,
            );
          }
        }
      })
      // 목록을 못 읽어도 방을 못 쓸 이유는 없다 — 다음 신호에서 다시 읽는다.
      .catch(() => undefined);
    return () => controller.abort();
  }, [threadId, revision, reloadTick, open]);

  const decidable = useMemo(
    () => approvals.filter((approval) => approval.canDecide),
    [approvals],
  );
  const reload = useCallback(() => setReloadTick((tick) => tick + 1), []);

  return { approvals, decidable, nameOf, reload };
}
