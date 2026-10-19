// @vitest-environment jsdom
/********************************************************
 파일명 : approval-dialog.test.tsx (components/collab)
 설 명 : 승인 처리 모달이 요청된 메시지를 보여 주고, 승인·반려를 서버 계약대로 보내며, 거절되면 열어 둔 채
 사유를 보여 주는지 본다(이슈 #407).
 *********************************************************/

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  type ThreadApproval,
  decideThreadApproval,
} from '@/lib/api/thread-approvals';
import { ApprovalDialog } from './approval-dialog';

vi.mock('@/lib/api/thread-approvals', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/thread-approvals')>()),
  decideThreadApproval: vi.fn(),
}));

const decide = vi.mocked(decideThreadApproval);

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

function renderDialog(
  overrides: Partial<Parameters<typeof ApprovalDialog>[0]> = {},
) {
  const onDecided = vi.fn();
  const onClose = vi.fn();
  render(
    <ApprovalDialog
      threadId="room-1"
      approval={approval}
      messageContent="제12조 개정안입니다"
      requesterName="배수진"
      onDecided={onDecided}
      onClose={onClose}
      {...overrides}
    />,
  );
  return { onDecided, onClose };
}

beforeEach(() => decide.mockReset());
afterEach(cleanup);

describe('ApprovalDialog', () => {
  it('요청한 사람과 요청된 메시지를 보여 준다', () => {
    renderDialog();

    expect(
      screen.getByText(/배수진님이 아래 메시지의 승인을 요청/),
    ).toBeTruthy();
    expect(screen.getByText('제12조 개정안입니다')).toBeTruthy();
  });

  it('메시지를 화면이 갖고 있지 않으면 그 사실을 알린다', () => {
    renderDialog({ messageContent: null });

    expect(
      screen.getByText('요청된 메시지를 불러오지 못했습니다.'),
    ).toBeTruthy();
  });

  it('승인 카드의 반려로 열면 반려 사유 입력부터 보인다', () => {
    renderDialog({ initialRejecting: true });

    expect(screen.getByLabelText('반려 사유')).toBeTruthy();
    expect(screen.getByRole('button', { name: '반려 확정' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '승인' })).toBeNull();
  });

  it('그냥 열면 사유 입력은 숨어 있다', () => {
    renderDialog();

    expect(screen.queryByLabelText('반려 사유')).toBeNull();
  });

  it('요청이 없으면 아무것도 그리지 않는다', () => {
    renderDialog({ approval: null });

    expect(screen.queryByText('승인 요청')).toBeNull();
  });

  it('승인하면 사유 없이 보내고 목록을 다시 읽게 한 뒤 닫는다', async () => {
    decide.mockResolvedValue({ ...approval, status: 'APPROVED' });
    const { onDecided, onClose } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(decide).toHaveBeenCalledWith('room-1', 'apv-1', {
      approved: true,
      reason: '',
    });
    expect(onDecided).toHaveBeenCalledTimes(1);
  });

  it('반려는 사유 입력을 거쳐 확정하고, 사유 입력 전에는 보내지 않는다', async () => {
    decide.mockResolvedValue({ ...approval, status: 'REJECTED' });
    const { onDecided } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: '반려' }));
    expect(decide).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('반려 사유'), {
      target: { value: '근거를 보강해 주세요' },
    });
    fireEvent.click(screen.getByRole('button', { name: '반려 확정' }));

    await vi.waitFor(() => expect(onDecided).toHaveBeenCalled());
    expect(decide).toHaveBeenCalledWith('room-1', 'apv-1', {
      approved: false,
      reason: '근거를 보강해 주세요',
    });
  });

  it('반려 사유 입력은 서버 상한까지만 받는다', () => {
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: '반려' }));

    expect(screen.getByLabelText('반려 사유').getAttribute('maxlength')).toBe(
      '500',
    );
  });

  it('반려 입력 중 취소하면 승인·반려 선택으로 돌아간다', () => {
    renderDialog();

    fireEvent.click(screen.getByRole('button', { name: '반려' }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByLabelText('반려 사유')).toBeNull();
    expect(screen.getByRole('button', { name: '승인' })).toBeTruthy();
  });

  it('서버가 거절하면 닫지 않고 사유를 보여 준다', async () => {
    decide.mockRejectedValueOnce(new Error('요청 상태가 방금 바뀌었어요.'));
    const { onDecided, onClose } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: '승인' }));

    expect((await screen.findByRole('alert')).textContent).toContain(
      '요청 상태가 방금 바뀌었어요.',
    );
    expect(onDecided).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    expect(
      (screen.getByRole('button', { name: '승인' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
  });

  it('"나중에"는 처리하지 않고 닫는다', () => {
    const { onClose } = renderDialog();

    fireEvent.click(screen.getByRole('button', { name: '나중에' }));

    expect(onClose).toHaveBeenCalled();
    expect(decide).not.toHaveBeenCalled();
  });
});
