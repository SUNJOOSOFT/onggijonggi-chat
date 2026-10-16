// @vitest-environment jsdom
/********************************************************
 파일명 : approval-request-dialog.test.tsx (components/collab)
 설 명 : 승인 요청 모달의 후보 선별과, 고른 사람에게 요청을 보내는 흐름을 본다(이슈 #407).
 *********************************************************/

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  type ThreadParticipant,
  fetchThreadParticipants,
} from '@/lib/api/collab';
import {
  type ThreadApproval,
  requestThreadApproval,
} from '@/lib/api/thread-approvals';
import {
  ApprovalRequestDialog,
  approvalCandidates,
} from './approval-request-dialog';

vi.mock('sonner', () => ({ toast: { success: vi.fn() } }));
vi.mock('@/lib/api/collab', () => ({ fetchThreadParticipants: vi.fn() }));
vi.mock('@/lib/api/thread-approvals', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/thread-approvals')>()),
  requestThreadApproval: vi.fn(),
}));

const participants = vi.mocked(fetchThreadParticipants);
const request = vi.mocked(requestThreadApproval);

function person(
  subject: string,
  overrides: Partial<ThreadParticipant> = {},
): ThreadParticipant {
  return {
    subject,
    role: 'MEMBER',
    self: false,
    displayName: `${subject} 님`,
    pending: false,
    workspaceAccess: true,
    ...overrides,
  };
}

const ROSTER = [
  person('sujin', { self: true }),
  person('minho'),
  person('invited', { pending: true }),
  person('blocked', { workspaceAccess: false }),
];

const created = {} as ThreadApproval;

function renderDialog(messageId: string | null = 'm1') {
  const onRequested = vi.fn();
  const onClose = vi.fn();
  render(
    <ApprovalRequestDialog
      threadId="room-1"
      messageId={messageId}
      onRequested={onRequested}
      onClose={onClose}
    />,
  );
  return { onRequested, onClose };
}

beforeEach(() => {
  participants.mockReset();
  request.mockReset();
  participants.mockResolvedValue(ROSTER);
});
afterEach(cleanup);

describe('approvalCandidates', () => {
  it('나와 대기 초대, 방 접근 권한이 없는 사람은 후보에서 뺀다', () => {
    expect(approvalCandidates(ROSTER).map((p) => p.subject)).toEqual(['minho']);
  });
});

describe('ApprovalRequestDialog', () => {
  it('후보만 이름으로 보여 준다', async () => {
    renderDialog();

    expect(
      await screen.findByRole('button', { name: 'minho 님' }),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'sujin 님' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'invited 님' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'blocked 님' })).toBeNull();
  });

  it('후보가 없으면 그 사실을 알린다', async () => {
    participants.mockResolvedValue([person('sujin', { self: true })]);
    renderDialog();

    expect(
      await screen.findByText('요청할 수 있는 참여자가 없습니다.'),
    ).toBeTruthy();
  });

  it('메시지가 없으면 열지 않고 명단도 읽지 않는다', () => {
    renderDialog(null);

    expect(
      screen.queryByText('이 메시지를 승인할 사람을 고르세요.'),
    ).toBeNull();
    expect(participants).not.toHaveBeenCalled();
  });

  it('고른 사람을 대상으로 메시지 승인을 요청하고 목록을 다시 읽게 한 뒤 닫는다', async () => {
    request.mockResolvedValue(created);
    const { onRequested, onClose } = renderDialog();

    fireEvent.click(await screen.findByRole('button', { name: 'minho 님' }));

    await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(request).toHaveBeenCalledWith('room-1', {
      id: expect.stringMatching(/^[0-9a-f-]{36}$/),
      messageId: 'm1',
      targetSubject: 'minho',
    });
    expect(onRequested).toHaveBeenCalledTimes(1);
  });

  it('실패하면 닫지 않고 사유를 보여 주며, 다시 눌러도 같은 요청 id를 쓴다', async () => {
    request.mockRejectedValueOnce(new Error('요청하지 못했어요.'));
    request.mockResolvedValueOnce(created);
    const { onClose } = renderDialog();

    fireEvent.click(await screen.findByRole('button', { name: 'minho 님' }));
    expect((await screen.findByRole('alert')).textContent).toContain(
      '요청하지 못했어요.',
    );
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'minho 님' }));
    await vi.waitFor(() => expect(onClose).toHaveBeenCalled());

    const [first, second] = request.mock.calls;
    expect(first[1].id).toBe(second[1].id);
  });

  it('명단을 못 읽으면 그 사실을 알린다', async () => {
    participants.mockRejectedValue(new Error('x'));
    renderDialog();

    expect((await screen.findByRole('alert')).textContent).toContain(
      '참여자 명단을 불러오지 못했어요.',
    );
  });
});
