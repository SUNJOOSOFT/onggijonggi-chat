import { describe, expect, it } from 'vitest';
import type { WsFrame } from './frames';

/** 하네스 배선 확인용 스모크 테스트. 실제 파싱·라우팅 테스트는 parse-frame.test.ts로 옮겨간다. */
describe('WsFrame 유니온', () => {
  it('type 태그로 프레임을 판별할 수 있다', () => {
    const frame: WsFrame = {
      type: 'chat.answer',
      sessionId: 's1',
      msgId: 'msg-1',
      seq: 1,
      delta: 'hi',
      citations: [],
      restrictedResultsOmitted: false,
      status: 'streaming',
    };
    expect(frame.type).toBe('chat.answer');
  });
});
