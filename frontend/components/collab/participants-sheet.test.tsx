// @vitest-environment jsdom

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { InviteCandidate, ThreadParticipant } from '@/lib/api/collab';
import { ParticipantsSheet } from './participants-sheet';

const mocks = vi.hoisted(() => ({
  fetchParticipants: vi.fn(),
  inviteParticipant: vi.fn(),
  removeParticipant: vi.fn(),
  revokeInvitation: vi.fn(),
  searchInviteCandidates: vi.fn(),
  transferOwnership: vi.fn(),
  router: { push: vi.fn() },
  toastError: vi.fn(),
}));

vi.mock('@/lib/api/collab', () => ({
  fetchThreadParticipants: mocks.fetchParticipants,
  inviteParticipant: mocks.inviteParticipant,
  removeParticipant: mocks.removeParticipant,
  revokeInvitation: mocks.revokeInvitation,
  searchInviteCandidates: mocks.searchInviteCandidates,
  transferOwnership: mocks.transferOwnership,
}));

vi.mock('next/navigation', () => ({
  useRouter: () => mocks.router,
}));

vi.mock('sonner', () => ({
  toast: { error: mocks.toastError },
}));

const owner: ThreadParticipant = {
  subject: 'owner-subject',
  role: 'OWNER',
  self: true,
  displayName: '방장',
  pending: false,
  workspaceAccess: true,
};

const member: ThreadParticipant = {
  subject: 'member-subject',
  role: 'MEMBER',
  self: false,
  displayName: '참가자',
  pending: false,
  workspaceAccess: true,
};

const pendingInvite: ThreadParticipant = {
  subject: 'pending-subject',
  role: 'MEMBER',
  self: false,
  displayName: '대기 초대',
  pending: true,
  workspaceAccess: true,
};

const candidate: InviteCandidate = {
  subject: 'candidate-subject',
  displayName: '초대 후보',
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

async function openSheet(participants = [owner, member, pendingInvite]) {
  mocks.fetchParticipants.mockResolvedValue(participants);
  render(<ParticipantsSheet threadId="thread-id" refreshSignal={0} />);

  fireEvent.click(screen.getByRole('button', { name: '참여자 관리' }));

  await screen.findByText(owner.displayName);
  expect(mocks.fetchParticipants).toHaveBeenCalledTimes(1);
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('ParticipantsSheet(#173)', () => {
  it('겹친 명단 조회에서 늦은 응답이 최신 목록을 덮어쓰지 않는다', async () => {
    const first = deferred<ThreadParticipant[]>();
    const second = deferred<ThreadParticipant[]>();
    mocks.fetchParticipants
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const view = render(
      <ParticipantsSheet threadId="thread-id" refreshSignal={0} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '참여자 관리' }));
    await waitFor(() => {
      expect(mocks.fetchParticipants).toHaveBeenCalledTimes(1);
    });

    view.rerender(<ParticipantsSheet threadId="thread-id" refreshSignal={1} />);
    await waitFor(() => {
      expect(mocks.fetchParticipants).toHaveBeenCalledTimes(2);
    });

    await act(async () => {
      second.resolve([owner, { ...member, displayName: '최신 응답' }]);
      await second.promise;
    });
    await screen.findByText('최신 응답');

    await act(async () => {
      first.resolve([owner, { ...member, displayName: '오래된 응답' }]);
      await first.promise;
    });

    expect(screen.getByText('최신 응답')).toBeTruthy();
    expect(screen.queryByText('오래된 응답')).toBeNull();
  });

  it('워크스페이스 권한을 잃은 참가자에게만 권한 없음을 표시한다', async () => {
    await openSheet([
      owner,
      { ...member, displayName: '권한 잃은 참가자', workspaceAccess: false },
    ]);

    expect(screen.getAllByText('권한 없음')).toHaveLength(1);
    expect(
      screen.getByText('권한 잃은 참가자').parentElement?.textContent,
    ).toContain('권한 없음');
  });

  it('초대 성공 뒤 참여자 목록을 직접 다시 조회하지 않는다', async () => {
    mocks.searchInviteCandidates.mockResolvedValue([candidate]);
    mocks.inviteParticipant.mockResolvedValue(undefined);

    await openSheet();
    fireEvent.change(screen.getByLabelText('사람 초대'), {
      target: { value: '후보' },
    });

    fireEvent.click(await screen.findByRole('button', { name: '초대' }));
    await waitFor(() => {
      expect(mocks.inviteParticipant).toHaveBeenCalledWith(
        'thread-id',
        candidate.subject,
      );
    });

    expect(mocks.fetchParticipants).toHaveBeenCalledTimes(1);
  });

  it('초대 취소 성공 뒤 참여자 목록을 직접 다시 조회하지 않는다', async () => {
    mocks.revokeInvitation.mockResolvedValue(undefined);

    await openSheet();
    fireEvent.click(screen.getByRole('button', { name: '초대 취소' }));
    await waitFor(() => {
      expect(mocks.revokeInvitation).toHaveBeenCalledWith(
        'thread-id',
        pendingInvite.subject,
      );
    });

    expect(mocks.fetchParticipants).toHaveBeenCalledTimes(1);
  });

  it('제거 성공 뒤 참여자 목록을 직접 다시 조회하지 않는다', async () => {
    mocks.removeParticipant.mockResolvedValue(undefined);

    await openSheet();
    fireEvent.click(screen.getByRole('button', { name: '제거' }));
    fireEvent.click(await screen.findByRole('button', { name: '확인' }));
    await waitFor(() => {
      expect(mocks.removeParticipant).toHaveBeenCalledWith(
        'thread-id',
        member.subject,
      );
    });

    expect(mocks.fetchParticipants).toHaveBeenCalledTimes(1);
  });

  it('OWNER 위임 성공 뒤 참여자 목록을 직접 다시 조회하지 않는다', async () => {
    mocks.transferOwnership.mockResolvedValue(undefined);

    await openSheet();
    fireEvent.click(screen.getByRole('button', { name: '위임' }));
    fireEvent.click(await screen.findByRole('button', { name: '확인' }));
    await waitFor(() => {
      expect(mocks.transferOwnership).toHaveBeenCalledWith(
        'thread-id',
        member.subject,
      );
    });

    expect(mocks.fetchParticipants).toHaveBeenCalledTimes(1);
  });
});
