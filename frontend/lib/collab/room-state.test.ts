import type { CollabMessageItem } from '@/lib/api/collab';
import { describe, expect, it } from 'vitest';
import type { Citation } from '@/lib/api/chat';
import type { PresenceParticipant, WsFrame } from '@/lib/transport/frames';
import {
  type CollabMessage,
  type RoomState,
  applyFrame,
  applyHistory,
  clearRoomError,
  dismissNotice,
  initialRoomState,
  isForbidden,
  isPresenceNotice,
} from './room-state';

const THREAD = 'thread-1';

/** subject를 받아 그 사람을 만든다. 표시 이름은 subject에서 파생시켜 둘이 섞이면 눈에 띄게 한다. */
function person(subject: string): PresenceParticipant {
  return { subject, displayName: `${subject} 님` };
}

function join(subject: string): WsFrame {
  return { type: 'presence.join', sessionId: THREAD, ...person(subject) };
}

function leave(subject: string): WsFrame {
  return { type: 'presence.leave', sessionId: THREAD, ...person(subject) };
}

function snapshot(...subjects: string[]): WsFrame {
  return {
    type: 'presence.snapshot',
    sessionId: THREAD,
    participants: subjects.map(person),
  };
}

/** 프레임마다 새 msgId를 준다 — 서버가 메시지마다 다른 id를 싣는 것과 같다(이슈 #190). */
let frameCounter = 0;

function say(from: string, content: string, msgId?: string): WsFrame {
  frameCounter += 1;
  return {
    type: 'chat.message',
    sessionId: THREAD,
    msgId: msgId ?? `msg-${frameCounter}`,
    seq: frameCounter,
    from,
    fromDisplayName: `${from} 님`,
    content,
  };
}

function notice(
  severity: 'warning' | 'info',
  code: string,
  message: string,
): WsFrame {
  return {
    type: 'system.notice',
    sessionId: THREAD,
    severity,
    code,
    message,
    traceId: `trace-${code}`,
  };
}

function answer(
  delta: string,
  status: 'streaming' | 'done',
  metadata: {
    citations?: Citation[];
    restrictedResultsOmitted?: boolean;
    /** 같은 턴의 패킷은 같은 msgId를 단다. 턴을 나누고 싶을 때만 다른 값을 준다. */
    msgId?: string;
  } = {},
): WsFrame {
  return {
    type: 'chat.answer',
    sessionId: THREAD,
    msgId: metadata.msgId ?? 'agent-msg-1',
    seq: 1000,
    delta,
    citations: metadata.citations ?? [],
    restrictedResultsOmitted: metadata.restrictedResultsOmitted ?? false,
    status,
  };
}

/** 입퇴장 시스템 라인(#111)을 뺀 사람·AI 메시지만. 대화 쪽을 보는 테스트가 쓴다. */
function chats(state: RoomState): CollabMessage[] {
  return state.messages.filter(
    (entry): entry is CollabMessage => !isPresenceNotice(entry),
  );
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
    expect(state.participants).toEqual([person('sujin'), person('minho')]);
  });

  it('같은 사람의 join이 두 번 와도 한 번만 센다', () => {
    const state = fold([join('sujin'), join('minho'), join('sujin')]);
    expect(state.participants).toEqual([person('sujin'), person('minho')]);
  });
});

describe('applyFrame - presence.snapshot', () => {
  it('붙는 순간 받은 명단으로 목록을 채운다 (본인 포함)', () => {
    // 서버가 보내는 명단에는 본인이 들어 있다 — 자기 입장은 자기가 받지 않기 때문이다.
    const state = fold([snapshot('sujin', 'minho')]);
    expect(state.participants).toEqual([person('sujin'), person('minho')]);
  });

  it('재연결로 다시 오면 그 사이 놓친 입퇴장까지 한 번에 맞춘다', () => {
    const state = fold([
      snapshot('sujin', 'minho'),
      snapshot('minho', 'jiwoo'),
    ]);
    expect(state.participants).toEqual([person('minho'), person('jiwoo')]);
  });

  it('명단 뒤에 도착한 입퇴장을 이어서 반영한다', () => {
    const state = fold([
      snapshot('sujin', 'minho'),
      join('jiwoo'),
      leave('sujin'),
    ]);
    expect(state.participants).toEqual([person('minho'), person('jiwoo')]);
  });
});

