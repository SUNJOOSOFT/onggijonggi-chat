import { describe, expect, it } from 'vitest';
import { parseFrame, parseFrameFromText } from './parse-frame';

describe('parseFrame', () => {
  it('chat.answer를 파싱한다 (delta만)', () => {
    const frame = parseFrame({
      type: 'chat.answer',
      threadId: 's1',
      msgId: 'msg-1',
      turnId: null,
      model: 'm',
      seq: 1,
      delta: '안녕',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'streaming',
    });
    expect(frame).toEqual({
      type: 'chat.answer',
      threadId: 's1',
      msgId: 'msg-1',
      turnId: null,
      model: 'm',
      seq: 1,
      delta: '안녕',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'streaming',
    });
  });

  it('chat.answer를 파싱한다 (citations만, delta 빈 문자열 — 근거를 먼저 보내는 경우)', () => {
    const frame = parseFrame({
      type: 'chat.answer',
      threadId: 's1',
      msgId: 'msg-1',
      turnId: null,
      model: 'm',
      seq: 1,
      delta: '',
      citations: [{ docId: 'd1', title: '제목', snippet: '발췌', score: 0.9 }],
      restrictedResultsOmitted: false,
      status: 'streaming',
    });
    expect(frame?.type).toBe('chat.answer');
    if (frame?.type === 'chat.answer') {
      expect(frame.citations).toHaveLength(1);
      expect(frame.delta).toBe('');
    }
  });

  it('citations가 빈 배열이어도 restrictedResultsOmitted가 true일 수 있다(전부 걸러진 경우)', () => {
    const frame = parseFrame({
      type: 'chat.answer',
      threadId: 's1',
      msgId: 'msg-1',
      turnId: null,
      model: 'm',
      seq: 1,
      delta: '',
      citations: [],
      restrictedResultsOmitted: true,
      status: 'streaming',
    });
    expect(frame?.type).toBe('chat.answer');
    if (frame?.type === 'chat.answer') {
      expect(frame.citations).toEqual([]);
      expect(frame.restrictedResultsOmitted).toBe(true);
    }
  });

  it('chat.answer의 status:"done"을 파싱한다', () => {
    const frame = parseFrame({
      type: 'chat.answer',
      threadId: 's1',
      msgId: 'msg-1',
      turnId: null,
      model: 'm',
      seq: 1,
      delta: '',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'done',
    });
    expect(frame).toEqual({
      type: 'chat.answer',
      threadId: 's1',
      msgId: 'msg-1',
      turnId: null,
      model: 'm',
      seq: 1,
      delta: '',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'done',
    });
  });

  it('status가 streaming/done이 아니면 null', () => {
    expect(
      parseFrame({
        type: 'chat.answer',
        threadId: 's1',
        msgId: 'msg-1',
        turnId: null,
        model: 'm',
        seq: 1,
        delta: '',
        citations: [],
        restrictedResultsOmitted: false,
        status: 'finished',
      }),
    ).toBeNull();
  });

  it('restrictedResultsOmitted가 빠지면 null', () => {
    expect(
      parseFrame({
        type: 'chat.answer',
        threadId: 's1',
        msgId: 'msg-1',
        turnId: null,
        model: 'm',
        seq: 1,
        delta: '',
        citations: [],
        status: 'streaming',
      }),
    ).toBeNull();
  });

  it('chat.message를 파싱한다', () => {
    const frame = parseFrame({
      type: 'chat.message',
      threadId: 's1',
      msgId: 'msg-1',
      clientMsgId: null,
      turnId: null,
      seq: 1,
      from: 'u1',
      fromDisplayName: '주성민',
      content: '안녕하세요',
    });
    expect(frame).toEqual({
      type: 'chat.message',
      threadId: 's1',
      msgId: 'msg-1',
      clientMsgId: null,
      turnId: null,
      seq: 1,
      from: 'u1',
      fromDisplayName: '주성민',
      content: '안녕하세요',
    });
  });

  it('presence.join을 파싱한다', () => {
    const frame = parseFrame({
      type: 'presence.join',
      threadId: 's1',
      subject: 'u1',
      displayName: '주성민',
    });
    expect(frame).toEqual({
      type: 'presence.join',
      threadId: 's1',
      subject: 'u1',
      displayName: '주성민',
    });
  });

  it('presence.leave를 파싱한다', () => {
    const frame = parseFrame({
      type: 'presence.leave',
      threadId: 's1',
      subject: 'u1',
      displayName: '주성민',
    });
    expect(frame).toEqual({
      type: 'presence.leave',
      threadId: 's1',
      subject: 'u1',
      displayName: '주성민',
    });
  });

  it('presence.snapshot을 파싱한다', () => {
    const frame = parseFrame({
      type: 'presence.snapshot',
      threadId: 's1',
      participants: [
        { subject: 'u1', displayName: '주성민' },
        { subject: 'u2', displayName: '이한결' },
      ],
    });
    expect(frame).toEqual({
      type: 'presence.snapshot',
      threadId: 's1',
      participants: [
        { subject: 'u1', displayName: '주성민' },
        { subject: 'u2', displayName: '이한결' },
      ],
    });
  });

  it('참여자가 혼자여도 배열로 온다', () => {
    expect(
      parseFrame({
        type: 'presence.snapshot',
        threadId: 's1',
        participants: 'u1',
      }),
    ).toBeNull();
  });

  it('error를 파싱한다 (threadId 있음)', () => {
    const frame = parseFrame({
      type: 'error',
      threadId: 's1',
      code: 'MODEL_UNAVAILABLE',
      message: '모델을 호출할 수 없습니다.',
      traceId: 't1',
    });
    expect(frame).toEqual({
      type: 'error',
      threadId: 's1',
      code: 'MODEL_UNAVAILABLE',
      message: '모델을 호출할 수 없습니다.',
      traceId: 't1',
    });
  });

  it('error를 파싱한다 (threadId null — 연결 수립 실패 등 방에 속하지 않는 오류)', () => {
    const frame = parseFrame({
      type: 'error',
      threadId: null,
      code: 'UNAUTHENTICATED',
      message: '인증이 필요합니다.',
      traceId: 't1',
    });
    expect(frame?.type).toBe('error');
    if (frame?.type === 'error') {
      expect(frame.threadId).toBeNull();
    }
  });

  it('알 수 없는 type은 null (계약이 넓어지기 전의 새 프레임에 대비)', () => {
    expect(
      parseFrame({ type: 'presence.away', threadId: 's1', userId: 'u1' }),
    ).toBeNull();
  });

  it('필드가 빠진 known type은 null', () => {
    expect(parseFrame({ type: 'chat.answer', threadId: 's1' })).toBeNull();
  });

  it('필드 타입이 안 맞으면 null', () => {
    expect(
      parseFrame({
        type: 'chat.answer',
        threadId: 's1',
        msgId: 'msg-1',
        turnId: null,
        model: 'm',
        seq: 1,
        delta: 123,
        citations: [],
        restrictedResultsOmitted: false,
        status: 'streaming',
      }),
    ).toBeNull();
  });

  it('객체가 아니면 null', () => {
    expect(parseFrame('not an object')).toBeNull();
    expect(parseFrame(42)).toBeNull();
    expect(parseFrame(null)).toBeNull();
    expect(parseFrame(undefined)).toBeNull();
    expect(parseFrame(['type', 'chat.answer'])).toBeNull();
  });

  it('type 필드 자체가 없으면 null', () => {
    expect(parseFrame({ threadId: 's1', delta: 'x' })).toBeNull();
  });
});

