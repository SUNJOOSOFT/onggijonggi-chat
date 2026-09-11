import { describe, expect, it, vi } from 'vitest';
import type { Citation } from '@/lib/api/chat';
import type { ChatMessageFrame } from '@/lib/transport/frames';
import {
  aiPrompt,
  aiTurnFrames,
  ERROR_BEFORE_THREAD_ID,
  ERROR_MID_THREAD_ID,
  FORBIDDEN_FRAME_THREAD_ID,
  FORBIDDEN_HANDSHAKE_THREAD_ID,
  mentionsAi,
  type MockAiJob,
  MockAiQueue,
  MockRoomRegistry,
  NORMAL_THREAD_ID,
  parseInboundMessage,
  parseWsPath,
  roomAccess,
  type RoomMember,
  scenarioForRoom,
} from './rooms';

const SAMPLE_CITATION: Citation = {
  docId: 'doc-1',
  title: '샘플 문서',
  snippet: '테스트용 발췌',
  score: 1,
};

function member(id: string, subject = id) {
  const send = vi.fn<(data: string) => void>();
  return {
    id,
    subject,
    displayName: `${subject} 님`,
    send,
  } satisfies RoomMember;
}

function say(content: string): ChatMessageFrame {
  return {
    type: 'chat.message',
    sessionId: NORMAL_THREAD_ID,
    msgId: 'msg-1',
    seq: 1,
    from: 'alice',
    fromDisplayName: '보낸 사람',
    content,
  };
}

function job(prompt: string): MockAiJob {
  return {
    threadId: NORMAL_THREAD_ID,
    generation: 'g1',
    prompt,
    traceId: `trace-${prompt}`,
  };
}

describe('parseWsPath', () => {
  it('정확한 UUID 방 경로만 받는다', () => {
    expect(parseWsPath(`/api/ws/${NORMAL_THREAD_ID}`)).toEqual({
      kind: 'valid',
      threadId: NORMAL_THREAD_ID,
    });
    expect(parseWsPath('/api/ws')).toBeNull();
    expect(parseWsPath(`/api/ws/${NORMAL_THREAD_ID}/extra`)).toBeNull();
  });

  it('형식이 잘못된 threadId는 핸드셰이크 뒤 오류를 보낼 수 있게 구분한다', () => {
    expect(parseWsPath('/api/ws/not-a-uuid')).toEqual({
      kind: 'invalid-thread',
      threadId: 'not-a-uuid',
    });
  });

  it('백엔드 UUID.fromString처럼 조각별 자릿수를 강제하지 않는다', () => {
    // Long.parseLong(part, 16)은 조각 자릿수를 검사하지 않으므로 표준 8-4-4-4-12보다
    // 짧거나 긴 16진 조각도 실서버는 통과시킨다 — 목업도 같은 threadId를 받아야 한다.
    expect(parseWsPath('/api/ws/1-2-3-4-5')).toEqual({
      kind: 'valid',
      threadId: '1-2-3-4-5',
    });
    expect(
      parseWsPath('/api/ws/123456789-1111-4111-8111-111111111111'),
    ).toEqual({
      kind: 'valid',
      threadId: '123456789-1111-4111-8111-111111111111',
    });
  });

  it('Long.parseLong 범위를 넘는 조각은 서버처럼 잘못된 threadId로 본다', () => {
    expect(parseWsPath('/api/ws/12345678901234567-1-1-1-1')).toEqual({
      kind: 'invalid-thread',
      threadId: '12345678901234567-1-1-1-1',
    });
    expect(parseWsPath('/api/ws/ffffffffffffffff-1-1-1-1')).toEqual({
      kind: 'invalid-thread',
      threadId: 'ffffffffffffffff-1-1-1-1',
    });
  });
});

describe('roomAccess', () => {
  it('예약 threadId만 접근을 거부하고 방식을 구분한다', () => {
    expect(roomAccess(NORMAL_THREAD_ID)).toBe('allow');
    expect(roomAccess(FORBIDDEN_HANDSHAKE_THREAD_ID)).toBe('deny-handshake');
    expect(roomAccess(FORBIDDEN_FRAME_THREAD_ID)).toBe('deny-frame');
  });
});

describe('parseInboundMessage', () => {
  it('최소 DTO를 받고 정상 content의 공백을 보존한다', () => {
    expect(
      parseInboundMessage(
        JSON.stringify({ type: 'chat.message', content: '  hello  ' }),
      ),
    ).toEqual({
      kind: 'message',
      message: { type: 'chat.message', content: '  hello  ' },
    });
  });

  it('서버 전용 타입은 무시하고 깨진 입력과 빈 content는 거부한다', () => {
    expect(
      parseInboundMessage(JSON.stringify({ type: 'chat.answer' })),
    ).toEqual({ kind: 'ignore' });
    expect(parseInboundMessage('{')).toEqual({ kind: 'malformed' });
    expect(
      parseInboundMessage(
        JSON.stringify({ type: 'chat.message', content: '   ' }),
      ),
    ).toEqual({ kind: 'malformed' });
  });
});

