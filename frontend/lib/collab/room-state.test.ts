import { describe, expect, it } from 'vitest';
import type { Citation } from '@/lib/api/chat';
import type { WsFrame } from '@/lib/transport/frames';
import {
  type RoomState,
  applyFrame,
  clearRoomError,
  initialRoomState,
  isForbidden,
} from './room-state';

const THREAD = 'thread-1';

function join(userId: string): WsFrame {
  return { type: 'presence.join', sessionId: THREAD, userId };
}

function leave(userId: string): WsFrame {
  return { type: 'presence.leave', sessionId: THREAD, userId };
}

function snapshot(...participants: string[]): WsFrame {
  return { type: 'presence.snapshot', sessionId: THREAD, participants };
}

function say(from: string, content: string): WsFrame {
  return { type: 'chat.message', sessionId: THREAD, from, content };
}

function answer(
  delta: string,
  status: 'streaming' | 'done',
  metadata: {
    citations?: Citation[];
    restrictedResultsOmitted?: boolean;
  } = {},
): WsFrame {
  return {
    type: 'chat.answer',
    sessionId: THREAD,
    delta,
    citations: metadata.citations ?? [],
    restrictedResultsOmitted: metadata.restrictedResultsOmitted ?? false,
    status,
  };
}

/** 프레임을 순서대로 접는다 — 테스트가 화면 없이 대화 한 판을 재현하는 방법이다. */
function fold(
  frames: WsFrame[],
  from: RoomState = initialRoomState,
): RoomState {
  return frames.reduce(applyFrame, from);
}

describe('applyFrame - presence.join', () => {
  it('입장 순서대로 참여자를 쌓는다', () => {
    const state = fold([join('sujin'), join('minho')]);
    expect(state.participants).toEqual(['sujin', 'minho']);
  });

  it('같은 사람의 join이 두 번 와도 한 번만 센다', () => {
    const state = fold([join('sujin'), join('minho'), join('sujin')]);
    expect(state.participants).toEqual(['sujin', 'minho']);
  });
});

describe('applyFrame - presence.snapshot', () => {
  it('붙는 순간 받은 명단으로 목록을 채운다 (본인 포함)', () => {
    // 서버가 보내는 명단에는 본인이 들어 있다 — 자기 입장은 자기가 받지 않기 때문이다.
    const state = fold([snapshot('sujin', 'minho')]);
    expect(state.participants).toEqual(['sujin', 'minho']);
  });

  it('재연결로 다시 오면 그 사이 놓친 입퇴장까지 한 번에 맞춘다', () => {
    const state = fold([
      snapshot('sujin', 'minho'),
      snapshot('minho', 'jiwoo'),
    ]);
    expect(state.participants).toEqual(['minho', 'jiwoo']);
  });

  it('명단 뒤에 도착한 입퇴장을 이어서 반영한다', () => {
    const state = fold([
      snapshot('sujin', 'minho'),
      join('jiwoo'),
      leave('sujin'),
    ]);
    expect(state.participants).toEqual(['minho', 'jiwoo']);
  });
});

describe('applyFrame - presence.leave', () => {
  it('나간 사람을 목록에서 지운다', () => {
    const state = fold([join('sujin'), join('minho'), leave('sujin')]);
    expect(state.participants).toEqual(['minho']);
  });

  it('목록에 없는 사람의 퇴장은 아무것도 바꾸지 않는다', () => {
    // 스냅샷을 못 받아 존재를 모르던 사람의 퇴장이 도착할 수 있다(#26 코멘트).
    const state = fold([join('sujin'), leave('minho')]);
    expect(state.participants).toEqual(['sujin']);
  });

  it('나갔다 다시 들어오면 맨 뒤에 붙는다', () => {
    const state = fold([
      join('sujin'),
      join('minho'),
      leave('sujin'),
      join('sujin'),
    ]);
    expect(state.participants).toEqual(['minho', 'sujin']);
  });

  it('참여자만 건드리고 메시지는 남긴다', () => {
    const state = fold([
      join('sujin'),
      say('sujin', '먼저 가볼게요'),
      leave('sujin'),
    ]);
    expect(state.participants).toEqual([]);
    expect(state.messages).toHaveLength(1);
  });
});

describe('applyFrame - chat.message', () => {
  it('보낸 사람을 함께 남긴다', () => {
    const state = fold([say('sujin', '이 계약서 확인 부탁해요')]);
    expect(state.messages).toEqual([
      {
        id: 'm1',
        from: 'sujin',
        content: '이 계약서 확인 부탁해요',
        streaming: false,
        citations: [],
        restrictedResultsOmitted: false,
      },
    ]);
  });
});

