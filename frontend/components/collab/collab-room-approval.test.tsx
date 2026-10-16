// @vitest-environment jsdom
/********************************************************
 파일명 : collab-room-approval.test.tsx (components/collab)
 설 명 : 협업방 화면에서 승인 요청이 시작되고 알려지는 길을 본다(이슈 #407) — 메시지의 "승인 요청" 버튼, 헤더의
 "승인 대기" 버튼, approval.changed 뒤의 토스트, 모달에 뜨는 요청된 메시지. 방 훅과 REST는 목으로 대체하고
 화면 연결만 본다.
 *********************************************************/

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { toast } from 'sonner';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchThreadParticipants } from '@/lib/api/collab';
import {
  type ThreadApproval,
  listThreadApprovals,
} from '@/lib/api/thread-approvals';
import { initialRoomState } from '@/lib/chat/room-state';
import { useCollabRoom } from '@/lib/collab/use-collab-room';
import { CollabRoom } from './collab-room';

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn() }),
}));
vi.mock('@/lib/collab/use-collab-room', () => ({ useCollabRoom: vi.fn() }));
vi.mock('@/lib/api/collab', () => ({
  fetchCollabThreads: vi.fn().mockResolvedValue([]),
  fetchThreadParticipants: vi.fn(),
}));
vi.mock('@/lib/api/thread-approvals', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/thread-approvals')>()),
  listThreadApprovals: vi.fn(),
}));
// 승인 연결 말고는 이 테스트의 관심이 아닌 구성요소다.
vi.mock('@/components/sidebar-toggle', () => ({ SidebarToggle: () => null }));
vi.mock('@/components/thread-documents', () => ({
  ThreadDocumentsProvider: ({ children }: { children: React.ReactNode }) =>
    children,
  ThreadDocumentsButton: () => null,
  ThreadDocumentsPanel: () => null,
}));
vi.mock('./participants-sheet', () => ({ ParticipantsSheet: () => null }));
vi.mock('./collab-input', () => ({ CollabInput: () => null }));
vi.mock('@/components/use-scroll-to-bottom', () => ({
  useScrollToBottom: () => [{ current: null }, { current: null }],
}));

const useRoom = vi.mocked(useCollabRoom);
const list = vi.mocked(listThreadApprovals);
const participants = vi.mocked(fetchThreadParticipants);

const message = {
  id: 'm1',
  seq: 1,
  from: { subject: 'sujin', displayName: '배수진' },
  content: '제12조 개정안입니다',
  streaming: false,
  terminalStatus: null,
  turnId: null,
  citations: [],
  restrictedResultsOmitted: false,
  groundingNotice: null,
  agent: null,
  attachments: [],
};

const pendingForMe: ThreadApproval = {
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

function room(approvalsRevision = 0) {
  return {
    state: { ...initialRoomState, messages: [message] },
    connection: 'open' as const,
    send: vi.fn(),
    cancel: vi.fn(),
    cancellableTurnId: null,
    dismissError: vi.fn(),
    dismissNotice: vi.fn(),
    participantsRevision: 0,
    approvalsRevision,
    typing: [],
    setTyping: vi.fn(),
  };
}

beforeEach(() => {
  list.mockReset();
  list.mockResolvedValue([]);
  participants.mockReset();
  participants.mockResolvedValue([
    {
      subject: 'minho',
      role: 'MEMBER',
      self: true,
      displayName: '김민호',
      pending: false,
      workspaceAccess: true,
    },
    {
      subject: 'sujin',
      role: 'OWNER',
      self: false,
      displayName: '배수진',
      pending: false,
      workspaceAccess: true,
    },
  ]);
  vi.mocked(toast).mockReset();
  useRoom.mockReturnValue(room());
});
afterEach(cleanup);

describe('CollabRoom 승인 요청', () => {
  it('메시지의 "승인 요청"을 누르면 참여자 선택 모달이 열린다', async () => {
    render(<CollabRoom threadId="room-1" />);

    fireEvent.click(screen.getByRole('button', { name: '승인 요청' }));

    expect(
      await screen.findByText('이 메시지를 승인할 사람을 고르세요.'),
    ).toBeTruthy();
    expect(await screen.findByRole('button', { name: '배수진' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '김민호' })).toBeNull();
  });

  it('쓰는 중인 답변과 본문 없는 메시지에는 "승인 요청"이 없다', () => {
    useRoom.mockReturnValue({
      ...room(),
      state: {
        ...initialRoomState,
        messages: [
          { ...message, id: 'a', streaming: true },
          { ...message, id: 'b', content: '' },
        ],
      },
    });
    render(<CollabRoom threadId="room-1" />);

    expect(screen.queryByRole('button', { name: '승인 요청' })).toBeNull();
  });
});

describe('CollabRoom 승인 대기', () => {
  it('내가 처리할 요청이 없으면 헤더에 승인 대기가 없다', async () => {
    list.mockResolvedValue([{ ...pendingForMe, canDecide: false }]);
    render(<CollabRoom threadId="room-1" />);

    await vi.waitFor(() => expect(list).toHaveBeenCalled());
    expect(screen.queryByText(/승인 대기/)).toBeNull();
  });

  it('접속 전에 쌓인 요청은 토스트 없이 헤더 승인 대기로 보이고, 누르면 요청된 메시지가 모달에 뜬다', async () => {
    list.mockResolvedValue([pendingForMe]);
    render(<CollabRoom threadId="room-1" />);

    const button = await screen.findByRole('button', { name: '승인 대기 1건' });
    expect(vi.mocked(toast)).not.toHaveBeenCalled();

    fireEvent.click(button);

    expect(
      await screen.findByText(/배수진님이 아래 메시지의 승인을 요청/),
    ).toBeTruthy();
    // 모달 안 인용과 대화 흐름에 같은 본문이 두 번 보인다.
    expect(screen.getAllByText('제12조 개정안입니다')).toHaveLength(2);
    expect(screen.getByRole('button', { name: '승인' })).toBeTruthy();
  });

  it('approval.changed 뒤에 내게 지정된 요청이 새로 보이면 토스트를 띄우고, 확인을 누르면 모달이 열린다', async () => {
    const view = render(<CollabRoom threadId="room-1" />);
    await vi.waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    // 첫 조회가 끝나 이전 목록이 생긴 뒤에야 변화를 알린다.
    await vi.waitFor(() => expect(participants).toHaveBeenCalled());

    list.mockResolvedValue([pendingForMe]);
    useRoom.mockReturnValue(room(1));
    view.rerender(<CollabRoom threadId="room-1" />);

    await vi.waitFor(() => expect(vi.mocked(toast)).toHaveBeenCalled());
    const [text, options] = vi.mocked(toast).mock.calls[0] as [
      string,
      { action: { label: string; onClick: () => void } },
    ];
    expect(text).toBe('배수진님이 승인을 요청했습니다');
    expect(options.action.label).toBe('확인');

    options.action.onClick();

    expect(
      await screen.findByText(/배수진님이 아래 메시지의 승인을 요청/),
    ).toBeTruthy();
  });
});