describe('AI mention', () => {
  it('백엔드와 같은 경계로 판정하고 모든 멘션을 제거한다', () => {
    expect(mentionsAi('@AI 첫 요청 @AI야 두 번째')).toBe(true);
    expect(aiPrompt('@AI 첫 요청 @AI야 두 번째')).toBe('첫 요청 야 두 번째');
    expect(mentionsAi('team@AI')).toBe(false);
    expect(mentionsAi('@AIX')).toBe(false);
  });

  it('예약 UUID 방마다 결정적인 오류 시점을 고른다', () => {
    expect(scenarioForRoom(NORMAL_THREAD_ID)).toBe('normal');
    expect(scenarioForRoom(ERROR_BEFORE_THREAD_ID)).toBe('error-before');
    expect(scenarioForRoom(ERROR_MID_THREAD_ID)).toBe('error-mid');
  });
});

describe('aiTurnFrames', () => {
  it('normal 방은 citations를 선행 방송한 뒤 토큰을 흘리고 done으로 끝난다', () => {
    const frames = aiTurnFrames(
      NORMAL_THREAD_ID,
      'trace-1',
      'hi there',
      'normal',
      SAMPLE_CITATION,
    );

    expect(frames[0]).toMatchObject({
      type: 'chat.answer',
      delta: '',
      status: 'streaming',
      citations: [SAMPLE_CITATION],
    });
    expect(frames.at(-1)).toMatchObject({
      type: 'chat.answer',
      status: 'done',
    });
    expect(frames.slice(1, -1).every((f) => f.type === 'chat.answer')).toBe(
      true,
    );
    // 토큰이 이어붙으면 원래 답변 문구가 그대로 복원돼야 한다.
    const rebuilt = frames
      .slice(1, -1)
      .map((f) => (f as { delta: string }).delta)
      .join('');
    expect(rebuilt).toBe('「목업 응답」 hi there');
  });

  it('error-before 방은 토큰 없이 종결 오류 하나만 보낸다', () => {
    const frames = aiTurnFrames(
      ERROR_BEFORE_THREAD_ID,
      'trace-2',
      'hi',
      'error-before',
      SAMPLE_CITATION,
    );

    expect(frames).toHaveLength(1);
    expect(frames[0]).toMatchObject({
      type: 'error',
      code: 'MODEL_UNAVAILABLE',
      traceId: 'trace-2',
    });
  });

  it('error-mid 방은 첫 토큰 뒤 종결 오류로 끝나고 done을 보내지 않는다', () => {
    const frames = aiTurnFrames(
      ERROR_MID_THREAD_ID,
      'trace-3',
      'hi there',
      'error-mid',
      SAMPLE_CITATION,
    );

    expect(frames).toHaveLength(2);
    expect(frames[0]).toMatchObject({
      type: 'chat.answer',
      status: 'streaming',
    });
    expect(frames[1]).toMatchObject({
      type: 'error',
      code: 'MODEL_UNAVAILABLE',
      traceId: 'trace-3',
    });
    expect(
      frames.some((f) => f.type === 'chat.answer' && f.status === 'done'),
    ).toBe(false);
  });
});

describe('MockRoomRegistry', () => {
  it('송신자를 포함해 같은 방에 방송하고 다른 방과 격리한다', () => {
    const registry = new MockRoomRegistry();
    const alice = member('c1');
    const bob = member('c2');
    const elsewhere = member('c3');
    const generation = registry.join(NORMAL_THREAD_ID, alice);
    expect(registry.join(NORMAL_THREAD_ID, bob)).toBe(generation);
    registry.join(ERROR_BEFORE_THREAD_ID, elsewhere);

    expect(
      registry.broadcastIfCurrent(NORMAL_THREAD_ID, generation, say('hello')),
    ).toBe(true);
    expect(alice.send).toHaveBeenCalledOnce();
    expect(bob.send).toHaveBeenCalledOnce();
    expect(elsewhere.send).not.toHaveBeenCalled();
  });

  it('마지막 퇴장 뒤 이전 generation 방송을 새 방으로 보내지 않는다', () => {
    const registry = new MockRoomRegistry();
    const oldMember = member('old');
    const oldGeneration = registry.join(NORMAL_THREAD_ID, oldMember);
    expect(registry.leave(NORMAL_THREAD_ID, 'old', oldGeneration)).toBe(true);

    const newMember = member('new');
    const newGeneration = registry.join(NORMAL_THREAD_ID, newMember);
    expect(newGeneration).not.toBe(oldGeneration);
    expect(
      registry.broadcastIfCurrent(
        NORMAL_THREAD_ID,
        oldGeneration,
        say('stale'),
      ),
    ).toBe(false);
    expect(newMember.send).not.toHaveBeenCalled();
  });
});

describe('MockAiQueue', () => {
  it('방별 하나만 실행하고 대기열을 FIFO로 시작한다', async () => {
    const started: string[] = [];
    const completions: (() => void)[] = [];
    const queue = new MockAiQueue(
      (item) =>
        new Promise<void>((resolve) => {
          started.push(item.prompt);
          completions.push(resolve);
        }),
      2,
    );

    expect(queue.enqueue(job('one'))).toBe(true);
    expect(queue.enqueue(job('two'))).toBe(true);
    expect(queue.enqueue(job('three'))).toBe(true);
    expect(queue.enqueue(job('overflow'))).toBe(false);
    expect(started).toEqual(['one']);

    completions.shift()?.();
    await vi.waitFor(() => expect(started).toEqual(['one', 'two']));
    completions.shift()?.();
    await vi.waitFor(() => expect(started).toEqual(['one', 'two', 'three']));
    completions.shift()?.();
  });
});