describe('applyFrame - chat.answer', () => {
  it('여러 패킷을 말풍선 하나로 잇고 done에서 멈춘다', () => {
    const state = fold([
      answer('제12조에 ', 'streaming'),
      answer('따르면 ', 'streaming'),
      answer('상한은 10%입니다.', 'done'),
    ]);

    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]).toMatchObject({
      from: null,
      content: '제12조에 따르면 상한은 10%입니다.',
      streaming: false,
    });
  });

  it('답변이 끝난 뒤 오는 패킷은 새 말풍선이 된다', () => {
    const state = fold([
      answer('첫 답변', 'done'),
      answer('두 번째 답변', 'done'),
    ]);
    expect(state.messages.map((m) => m.content)).toEqual([
      '첫 답변',
      '두 번째 답변',
    ]);
  });

  it('내용이 없는 패킷으로 빈 말풍선을 만들지 않는다', () => {
    // delta 없이 status만 알리는 패킷도 계약상 유효하다(frames.ts).
    expect(fold([answer('', 'streaming')]).messages).toEqual([]);
  });

  it('답변이 흐르는 중에 다른 사람이 말해도 한 말풍선으로 잇는다', () => {
    // 협업방에서는 흔한 순서다. 맨 끝만 보고 이어붙이면 답변이 둘로 갈린다(PR #80 리뷰).
    const state = fold([
      answer('제12조에 ', 'streaming'),
      say('minho', '저도 그거 궁금했어요'),
      answer('따르면 10%입니다.', 'done'),
    ]);

    expect(state.messages.map((m) => m.from)).toEqual([null, 'minho']);
    expect(state.messages[0]).toMatchObject({
      content: '제12조에 따르면 10%입니다.',
      streaming: false,
    });
  });

  it('사람 메시지 뒤에 오면 그 메시지에 섞이지 않는다', () => {
    const state = fold([say('sujin', '@AI 요약해줘'), answer('요약', 'done')]);
    expect(state.messages.map((m) => m.from)).toEqual(['sujin', null]);
  });

  it('토큰보다 먼저 온 citations를 답변에 누적하고 docId 중복은 최신 값으로 바꾼다', () => {
    const first = {
      docId: 'd1',
      title: '초안',
      snippet: '첫 발췌',
      score: 0.5,
    };
    const updated = {
      docId: 'd1',
      title: '최종',
      snippet: '갱신 발췌',
      score: 0.9,
    };
    const second = {
      docId: 'd2',
      title: '추가',
      snippet: '둘째 발췌',
      score: 0.7,
    };

    const state = fold([
      answer('', 'streaming', { citations: [first] }),
      answer('답변', 'streaming', {
        citations: [updated, second],
        restrictedResultsOmitted: true,
      }),
      answer('', 'done'),
    ]);

    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]).toMatchObject({
      content: '답변',
      streaming: false,
      citations: [updated, second],
      restrictedResultsOmitted: true,
    });
  });
});

describe('applyFrame - error', () => {
  it('FORBIDDEN은 방 접근 거부로 읽는다', () => {
    const state = fold([
      {
        type: 'error',
        sessionId: THREAD,
        code: 'FORBIDDEN',
        message: '이 방에 접근할 수 없습니다.',
        traceId: 'trace-1',
      },
    ]);
    expect(isForbidden(state.error)).toBe(true);
    expect(state.error?.message).toBe('이 작업을 수행할 권한이 없어요.');
    expect(state.error?.traceId).toBe('trace-1');
  });

  it('그 밖의 오류는 접근 거부가 아니다', () => {
    const state = fold([
      {
        type: 'error',
        sessionId: null,
        code: 'RATE_LIMITED',
        message: '요청이 많습니다.',
        traceId: 'trace-2',
      },
    ]);
    expect(isForbidden(state.error)).toBe(false);
    expect(state.error?.code).toBe('RATE_LIMITED');
  });

  it('AI 종결 오류는 부분 답변을 보존하고 streaming만 끝낸다', () => {
    const state = fold([
      answer('부분 답변', 'streaming'),
      {
        type: 'error',
        sessionId: THREAD,
        code: 'MODEL_UNAVAILABLE',
        message: '모델 오류',
        traceId: 'trace-ai',
      },
    ]);

    expect(state.messages[0]).toMatchObject({
      content: '부분 답변',
      streaming: false,
    });
  });

  it('비종결 오류는 진행 중 AI 답변을 끝내지 않고 알림만 닫을 수 있다', () => {
    const state = fold([
      answer('진행 중', 'streaming'),
      {
        type: 'error',
        sessionId: THREAD,
        code: 'MESSAGE_DELIVERY_FAILED',
        message: '전달 실패',
        traceId: 'trace-local',
      },
    ]);

    expect(state.messages[0].streaming).toBe(true);
    expect(state.error?.message).toBe(
      '메시지를 전달하지 못했어요. 연결을 확인하고 다시 보내 주세요.',
    );
    expect(clearRoomError(state).error).toBeNull();
  });
});

describe('applyFrame', () => {
  it('받은 상태를 바꾸지 않는다', () => {
    const before = fold([join('sujin')]);
    const snapshot = structuredClone(before);
    applyFrame(before, say('sujin', '안녕하세요'));
    expect(before).toEqual(snapshot);
  });
});
