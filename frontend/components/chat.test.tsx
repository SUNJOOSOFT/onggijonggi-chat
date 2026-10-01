// @vitest-environment jsdom

// 새 draft의 미전송 보존과 서버 에코 뒤 세션 생성을 실제 화면 배선으로 검증한다.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Chat } from './chat';
import { useChatSessionsStore } from '@/lib/store/chat-sessions';

const mocks = vi.hoisted(() => ({
  send: vi.fn(),
  messages: [] as Array<Record<string, unknown>>,
}));
vi.mock('@/app/(chat)/actions', () => ({ saveModelId: vi.fn() }));
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }));
vi.mock('@/lib/store/chat-sessions', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  useChatSessionsHydrated: () => true,
}));
vi.mock('@/lib/chat/use-room', () => ({
  useRoom: () => ({
    state: { messages: mocks.messages, error: null, notices: [] },
    send: mocks.send,
    cancel: vi.fn(),
    cancellableTurnId: null,
    dismissError: vi.fn(),
    dismissNotice: vi.fn(),
  }),
}));
vi.mock('@/components/chat-header', () => ({ ChatHeader: () => null }));
vi.mock('./multimodal-input', () => ({
  MultimodalInput: ({
    onSend,
  }: { onSend: (text: string, attachments: []) => void }) => (
    <button type="button" onClick={() => onSend('보존할 첫 질문', [])}>
      전송
    </button>
  ),
}));
vi.mock('./messages', () => ({
  Messages: ({
    messages,
    failedMessageIds,
    onResendFailedMessage,
  }: {
    messages: Array<{ id: string; content: string }>;
    failedMessageIds: string[];
    onResendFailedMessage: (id: string) => void;
  }) => (
    <div>
      {messages.map((message) => (
        <div key={message.id}>
          {message.content}
          {failedMessageIds.includes(message.id) && (
            <button
              type="button"
              onClick={() => onResendFailedMessage(message.id)}
            >
              재전송
            </button>
          )}
        </div>
      ))}
    </div>
  ),
}));

const props = {
  id: 'draft-262',
  availableModels: ['model'],
  selectedModelId: 'model',
  isNewDraft: true,
};
beforeEach(() => {
  mocks.messages = [];
  mocks.send.mockReset();
  useChatSessionsStore.setState({ sessions: [], currentSessionId: null });
});
afterEach(cleanup);

describe('새 대화 첫 전송', () => {
  it('연결 실패 본문을 보존하고 재전송하되 에코 전에는 세션을 만들지 않는다', () => {
    mocks.send.mockReturnValue(null);
    render(<Chat {...props} />);
    fireEvent.click(screen.getByText('전송'));
    expect(screen.getByText('보존할 첫 질문')).toBeTruthy();
    expect(useChatSessionsStore.getState().sessions).toHaveLength(0);
    mocks.send.mockReturnValue({ clientMsgId: 'client-1', turnId: 'turn-1' });
    fireEvent.click(screen.getByText('재전송'));
    expect(mocks.send).toHaveBeenLastCalledWith(
      '보존할 첫 질문',
      'model',
      undefined,
      [],
    );
    expect(screen.queryByText('재전송')).toBeNull();
    expect(useChatSessionsStore.getState().sessions).toHaveLength(0);
  });

  it('첫 발화의 서버 에코가 도착한 뒤에만 세션을 만든다', () => {
    mocks.send.mockReturnValue({ clientMsgId: 'client-1', turnId: 'turn-1' });
    const view = render(<Chat {...props} />);
    fireEvent.click(screen.getByText('전송'));
    expect(useChatSessionsStore.getState().sessions).toHaveLength(0);
    mocks.messages = [
      {
        id: 'server-1',
        from: 'owner',
        content: '보존할 첫 질문',
        turnId: 'turn-1',
        streaming: false,
        terminalStatus: null,
        attachments: [],
      },
    ];
    view.rerender(<Chat {...props} />);
    expect(
      useChatSessionsStore.getState().sessions.map((session) => session.id),
    ).toEqual([props.id]);
  });
});