describe('applyFrame - presence.leave', () => {
  it('나간 사람을 목록에서 지운다', () => {
    const state = fold([join('sujin'), join('minho'), leave('sujin')]);
    expect(state.participants).toEqual([person('minho')]);
  });

  it('목록에 없는 사람의 퇴장은 아무것도 바꾸지 않는다', () => {
    // 스냅샷을 못 받아 존재를 모르던 사람의 퇴장이 도착할 수 있다(#26 코멘트).
    const state = fold([join('sujin'), leave('minho')]);
    expect(state.participants).toEqual([person('sujin')]);
    // 흐름에도 남기지 않는다 — 목록에서 지울 사람이 없으면 알릴 사건도 없다.
    expect(state.messages).toEqual([
      { id: 'm1', event: 'join', participant: person('sujin') },
    ]);
  });

  it('나갔다 다시 들어오면 맨 뒤에 붙는다', () => {
    const state = fold([
      join('sujin'),
      join('minho'),
      leave('sujin'),
      join('sujin'),
    ]);
    expect(state.participants).toEqual([person('minho'), person('sujin')]);
  });

  it('참여자만 건드리고 대화 메시지는 남긴다', () => {
    const state = fold([
      join('sujin'),
      say('sujin', '먼저 가볼게요'),
      leave('sujin'),
    ]);
    expect(state.participants).toEqual([]);
    // 흐름에는 입장·퇴장 시스템 라인이 함께 남는다(#111) — 대화 메시지만 세어 확인한다.
    expect(chats(state)).toHaveLength(1);
  });
});

describe('applyFrame - 입퇴장 시스템 라인(#111)', () => {
  it('입장과 퇴장을 흐름에 시간 순서로 남긴다', () => {
    const state = fold([
      join('sujin'),
      say('sujin', '안녕하세요'),
      leave('sujin'),
    ]);
    expect(state.messages).toEqual([
      { id: 'm1', event: 'join', participant: person('sujin') },
      {
        id: expect.any(String),
        seq: expect.any(Number),
        from: person('sujin'),
        content: '안녕하세요',
        streaming: false,
        citations: [],
        restrictedResultsOmitted: false,
      },
      // 입퇴장 줄 번호는 서버 msgId를 쓰는 메시지와 카운터를 나눠 쓰지 않는다(이슈 #190).
      { id: 'm2', event: 'leave', participant: person('sujin') },
    ]);
  });

  it('명단(스냅샷)은 줄을 만들지 않는다 — 상태이지 사건이 아니다', () => {
    // 붙는 순간 받는 명단으로 "N명이 입장했습니다"가 우수수 뜨면 안 된다. B안(전용 스냅샷
    // 프레임)을 택한 이유가 이것이다(#26).
    const state = fold([snapshot('sujin', 'minho')]);
    expect(state.messages).toEqual([]);
    expect(state.participants).toEqual([person('sujin'), person('minho')]);
  });

  it('이미 아는 사람의 join이 또 와도 줄을 늘리지 않는다', () => {
    const state = fold([join('sujin'), join('sujin')]);
    expect(state.messages).toHaveLength(1);
  });

  it('시스템 라인이 흐르는 AI 답변을 끊지 않는다', () => {
    // 답변이 흐르는 도중 누가 들어와도 이어지는 delta는 같은 말풍선에 붙어야 한다.
    const state = fold([
      answer('요약을 ', 'streaming'),
      join('minho'),
      answer('시작합니다', 'done'),
    ]);
    expect(chats(state)).toHaveLength(1);
    expect(chats(state)[0].content).toBe('요약을 시작합니다');
    expect(state.messages[1]).toEqual({
      id: 'm1',
      event: 'join',
      participant: person('minho'),
    });
  });
});