describe('parseFrameFromText', () => {
  it('유효한 JSON 문자열을 파싱한다', () => {
    const frame = parseFrameFromText(
      '{"type":"chat.answer","threadId":"s1","msgId":"msg-1","turnId":null,"model":"m","seq":1,"delta":"","citations":[],"restrictedResultsOmitted":false,"status":"done"}',
    );
    expect(frame).toEqual({
      type: 'chat.answer',
      threadId: 's1',
      msgId: 'msg-1',
      turnId: null,
      model: 'm',
      seq: 1,
      delta: '',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'done',
    });
  });

  it('깨진 JSON은 예외를 던지지 않고 null을 반환한다', () => {
    expect(parseFrameFromText('{"type":"chat.answer"')).toBeNull();
    expect(parseFrameFromText('')).toBeNull();
  });

  it('유효한 JSON이지만 알려진 프레임이 아니면 null', () => {
    expect(parseFrameFromText('{"hello":"world"}')).toBeNull();
  });
});

describe('parseFrame — system.notice(#29)', () => {
  it('warning 알림을 파싱한다', () => {
    expect(
      parseFrame({
        type: 'system.notice',
        threadId: 's1',
        severity: 'warning',
        code: 'RISKY_CONTENT',
        message: '검토가 필요한 내용이 감지되었습니다.',
        traceId: 't1',
      }),
    ).toEqual({
      type: 'system.notice',
      threadId: 's1',
      severity: 'warning',
      code: 'RISKY_CONTENT',
      message: '검토가 필요한 내용이 감지되었습니다.',
      traceId: 't1',
    });
  });

  it('info 알림을 파싱한다', () => {
    const frame = parseFrame({
      type: 'system.notice',
      threadId: 's1',
      severity: 'info',
      code: 'TOKEN_BUDGET_LOW',
      message: '한도에 가까워지고 있습니다.',
      traceId: 't1',
    });
    expect(frame?.type === 'system.notice' && frame.severity).toBe('info');
  });

  // 서버가 나중에 severity를 늘려도 프론트 배포 전까지 알림이 사라지면 안 된다 — 위험 알림은
  // 덜 정확하게 보이는 것보다 안 보이는 쪽이 나쁘다(#29 확정).
  it('모르는 severity는 버리지 않고 warning으로 받는다', () => {
    const frame = parseFrame({
      type: 'system.notice',
      threadId: 's1',
      severity: 'critical',
      code: 'RISKY_CONTENT',
      message: '알림',
      traceId: 't1',
    });
    expect(frame?.type === 'system.notice' && frame.severity).toBe('warning');
  });

  it('severity 필드가 아예 없어도 warning으로 받는다', () => {
    const frame = parseFrame({
      type: 'system.notice',
      threadId: 's1',
      code: 'RISKY_CONTENT',
      message: '알림',
      traceId: 't1',
    });
    expect(frame?.type === 'system.notice' && frame.severity).toBe('warning');
  });

  it('방에 속하지 않는 알림은 threadId가 null일 수 있다', () => {
    const frame = parseFrame({
      type: 'system.notice',
      threadId: null,
      severity: 'info',
      code: 'TOKEN_BUDGET_LOW',
      message: '알림',
      traceId: 't1',
    });
    expect(frame?.type === 'system.notice' && frame.threadId).toBeNull();
  });

  it('code가 없으면 파싱하지 않는다', () => {
    expect(
      parseFrame({
        type: 'system.notice',
        threadId: 's1',
        severity: 'warning',
        message: '알림',
        traceId: 't1',
      }),
    ).toBeNull();
  });
  it('participant.changed를 통과시킨다', () => {
    // frames.ts 유니온에만 더하고 여기 스키마를 빠뜨리면 컴파일은 통과하고 이 프레임만
    // 조용히 버려진다 — 실제로 한 번 그렇게 놓쳐서 남기는 테스트다.
    const frame = parseFrame({
      type: 'participant.changed',
      threadId: 'room-1',
      action: 'INVITE_PENDING',
      subject: 'sub-1',
      displayName: '아직 로그인 전',
    });

    expect(frame?.type).toBe('participant.changed');
  });

  it('모르는 action이 와도 버리지 않는다', () => {
    // 서버가 액션을 하나 더한 뒤 프론트를 아직 배포하지 않은 상태다. 화면은 action으로
    // 분기하지 않으므로 통지 자체는 살아 있어야 한다.
    const frame = parseFrame({
      type: 'participant.changed',
      threadId: 'room-1',
      action: 'SOMETHING_NEW',
      subject: 'sub-1',
      displayName: '누군가',
    });

    expect(frame?.type).toBe('participant.changed');
  });

  it('chat.queued와 pong을 파싱한다(이슈 #160)', () => {
    const queued = {
      type: 'chat.queued',
      threadId: 'room-1',
      turnId: null,
      status: 'cancelled',
    };
    expect(parseFrame(queued)).toEqual(queued);
    expect(parseFrame({ type: 'pong' })).toEqual({ type: 'pong' });
    expect(parseFrame({ ...queued, status: 'started' })).toBeNull();
  });

  it('chat.message는 clientMsgId·turnId를, chat.answer는 turnId·model을 요구한다 — 서버가 null이어도 늘 싣는다(이슈 #160)', () => {
    const message = {
      type: 'chat.message',
      threadId: 's1',
      msgId: 'msg-1',
      seq: 1,
      from: 'u1',
      fromDisplayName: '주성민',
      content: '안녕하세요',
    };
    expect(parseFrame({ ...message, clientMsgId: 'c1' })).toBeNull();
    expect(parseFrame({ ...message, clientMsgId: 'c1', turnId: 't1' })).toEqual(
      {
        ...message,
        clientMsgId: 'c1',
        turnId: 't1',
      },
    );
    const answer = {
      type: 'chat.answer',
      threadId: 's1',
      msgId: 'msg-2',
      turnId: 't1',
      seq: 2,
      delta: '',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'done',
    };
    expect(parseFrame(answer)).toBeNull();
    expect(parseFrame({ ...answer, model: 'm' })).toEqual({
      ...answer,
      model: 'm',
    });
  });

});