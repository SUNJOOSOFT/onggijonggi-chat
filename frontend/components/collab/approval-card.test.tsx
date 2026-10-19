// @vitest-environment jsdom
/********************************************************
 파일명 : approval-card.test.tsx (components/collab)
 설 명 : 승인 카드가 원글 앞부분·상태·반려 사유를 보이고(이슈 #416), 승인권자 본인의 대기 카드에만 버튼이 있으며,
 원글 id가 없는 옛 줄은 한 줄 문구로 남는지를 본다. 원글로 이동하는 동작도 함께 본다.
 *********************************************************/

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ThreadApproval } from '@/lib/api/thread-approvals';
import type { RoomApprovalNotice } from '@/lib/chat/room-state';
import {
  ApprovalCard,
  OriginStatusButton,
  entryAnchorId,
  jumpToEntry,
} from './approval-card';

afterEach(cleanup);

const origin = { id: 'm1', label: '배수진', content: '제12조 개정안입니다' };

const approval: ThreadApproval = {
  id: 'apv-1',
  messageId: 'm1',
  requesterSubject: 'sujin',
  targetSubject: 'minho',
  status: 'PENDING',
  reason: null,
  createdAt: '2026-10-16T00:00:00Z',
  completedAt: null,
  canDecide: true,
};

function notice(over: Partial<RoomApprovalNotice> = {}): RoomApprovalNotice {
  return {
    id: 'line-1',
    seq: 2,
    approvalId: 'apv-1',
    event: 'requested',
    requester: { subject: 'sujin', displayName: '배수진' },
    target: { subject: 'minho', displayName: '김민호' },
    originMsgId: 'm1',
    reason: null,
    ...over,
  };
}

describe('ApprovalCard', () => {
  it('요청 카드는 원글 앞부분과 대기 상태와 문장을 보인다', () => {
    render(
      <ApprovalCard
        notice={notice()}
        origin={origin}
        decidable={null}
        onReview={vi.fn()}
      />,
    );

    expect(screen.getByText('제12조 개정안입니다')).toBeTruthy();
    expect(screen.getByText('승인 대기')).toBeTruthy();
    expect(
      screen.getByText('배수진님이 김민호님에게 승인을 요청했습니다'),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: '승인' })).toBeNull();
  });

  it('반려 카드는 사유를 같이 보인다', () => {
    render(
      <ApprovalCard
        notice={notice({
          event: 'rejected',
          reason: '3번 항목 근거가 없습니다',
        })}
        origin={origin}
        decidable={null}
        onReview={vi.fn()}
      />,
    );

    expect(screen.getByText('반려됨')).toBeTruthy();
    expect(
      screen.getByText('김민호님이 배수진님의 요청을 반려했습니다'),
    ).toBeTruthy();
    expect(screen.getByText('3번 항목 근거가 없습니다')).toBeTruthy();
  });

  it('사유가 없는 반려와 승인 카드에는 사유 줄이 없다', () => {
    render(
      <ApprovalCard
        notice={notice({ event: 'rejected' })}
        origin={origin}
        decidable={null}
        onReview={vi.fn()}
      />,
    );
    expect(screen.queryByText('사유')).toBeNull();
  });

  it('내가 처리할 대기 카드에만 승인·반려 버튼이 있고, 누르면 그 요청과 반려 여부를 알린다', () => {
    const onReview = vi.fn();
    render(
      <ApprovalCard
        notice={notice()}
        origin={origin}
        decidable={approval}
        onReview={onReview}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '승인' }));
    expect(onReview).toHaveBeenLastCalledWith(approval, false);
    fireEvent.click(screen.getByRole('button', { name: '반려' }));
    expect(onReview).toHaveBeenLastCalledWith(approval, true);
  });

  it('원글을 아직 모르면 인용 없이 나머지만 그린다', () => {
    render(
      <ApprovalCard
        notice={notice()}
        origin={null}
        decidable={null}
        onReview={vi.fn()}
      />,
    );

    expect(screen.queryByTitle('원글로 이동')).toBeNull();
    expect(
      screen.getByText('배수진님이 김민호님에게 승인을 요청했습니다'),
    ).toBeTruthy();
    expect(screen.getByText('승인 대기')).toBeTruthy();
  });

  it('원글 id가 없는 옛 줄은 한 줄 문구로만 남고 칩도 버튼도 없다', () => {
    render(
      <ApprovalCard
        notice={notice({ originMsgId: null })}
        origin={null}
        decidable={approval}
        onReview={vi.fn()}
      />,
    );

    expect(
      screen.getByText('배수진님이 김민호님에게 승인을 요청했습니다'),
    ).toBeTruthy();
    expect(screen.queryByText('승인 대기')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });
});

describe('원글로 이동', () => {
  it('원글 앞부분을 누르면 그 항목으로 스크롤하고 잠깐 표시한다', () => {
    const target = document.createElement('div');
    target.id = entryAnchorId('m1');
    const scroll = vi.fn();
    target.scrollIntoView = scroll;
    document.body.append(target);
    vi.useFakeTimers();
    try {
      render(
        <ApprovalCard
          notice={notice()}
          origin={origin}
          decidable={null}
          onReview={vi.fn()}
        />,
      );

      fireEvent.click(screen.getByTitle('원글로 이동'));
      expect(scroll).toHaveBeenCalledOnce();
      expect(target.classList.contains('ring-2')).toBe(true);
      vi.advanceTimersByTime(1500);
      expect(target.classList.contains('ring-2')).toBe(false);
    } finally {
      vi.useRealTimers();
      target.remove();
    }
  });

  it('화면에 없는 항목으로는 아무 일도 일어나지 않는다', () => {
    expect(() => jumpToEntry('없는-id')).not.toThrow();
  });
});

describe('OriginStatusButton', () => {
  it('상태와 승인권자 이름을 보이고 누르면 그 승인 카드로 이동한다', () => {
    const target = document.createElement('div');
    target.id = entryAnchorId('line-1');
    const scroll = vi.fn();
    target.scrollIntoView = scroll;
    document.body.append(target);
    try {
      render(
        <OriginStatusButton
          status="pending"
          approverName="김민호"
          noticeId="line-1"
        />,
      );

      fireEvent.click(
        screen.getByRole('button', { name: '승인 대기 · 김민호' }),
      );
      expect(scroll).toHaveBeenCalledOnce();
    } finally {
      target.remove();
    }
  });
});
