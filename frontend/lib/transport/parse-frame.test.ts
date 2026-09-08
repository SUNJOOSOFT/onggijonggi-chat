import { describe, expect, it } from 'vitest';
import { parseFrame, parseFrameFromText } from './parse-frame';

describe('parseFrame', () => {
  it('chat.answer를 파싱한다 (delta만)', () => {
    const frame = parseFrame({
      type: 'chat.answer',
      sessionId: 's1',
      delta: '안녕',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'streaming',
    });
    expect(frame).toEqual({
      type: 'chat.answer',
      sessionId: 's1',
      delta: '안녕',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'streaming',
    });
  });

  it('chat.answer를 파싱한다 (citations만, delta 빈 문자열 — 근거를 먼저 보내는 경우)', () => {
    const frame = parseFrame({
      type: 'chat.answer',
      sessionId: 's1',
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
      sessionId: 's1',
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
      sessionId: 's1',
      delta: '',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'done',
    });
    expect(frame).toEqual({
      type: 'chat.answer',
      sessionId: 's1',
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
        sessionId: 's1',
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
        sessionId: 's1',
        delta: '',
        citations: [],
        status: 'streaming',
      }),
    ).toBeNull();
  });

  it('chat.message를 파싱한다', () => {
    const frame = parseFrame({
      type: 'chat.message',
      sessionId: 's1',
      from: 'u1',
      content: '안녕하세요',
    });
    expect(frame).toEqual({
      type: 'chat.message',
      sessionId: 's1',
      from: 'u1',
      content: '안녕하세요',
    });
  });

  it('presence.join을 파싱한다', () => {
    const frame = parseFrame({
      type: 'presence.join',
      sessionId: 's1',
      userId: 'u1',
    });
    expect(frame).toEqual({
      type: 'presence.join',
      sessionId: 's1',
      userId: 'u1',
    });
  });

  it('presence.leave를 파싱한다', () => {
    const frame = parseFrame({
      type: 'presence.leave',
      sessionId: 's1',
      userId: 'u1',
    });
    expect(frame).toEqual({
      type: 'presence.leave',
      sessionId: 's1',
      userId: 'u1',
    });
  });

  it('presence.snapshot을 파싱한다', () => {
    const frame = parseFrame({
      type: 'presence.snapshot',
      sessionId: 's1',
      participants: ['u1', 'u2'],
    });
    expect(frame).toEqual({
      type: 'presence.snapshot',
      sessionId: 's1',
      participants: ['u1', 'u2'],
    });
  });

  it('참여자가 혼자여도 배열로 온다', () => {
    expect(
      parseFrame({
        type: 'presence.snapshot',
        sessionId: 's1',
        participants: 'u1',
      }),
    ).toBeNull();
  });

  it('error를 파싱한다 (sessionId 있음)', () => {
    const frame = parseFrame({
      type: 'error',
      sessionId: 's1',
      code: 'MODEL_UNAVAILABLE',
      message: '모델을 호출할 수 없습니다.',
      traceId: 't1',
    });
    expect(frame).toEqual({
      type: 'error',
      sessionId: 's1',
      code: 'MODEL_UNAVAILABLE',
      message: '모델을 호출할 수 없습니다.',
      traceId: 't1',
    });
  });

  it('error를 파싱한다 (sessionId null — 연결 수립 실패 등 세션에 속하지 않는 오류)', () => {
    const frame = parseFrame({
      type: 'error',
      sessionId: null,
      code: 'UNAUTHENTICATED',
      message: '인증이 필요합니다.',
      traceId: 't1',
    });
    expect(frame?.type).toBe('error');
    if (frame?.type === 'error') {
      expect(frame.sessionId).toBeNull();
    }
  });

  it('알 수 없는 type은 null (계약이 넓어지기 전의 새 프레임에 대비)', () => {
    expect(
      parseFrame({ type: 'presence.away', sessionId: 's1', userId: 'u1' }),
    ).toBeNull();
  });

  it('필드가 빠진 known type은 null', () => {
    expect(parseFrame({ type: 'chat.answer', sessionId: 's1' })).toBeNull();
  });

  it('필드 타입이 안 맞으면 null', () => {
    expect(
      parseFrame({
        type: 'chat.answer',
        sessionId: 's1',
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
    expect(parseFrame({ sessionId: 's1', delta: 'x' })).toBeNull();
  });
});

describe('parseFrameFromText', () => {
  it('유효한 JSON 문자열을 파싱한다', () => {
    const frame = parseFrameFromText(
      '{"type":"chat.answer","sessionId":"s1","delta":"","citations":[],"restrictedResultsOmitted":false,"status":"done"}',
    );
    expect(frame).toEqual({
      type: 'chat.answer',
      sessionId: 's1',
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
        sessionId: 's1',
        severity: 'warning',
        code: 'RISKY_CONTENT',
        message: '검토가 필요한 내용이 감지되었습니다.',
        traceId: 't1',
      }),
    ).toEqual({
      type: 'system.notice',
      sessionId: 's1',
      severity: 'warning',
      code: 'RISKY_CONTENT',
      message: '검토가 필요한 내용이 감지되었습니다.',
      traceId: 't1',
    });
  });

  it('info 알림을 파싱한다', () => {
    const frame = parseFrame({
      type: 'system.notice',
      sessionId: 's1',
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
      sessionId: 's1',
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
      sessionId: 's1',
      code: 'RISKY_CONTENT',
      message: '알림',
      traceId: 't1',
    });
    expect(frame?.type === 'system.notice' && frame.severity).toBe('warning');
  });

  it('방에 속하지 않는 알림은 sessionId가 null일 수 있다', () => {
    const frame = parseFrame({
      type: 'system.notice',
      sessionId: null,
      severity: 'info',
      code: 'TOKEN_BUDGET_LOW',
      message: '알림',
      traceId: 't1',
    });
    expect(frame?.type === 'system.notice' && frame.sessionId).toBeNull();
  });

  it('code가 없으면 파싱하지 않는다', () => {
    expect(
      parseFrame({
        type: 'system.notice',
        sessionId: 's1',
        severity: 'warning',
        message: '알림',
        traceId: 't1',
      }),
    ).toBeNull();
  });
});
