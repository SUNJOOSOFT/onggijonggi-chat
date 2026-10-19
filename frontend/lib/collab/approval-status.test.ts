/********************************************************
 파일명 : approval-status.test.ts (lib/collab)
 설 명 : 원글마다 지금 승인 상태를 가르는 규칙을 본다(이슈 #416) — 가장 뒤의 줄이 이기고, 반려 뒤 다시 요청하면
 대기로 돌아가며, 원글 id가 없는 옛 줄과 메시지는 건너뛴다.
 *********************************************************/

import { describe, expect, it } from 'vitest';

import type { RoomApprovalNotice, RoomEntry } from '@/lib/chat/room-state';
import { latestApprovalByOrigin, statusOf } from './approval-status';

function notice(
  id: string,
  seq: number,
  event: RoomApprovalNotice['event'],
  originMsgId: string | null,
  approvalId = 'apv-1',
): RoomApprovalNotice {
  return {
    id,
    seq,
    approvalId,
    event,
    requester: { subject: 'sujin', displayName: '배수진' },
    target: { subject: 'minho', displayName: '김민호' },
    originMsgId,
    reason: null,
  };
}

describe('statusOf', () => {
  it('요청 줄은 대기이고 승인·반려는 그대로다', () => {
    expect(statusOf('requested')).toBe('pending');
    expect(statusOf('approved')).toBe('approved');
    expect(statusOf('rejected')).toBe('rejected');
  });
});

describe('latestApprovalByOrigin', () => {
  it('요청만 있으면 대기이고, 처리 줄이 뒤에 오면 그 결과가 된다', () => {
    const entries: RoomEntry[] = [
      notice('l1', 1, 'requested', 'm1'),
      notice('l2', 2, 'approved', 'm1'),
    ];
    const result = latestApprovalByOrigin(entries);
    expect(result.get('m1')?.status).toBe('approved');
    expect(result.get('m1')?.notice.id).toBe('l2');
  });

  it('반려된 뒤 같은 원글을 다시 요청하면 대기로 돌아간다', () => {
    const entries: RoomEntry[] = [
      notice('l1', 1, 'requested', 'm1', 'apv-1'),
      notice('l2', 2, 'rejected', 'm1', 'apv-1'),
      notice('l3', 3, 'requested', 'm1', 'apv-2'),
    ];
    const result = latestApprovalByOrigin(entries);
    expect(result.get('m1')?.status).toBe('pending');
    expect(result.get('m1')?.notice.approvalId).toBe('apv-2');
  });

  it('원글마다 따로 센다', () => {
    const entries: RoomEntry[] = [
      notice('l1', 1, 'requested', 'm1', 'apv-1'),
      notice('l2', 2, 'requested', 'm2', 'apv-2'),
      notice('l3', 3, 'rejected', 'm2', 'apv-2'),
    ];
    const result = latestApprovalByOrigin(entries);
    expect(result.get('m1')?.status).toBe('pending');
    expect(result.get('m2')?.status).toBe('rejected');
  });

  it('원글 id가 없는 옛 줄은 건너뛴다', () => {
    const result = latestApprovalByOrigin([notice('l1', 1, 'requested', null)]);
    expect(result.size).toBe(0);
  });

  it('메시지와 입퇴장 줄은 읽지 않는다', () => {
    const message: RoomEntry = {
      id: 'm1',
      seq: 1,
      from: { subject: 'sujin', displayName: '배수진' },
      content: '안녕',
      streaming: false,
      terminalStatus: null,
      turnId: null,
      citations: [],
      restrictedResultsOmitted: false,
      groundingNotice: null,
      agent: null,
      attachments: [],
    };
    expect(latestApprovalByOrigin([message]).size).toBe(0);
  });
});
