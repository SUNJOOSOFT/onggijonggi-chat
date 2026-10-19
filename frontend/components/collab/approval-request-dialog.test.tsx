// @vitest-environment jsdom
/********************************************************
 파일명 : approval-request-dialog.test.tsx (components/collab)
 설 명 : 승인 요청 모달의 후보 선별과, 고른 사람에게 요청을 보내는 흐름을 본다(이슈 #407). 고른 뒤 확인해야 보내는
 것, 요청할 메시지 미리보기, 이미 대기 중인 요청이 있을 때 후보를 막는 것도 함께 본다(이슈 #416).
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

function renderDialog(
  messageId: string | null = 'm1',
  extra: Partial<React.ComponentProps<typeof ApprovalRequestDialog>> = {},
) {
  const onRequested = vi.fn();
  const onClose = vi.fn();
  render(
    <ApprovalRequestDialog
      threadId="room-1"
      messageId={messageId}
      onRequested={onRequested}
      onClose={onClose}
      {...extra}
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

  it('사람을 고르는 것만으로는 요청이 나가지 않고, 확인 버튼을 눌러야 나간다', async () => {
    renderDialog();

    // 고르기 전에는 보낼 수 없다.
    expect(
      (screen.getByRole('button', { name: '요청 보내기' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);

    fireEvent.click(await screen.findByRole('button', { name: 'minho 님' }));

    expect(request).not.toHaveBeenCalled();
    expect(
      screen
        .getByRole('button', { name: 'minho 님' })
        .getAttribute('aria-pressed'),
    ).toBe('true');
    expect(
      screen.getByRole('button', { name: 'minho 님에게 요청 보내기' }),
    ).toBeTruthy();
  });

  it('제목을 처리 모달과 구분하고 요청할 메시지의 앞부분을 보여 준다', async () => {
    renderDialog('m1', {
      preview: { label: '배수진', content: '제12조 개정안입니다' },
    });

    expect(await screen.findByText('승인 요청 보내기')).toBeTruthy();
    expect(screen.getByText('배수진')).toBeTruthy();
    expect(screen.getByText('제12조 개정안입니다')).toBeTruthy();
  });

  it('이미 대기 중인 요청이 있으면 후보 전원을 막고 요청이 간 사람을 표시한다', async () => {
    participants.mockResolvedValue([
      person('sujin', { self: true }),
      person('minho'),
      person('hana'),
    ]);
    renderDialog('m1', { pendingTargetSubject: 'minho' });

    expect(
      await screen.findByText(/이미 대기 중인 요청이 있습니다/),
    ).toBeTruthy();
    expect(screen.getByText('이미 요청함')).toBeTruthy();
    for (const name of ['minho 님', 'hana 님']) {
      expect(
        (
          (await screen.findByRole('button', {
            name: new RegExp(name),
          })) as HTMLButtonElement
        ).disabled,
      ).toBe(true);
    }
    expect(
      (screen.getByRole('button', { name: '요청 보내기' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('고른 사람을 대상으로 메시지 승인을 요청하고 목록을 다시 읽게 한 뒤 닫는다', async () => {
    request.mockResolvedValue(created);
    const { onRequested, onClose } = renderDialog();

    fireEvent.click(await screen.findByRole('button', { name: 'minho 님' }));
    fireEvent.click(
      screen.getByRole('button', { name: 'minho 님에게 요청 보내기' }),
    );

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
    fireEvent.click(
      screen.getByRole('button', { name: 'minho 님에게 요청 보내기' }),
    );
    expect((await screen.findByRole('alert')).textContent).toContain(
      '요청하지 못했어요.',
    );
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(
      screen.getByRole('button', { name: 'minho 님에게 요청 보내기' }),
    );
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
