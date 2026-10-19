/********************************************************
 파일명 : approval-status.ts (lib/collab)
 설 명 : 대화 흐름의 승인 줄에서 원글마다 지금 승인이 어떤 상태인지 가른다(이슈 #416). 줄은 요청·승인·반려
 사건마다 하나씩 남으므로 원글 하나에 줄이 여럿일 수 있고, 그중 seq가 가장 큰 줄이 지금 상태다 — 반려된 뒤 다시
 요청하면 요청 줄이 더 뒤에 있어 대기로 돌아간다. 원글 id가 없는 옛 줄은 어느 원글인지 알 수 없어 건너뛴다.
 *********************************************************/

import type { RoomApprovalNotice, RoomEntry } from '@/lib/chat/room-state';
import { isApprovalNotice } from '@/lib/chat/room-state';

export type OriginApprovalStatus = 'pending' | 'approved' | 'rejected';

export interface OriginApproval {
  status: OriginApprovalStatus;
  /** 지금 상태를 만든 줄. 원글 아래 표시를 누르면 이 줄로 이동한다. */
  notice: RoomApprovalNotice;
}

/** 줄의 사건을 원글 기준 상태로 옮긴다. 요청 줄은 아직 처리 전이라 대기다. */
export function statusOf(
  event: RoomApprovalNotice['event'],
): OriginApprovalStatus {
  if (event === 'requested') return 'pending';
  return event;
}

/** 원글 id → 그 원글의 지금 승인 상태. entries는 seq 순서로 정렬돼 있다고 본다(room-state가 그렇게 둔다). */
export function latestApprovalByOrigin(
  entries: RoomEntry[],
): Map<string, OriginApproval> {
  const latest = new Map<string, OriginApproval>();
  for (const entry of entries) {
    if (!isApprovalNotice(entry) || entry.originMsgId === null) continue;
    latest.set(entry.originMsgId, {
      status: statusOf(entry.event),
      notice: entry,
    });
  }
  return latest;
}
