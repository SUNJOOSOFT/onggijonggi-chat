import { describe, expect, it, vi } from 'vitest';
import { routeFrame } from './frame-router';
import type {
  ChatAnswerFrame,
  ChatMessageFrame,
  PresenceJoinFrame,
  PresenceLeaveFrame,
  PresenceSnapshotFrame,
  WsErrorFrame,
} from './frames';

describe('routeFrame', () => {
  it('chat.answer는 onChatAnswer에게만 원본 payload로 전달된다', () => {
    const frame: ChatAnswerFrame = {
      type: 'chat.answer',
      sessionId: 's1',
      delta: '안녕',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'streaming',
    };
    const onChatAnswer = vi.fn();
    const onError = vi.fn();
    routeFrame(frame, { onChatAnswer, onError });
    expect(onChatAnswer).toHaveBeenCalledExactlyOnceWith(frame);
    expect(onError).not.toHaveBeenCalled();
  });

  it('chat.message를 라우팅한다', () => {
    const frame: ChatMessageFrame = {
      type: 'chat.message',
      sessionId: 's1',
      from: 'u1',
      content: '안녕하세요',
    };
    const onChatMessage = vi.fn();
    routeFrame(frame, { onChatMessage });
    expect(onChatMessage).toHaveBeenCalledExactlyOnceWith(frame);
  });

  it('presence.join을 라우팅한다', () => {
    const frame: PresenceJoinFrame = {
      type: 'presence.join',
      sessionId: 's1',
      userId: 'u1',
    };
    const onPresenceJoin = vi.fn();
    routeFrame(frame, { onPresenceJoin });
    expect(onPresenceJoin).toHaveBeenCalledExactlyOnceWith(frame);
  });

  it('presence.leave를 라우팅한다', () => {
    const frame: PresenceLeaveFrame = {
      type: 'presence.leave',
      sessionId: 's1',
      userId: 'u1',
    };
    const onPresenceLeave = vi.fn();
    routeFrame(frame, { onPresenceLeave });
    expect(onPresenceLeave).toHaveBeenCalledExactlyOnceWith(frame);
  });

  it('presence.snapshot을 라우팅한다', () => {
    const frame: PresenceSnapshotFrame = {
      type: 'presence.snapshot',
      sessionId: 's1',
      participants: ['u1'],
    };
    const onPresenceSnapshot = vi.fn();
    routeFrame(frame, { onPresenceSnapshot });
    expect(onPresenceSnapshot).toHaveBeenCalledExactlyOnceWith(frame);
  });

  it('error를 라우팅한다', () => {
    const frame: WsErrorFrame = {
      type: 'error',
      sessionId: null,
      code: 'UNAUTHENTICATED',
      message: '인증이 필요합니다.',
      traceId: 't1',
    };
    const onError = vi.fn();
    routeFrame(frame, { onError });
    expect(onError).toHaveBeenCalledExactlyOnceWith(frame);
  });

  it('해당 타입 핸들러를 안 넘겨도 예외 없이 조용히 무시한다', () => {
    const frame: ChatAnswerFrame = {
      type: 'chat.answer',
      sessionId: 's1',
      delta: 'x',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'streaming',
    };
    expect(() => routeFrame(frame, {})).not.toThrow();
  });

  it('handlers가 여러 타입을 갖고 있어도 해당 프레임의 핸들러만 호출된다', () => {
    const frame: ChatAnswerFrame = {
      type: 'chat.answer',
      sessionId: 's1',
      delta: '',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'done',
    };
    const onChatAnswer = vi.fn();
    const onChatMessage = vi.fn();
    const onError = vi.fn();
    routeFrame(frame, { onChatAnswer, onChatMessage, onError });
    expect(onChatAnswer).toHaveBeenCalledOnce();
    expect(onChatMessage).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });
});
