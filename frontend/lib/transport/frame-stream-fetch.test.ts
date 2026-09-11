import { describe, expect, it, vi } from 'vitest';
import type { BffErrorEnvelope } from '@/lib/api/errors';
import { frameSourceToResponse } from './frame-stream-fetch';
import {
  errorMidStreamFrames,
  goldenPathFrames,
  mockFrameSource,
  restrictedCitationsFrames,
  tokensOnlyFrames,
} from './mock-frame-source';
import type { WsFrame } from './frames';

describe('frameSourceToResponse — 정상 흐름', () => {
  it('token들을 이어붙인 text/plain 200 응답을 만든다', async () => {
    const frames = goldenPathFrames({ tokens: ['안', '녕'] });
    const response = await frameSourceToResponse(mockFrameSource(frames));

    expect(response.status).toBe(200);
    expect(response.headers.get('Content-Type')).toContain('text/plain');
    await expect(response.text()).resolves.toBe('안녕');
  });

  it('citations는 body에 섞이지 않고 콜백으로만(REST CitationsResponse와 같은 모양으로) 전달된다', async () => {
    const frames = goldenPathFrames({
      tokens: ['안', '녕'],
      citations: [{ docId: 'd1', title: '제목', snippet: '발췌', score: 0.9 }],
    });
    const onChatCitation = vi.fn();
    const response = await frameSourceToResponse(mockFrameSource(frames), {
      onChatCitation,
    });

    const text = await response.text();
    expect(text).toBe('안녕');
    expect(text).not.toContain('docId');
    expect(onChatCitation).toHaveBeenCalledExactlyOnceWith({
      citations: [{ docId: 'd1', title: '제목', snippet: '발췌', score: 0.9 }],
      restrictedResultsOmitted: false,
    });
  });

  it('citations가 빈 배열이어도 restrictedResultsOmitted가 true면 콜백이 불린다(전부 걸러진 경우)', async () => {
    const frames = restrictedCitationsFrames({ tokens: ['안', '녕'] });
    const onChatCitation = vi.fn();
    const response = await frameSourceToResponse(mockFrameSource(frames), {
      onChatCitation,
    });

    await expect(response.text()).resolves.toBe('안녕');
    expect(onChatCitation).toHaveBeenCalledExactlyOnceWith({
      citations: [],
      restrictedResultsOmitted: true,
    });
  });

  it('citations가 delta 없이(빈 문자열) 먼저 도착하면, 첫 실제 토큰이 읽히기 전에 이미 콜백이 불려 있다', async () => {
    const frames = goldenPathFrames({ tokens: ['a', 'b', 'c'] });
    const order: string[] = [];
    const onChatCitation = vi.fn(() => order.push('citation'));
    const response = await frameSourceToResponse(mockFrameSource(frames), {
      onChatCitation,
    });

    const reader = response.body?.getReader();
    if (!reader) throw new Error('no body');
    // 첫 청크(첫 실제 토큰)가 읽힐 때까지 기다린다 — citation 전용 패킷은 delta가 비어 있어
    // 그 자체로는 청크를 만들지 않으므로, 이 read()는 그 다음 토큰 패킷까지 기다리게 된다.
    // 그 사이에 citation 콜백은 이미 불렸어야 한다.
    await reader.read();
    expect(order).toEqual(['citation']);
  });

  it('citation·restriction 둘 다 없이 토큰만 오는 흐름도 정상 동작한다', async () => {
    const frames = tokensOnlyFrames({ tokens: ['x', 'y'] });
    const onChatCitation = vi.fn();
    const response = await frameSourceToResponse(mockFrameSource(frames), {
      onChatCitation,
    });

    await expect(response.text()).resolves.toBe('xy');
    expect(onChatCitation).not.toHaveBeenCalled();
  });
});

describe('frameSourceToResponse — 스트림 시작 전 오류(첫 프레임이 error)', () => {
  it('기존 HTTP 에러 봉투와 같은 모양의 JSON을 상태코드와 함께 반환한다', async () => {
    const frames: WsFrame[] = [
      {
        type: 'error',
        sessionId: 's1',
        code: 'MODEL_UNAVAILABLE',
        message: '모델 호출 불가',
        traceId: 't1',
      },
    ];
    const response = await frameSourceToResponse(mockFrameSource(frames));

    expect(response.ok).toBe(false);
    expect(response.status).toBe(502);
    const body = (await response.json()) as BffErrorEnvelope;
    expect(body).toEqual({
      error: {
        code: 'MODEL_UNAVAILABLE',
        message: '모델 호출 불가',
        traceId: 't1',
      },
    });
  });

  it.each([
    ['UNAUTHENTICATED', 401],
    ['TOKEN_EXPIRED', 401],
    ['TOKEN_INVALID', 401],
    ['FORBIDDEN', 403],
    ['RATE_LIMITED', 429],
    ['VALIDATION_ERROR', 400],
    ['SOME_FUTURE_CODE', 500],
  ])(
    'code=%s → status=%i (GlobalExceptionHandler.java 매핑과 동일)',
    async (code, expectedStatus) => {
      const frames: WsFrame[] = [
        { type: 'error', sessionId: 's1', code, message: 'x', traceId: 't1' },
      ];
      const response = await frameSourceToResponse(mockFrameSource(frames));
      expect(response.status).toBe(expectedStatus);
    },
  );
});

describe('frameSourceToResponse — 스트림 도중 오류', () => {
  it('토큰이 이미 흐른 뒤 error가 오면 200으로 시작하고, body 읽기가 끊긴다(스트림 중단 경로)', async () => {
    const frames = errorMidStreamFrames({ tokensBeforeError: ['안'] });
    const response = await frameSourceToResponse(mockFrameSource(frames));

    // 헤더는 이미 커밋된 뒤라 상태코드는 그대로 200 — chat.tsx의 isStreamTruncated 경로로
    // 넘어가는 게 의도된 동작이다(에러 봉투 없이 "응답이 중단되었습니다"로 안내).
    expect(response.status).toBe(200);
    await expect(response.text()).rejects.toThrow();
  });
});

describe('frameSourceToResponse — 손상된 프레임', () => {
  it('중간에 파싱 안 되는 문자열이 섞여도 나머지 프레임은 정상 처리된다', async () => {
    async function* sourceWithGarbage() {
      yield JSON.stringify({
        type: 'chat.answer',
        sessionId: 's1',
        msgId: 'msg-1',
        seq: 1,
        delta: '안',
        citations: [],
        restrictedResultsOmitted: false,
        status: 'streaming',
      });
      yield 'this is not json';
      yield JSON.stringify({
        type: 'chat.answer',
        sessionId: 's1',
        msgId: 'msg-1',
        seq: 1,
        delta: '녕',
        citations: [],
        restrictedResultsOmitted: false,
        status: 'streaming',
      });
      yield JSON.stringify({
        type: 'chat.answer',
        sessionId: 's1',
        msgId: 'msg-1',
        seq: 1,
        delta: '',
        citations: [],
        restrictedResultsOmitted: false,
        status: 'done',
      });
    }
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const response = await frameSourceToResponse(sourceWithGarbage());

    await expect(response.text()).resolves.toBe('안녕');
    expect(warnSpy).toHaveBeenCalledOnce();
    warnSpy.mockRestore();
  });
});

describe('frameSourceToResponse — 빈 소스', () => {
  it('프레임을 하나도 안 보내고 끝나면 빈 200 응답을 만든다', async () => {
    async function* emptySource() {}
    const response = await frameSourceToResponse(emptySource());

    expect(response.status).toBe(200);
    await expect(response.text()).resolves.toBe('');
  });
});
