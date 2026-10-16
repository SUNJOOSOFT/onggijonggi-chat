/********************************************************
 파일명 : approval-diff.test.ts (lib/collab)
 설 명 : 승인 목록의 변화가 어떤 때 토스트가 되고 어떤 때 조용히 넘어가는지 본다(이슈 #407).
 *********************************************************/

import { describe, expect, it } from 'vitest';
import type { ThreadApproval } from '@/lib/api/thread-approvals';
import { approvalToasts } from './approval-diff';

const approval = (
  id: string,
  overrides: Partial<ThreadApproval> = {},
): ThreadApproval => ({
  id,
  messageId: 'm1',
  requesterSubject: 'sujin',
  targetSubject: 'minho',
  status: 'PENDING',
  reason: null,
  createdAt: '2026-10-16T00:00:00Z',
  completedAt: null,
  canDecide: false,
  ...overrides,
});

describe('approvalToasts', () => {
  it('첫 조회에서는 이미 쌓여 있던 요청도 알리지 않는다', () => {
    expect(
      approvalToasts(null, [approval('a', { canDecide: true })], 'minho'),
    ).toEqual([]);
  });

  it('내가 누구인지 모르면 알리지 않는다', () => {
    expect(
      approvalToasts([], [approval('a', { canDecide: true })], null),
    ).toEqual([]);
  });

  it('내가 처리할 대기 요청이 새로 생기면 requested다', () => {
    const next = approval('a', { canDecide: true });
    expect(approvalToasts([], [next], 'minho')).toEqual([
      { kind: 'requested', approval: next },
    ]);
  });

  it('이미 알린 대기 요청은 다시 알리지 않는다', () => {
    const pending = approval('a', { canDecide: true });
    expect(approvalToasts([pending], [pending], 'minho')).toEqual([]);
  });

  it('내가 요청한 건은 요청 시점에는 알리지 않는다', () => {
    expect(approvalToasts([], [approval('a')], 'sujin')).toEqual([]);
  });

  it('내가 요청한 건이 처리되면 decided다', () => {
    const done = approval('a', {
      status: 'REJECTED',
      reason: '근거 보강',
      completedAt: '2026-10-16T01:00:00Z',
    });
    expect(approvalToasts([approval('a')], [done], 'sujin')).toEqual([
      { kind: 'decided', approval: done },
    ]);
  });

  it('내가 처리한 건은 나에게 알리지 않는다', () => {
    const done = approval('a', { status: 'APPROVED' });
    expect(
      approvalToasts([approval('a', { canDecide: true })], [done], 'minho'),
    ).toEqual([]);
  });

  it('이미 처리된 채로 처음 보이는 건은 알리지 않는다', () => {
    expect(
      approvalToasts([], [approval('a', { status: 'APPROVED' })], 'sujin'),
    ).toEqual([]);
  });
});
