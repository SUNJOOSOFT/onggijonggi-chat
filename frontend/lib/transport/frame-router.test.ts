import { describe, expect, it, vi } from 'vitest';
import { routeFrame } from './frame-router';
import type {
  ChatAnswerFrame,
  ChatMessageFrame,
  PresenceJoinFrame,
  PresenceLeaveFrame,
  PresenceSnapshotFrame,
  SystemNoticeFrame,
  WsErrorFrame,
  WsFrame,
} from './frames';

describe('routeFrame', () => {
  it('chat.answer는 onChatAnswer에게만 원본 payload로 전달된다', () => {
    const frame: ChatAnswerFrame = {
      type: 'chat.answer',
      sessionId: 's1',
      msgId: 'msg-1',
      seq: 1,
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
      msgId: 'msg-1',
      seq: 1,
      from: 'u1',
      fromDisplayName: '보낸 사람',
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
      subject: 'u1',
      displayName: '들어온 사람',
    };
    const onPresenceJoin = vi.fn();
    routeFrame(frame, { onPresenceJoin });
    expect(onPresenceJoin).toHaveBeenCalledExactlyOnceWith(frame);
  });

  it('presence.leave를 라우팅한다', () => {
    const frame: PresenceLeaveFrame = {
      type: 'presence.leave',
      sessionId: 's1',
      subject: 'u1',
      displayName: '들어온 사람',
    };
    const onPresenceLeave = vi.fn();
    routeFrame(frame, { onPresenceLeave });
    expect(onPresenceLeave).toHaveBeenCalledExactlyOnceWith(frame);
  });

  it('presence.snapshot을 라우팅한다', () => {
    const frame: PresenceSnapshotFrame = {
      type: 'presence.snapshot',
      sessionId: 's1',
      participants: [{ subject: 'u1', displayName: 'u1 님' }],
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
      msgId: 'msg-1',
      seq: 1,
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
      msgId: 'msg-1',
      seq: 1,
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

describe('routeFrame — system.notice(#29)', () => {
  it('system.notice는 onSystemNotice에게만 원본 payload로 전달된다', () => {
    const frame: SystemNoticeFrame = {
      type: 'system.notice',
      sessionId: 's1',
      severity: 'warning',
      code: 'RISKY_CONTENT',
      message: '검토가 필요한 내용이 감지되었습니다.',
      traceId: 't1',
    };
    const onSystemNotice = vi.fn();
    const onError = vi.fn();
    routeFrame(frame, { onSystemNotice, onError });
    expect(onSystemNotice).toHaveBeenCalledExactlyOnceWith(frame);
    expect(onError).not.toHaveBeenCalled();
  });
  it('participant.changed를 onParticipantChanged로 보낸다', () => {
    const frame: WsFrame = {
      type: 'participant.changed',
      sessionId: 'room-1',
      action: 'INVITE_PENDING',
      subject: 'sub-1',
      displayName: '아직 로그인 전',
    };
    const onParticipantChanged = vi.fn();

    routeFrame(frame, { onParticipantChanged });

    expect(onParticipantChanged).toHaveBeenCalledWith(frame);
  });

});