describe('applyFrame - chat.message', () => {
  it('보낸 사람을 함께 남긴다', () => {
    const state = fold([say('sujin', '이 계약서 확인 부탁해요', 'msg-sujin-1')]);
    expect(state.messages).toEqual([
      {
        id: 'msg-sujin-1',
        seq: expect.any(Number),
        from: person('sujin'),
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

  it('다른 턴의 답변은 새 말풍선이 된다', () => {
    // 예전에는 "앞 답변이 끝났는지"로 갈랐다. 이제 프레임이 턴 식별자(msgId)를 들고 오므로
    // 그 값으로 가른다(이슈 #190) — 턴이 겹쳐 도착해도 섞이지 않는다.
    const state = fold([
      answer('첫 답변', 'done'),
      answer('두 번째 답변', 'done', { msgId: 'agent-msg-2' }),
    ]);
    expect(chats(state).map((m) => m.content)).toEqual([
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

    expect(chats(state).map((m) => m.from)).toEqual([null, person('minho')]);
    expect(state.messages[0]).toMatchObject({
      content: '제12조에 따르면 10%입니다.',
      streaming: false,
    });
  });

  it('사람 메시지 뒤에 오면 그 메시지에 섞이지 않는다', () => {
    const state = fold([say('sujin', '@AI 요약해줘'), answer('요약', 'done')]);
    expect(chats(state).map((m) => m.from)).toEqual([person('sujin'), null]);
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

    expect(chats(state)[0].streaming).toBe(true);
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

describe('system.notice(#29)', () => {
  it('warning 알림은 방 위에 남는다', () => {
    const state = fold([
      notice('warning', 'RISKY_CONTENT', '검토가 필요합니다.'),
    ]);
    expect(state.notices).toEqual([
      {
        code: 'RISKY_CONTENT',
        message: '검토가 필요합니다.',
        traceId: 'trace-RISKY_CONTENT',
      },
    ]);
  });

  // 배치가 30초마다 도는 계약(#27)이라 같은 사유가 되풀이해 온다 — 쌓이면 방을 덮는다.
  it('같은 code가 또 오면 쌓이지 않고 그 자리에서 갈아끼운다', () => {
    const state = fold([
      notice('warning', 'RISKY_CONTENT', '첫 번째'),
      notice('warning', 'OTHER', '다른 사유'),
      notice('warning', 'RISKY_CONTENT', '두 번째'),
    ]);
    expect(state.notices.map((item) => item.message)).toEqual([
      '두 번째',
      '다른 사유',
    ]);
  });

  // 토스트로 지나가는 안내라 훅이 띄운다 — 배너 자리를 차지하면 안 된다.
  it('info 알림은 상태에 남지 않는다', () => {
    const state = fold([
      notice('info', 'TOKEN_BUDGET_LOW', '한도가 가깝습니다.'),
    ]);
    expect(state.notices).toEqual([]);
  });

  it('알림을 닫으면 그 code만 사라진다', () => {
    const state = fold([
      notice('warning', 'RISKY_CONTENT', '검토가 필요합니다.'),
      notice('warning', 'OTHER', '다른 사유'),
    ]);
    expect(dismissNotice(state, 'RISKY_CONTENT').notices).toEqual([
      { code: 'OTHER', message: '다른 사유', traceId: 'trace-OTHER' },
    ]);
  });

  it('없는 code를 닫으면 상태를 그대로 둔다', () => {
    const state = fold([
      notice('warning', 'RISKY_CONTENT', '검토가 필요합니다.'),
    ]);
    expect(dismissNotice(state, 'NONE')).toBe(state);
  });

  it('문구가 비어 오면 빈 배너 대신 대체 문구를 남긴다', () => {
    const state = fold([notice('warning', 'RISKY_CONTENT', '')]);
    expect(state.notices[0].message).toBe('확인이 필요한 알림이 도착했습니다.');
  });

  it('알림은 메시지 흐름을 건드리지 않는다', () => {
    const state = fold([
      say('sujin', '안녕하세요'),
      notice('warning', 'RISKY_CONTENT', '검토가 필요합니다.'),
    ]);
    expect(state.messages).toHaveLength(1);
  });
  it('participant.changed는 접속자 상태를 건드리지 않는다', () => {
    // 명단(REST)과 접속자(presence)는 다른 목록이다 — 이 프레임은 시트 재조회 신호일 뿐이다.
    const before = applyFrame(initialRoomState, {
      type: 'presence.snapshot',
      sessionId: 'room-1',
      participants: [{ subject: 'sub-1', displayName: '나' }],
    });

    const after = applyFrame(before, {
      type: 'participant.changed',
      sessionId: 'room-1',
      action: 'INVITE_PENDING',
      subject: 'sub-2',
      displayName: '초대된 사람',
    });

    expect(after).toBe(before);
  });

});
describe('applyHistory - 방 진입 시 과거 대화(#190)', () => {
  const historyItem = (
    id: string,
    seq: number,
    content: string,
    overrides: Partial<CollabMessageItem> = {},
  ): CollabMessageItem => ({
    id,
    seq,
    athKind: 'HUMAN',
    status: 'COMPLETE',
    content,
    authorSubject: 'sujin',
    authorDisplayName: 'sujin 님',
    createdAt: '2026-09-10T01:00:00Z',
    completedAt: '2026-09-10T01:00:00Z',
    ...overrides,
  });

  it('seq 순서로 흐름 앞에 붙인다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('b', 5, '나중'),
      historyItem('a', 1, '먼저'),
    ]);
    expect(chats(state).map((m) => m.content)).toEqual(['먼저', '나중']);
  });

  it('seq가 띄엄띄엄해도 그대로 받는다 — 블록 예약이 남긴 구멍이다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '하나'),
      historyItem('b', 97, '둘'),
    ]);
    expect(chats(state).map((m) => m.seq)).toEqual([0, 97]);
  });

  it('이미 WS로 받은 메시지는 msgId로 걸러 두 번 그리지 않는다', () => {
    const live = fold([say('sujin', '실시간으로 먼저 왔다', 'dup-1')]);
    const state = applyHistory(live, [historyItem('dup-1', 3, '실시간으로 먼저 왔다')]);
    expect(chats(state)).toHaveLength(1);
  });

  it('이력이 실시간보다 앞에 온다', () => {
    const live = fold([say('sujin', '방금 말', 'live-1')]);
    const state = applyHistory(live, [historyItem('old-1', 0, '예전 말')]);
    expect(chats(state).map((m) => m.content)).toEqual(['예전 말', '방금 말']);
  });

  it('AGENT는 보낸 사람 없이 AI 답변으로 남는다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '답변입니다', {
        athKind: 'AGENT',
        authorSubject: null,
        authorDisplayName: null,
      }),
    ]);
    expect(chats(state)[0].from).toBeNull();
  });

  it('본문이 빈 행(PENDING·CANCELLED)은 빈 말풍선을 만들지 않는다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '', { athKind: 'AGENT', status: 'CANCELLED' }),
    ]);
    expect(state.messages).toEqual([]);
  });

  it('SYSTEM은 그리지 않는다 — 사람도 AI도 아닌 줄을 이 화면이 표현하지 못한다', () => {
    const state = applyHistory(initialRoomState, [
      historyItem('a', 0, '위험 표현이 감지되었습니다', { athKind: 'SYSTEM' }),
    ]);
    expect(state.messages).toEqual([]);
  });

  it('입퇴장 줄은 제자리에 남는다 — seq가 없어 정렬 대상이 아니다', () => {
    const live = fold([join('minho')]);
    const state = applyHistory(live, [historyItem('old-1', 0, '예전 말')]);
    expect(state.messages).toHaveLength(2);
    expect(isPresenceNotice(state.messages[1])).toBe(true);
  });
});
