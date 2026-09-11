/********************************************************
 파일명 : mock-frame-source.ts (lib/transport)
 설 명 : 실제 전송 계층(WebSocket, 이슈 #2)이 아직 없는 상태에서 frame-stream-fetch.ts를
 검증하기 위한 목업 프레임 소스. WS의 message 이벤트나 NDJSON 한 줄이나 결국 "프레임 하나 =
 문자열 하나"로 도착한다는 점만 계약으로 삼는다 — 그래서 AsyncIterable<string>이 소스의
 전체 인터페이스다. 실제 전송 계층이 붙을 때도 이 인터페이스만 만족하면 소비 코드는 안 바뀐다.
 *********************************************************/

import type { Citation } from '@/lib/api/chat';
import type { ChatAnswerFrame, WsFrame } from './frames';

/** WsFrame 배열을 JSON 문자열 스트림으로 내보낸다. 반환 타입(AsyncGenerator<string>)은
 * frame-stream-fetch.ts가 선언한 FrameSource(AsyncIterable<string>)에 구조적으로 맞는다 —
 * 이 파일은 그 타입을 몰라도 된다. 매 프레임 사이에 마이크로태스크 하나만큼
 * 양보한다 — 실벽시계 지연 없이도(테스트가 느려지지 않게) "한 번에 다 오지 않고 순차적으로
 * 도착한다"는 진짜 비동기 스트림의 성질은 유지한다. */
export async function* mockFrameSource(
  frames: readonly WsFrame[],
): AsyncGenerator<string> {
  for (const frame of frames) {
    await Promise.resolve();
    yield JSON.stringify(frame);
  }
}

function answerFrame(
  sessionId: string,
  msgId: string,
  seq: number,
  partial: Partial<
    Pick<
      ChatAnswerFrame,
      'delta' | 'citations' | 'restrictedResultsOmitted' | 'status'
    >
  >,
): ChatAnswerFrame {
  return {
    type: 'chat.answer',
    sessionId,
    msgId,
    seq,
    delta: partial.delta ?? '',
    citations: partial.citations ?? [],
    restrictedResultsOmitted: partial.restrictedResultsOmitted ?? false,
    status: partial.status ?? 'streaming',
  };
}

/** 근거가 먼저(delta 없이) 도착하고, 토큰이 이어서 흐르고, 마지막에 status:'done'으로 끝나는
 * 정상 흐름. citation이 done을 기다리지 않는다는 걸 보여주는 것이 이 시나리오의 핵심 —
 * ChatAnswerFrame의 citations를 delta보다 먼저 채워 보낼 수 있게 한 이유(이슈 #10 코멘트,
 * bsjSunjoo 확정 스펙)를 그대로 재현한다. */
export function goldenPathFrames(params?: {
  sessionId?: string;
  tokens?: string[];
  citations?: Citation[];
  restrictedResultsOmitted?: boolean;
}): WsFrame[] {
  const sessionId = params?.sessionId ?? 's1';
  // 한 턴의 패킷은 모두 같은 msgId·seq를 단다 — 실서버 계약과 같다(이슈 #190).
  const answerMsgId = crypto.randomUUID();
  const answerSeq = 0;
  const tokens = params?.tokens ?? ['안녕', '하세요'];
  const citations = params?.citations ?? [
    { docId: 'd1', title: '문서 제목', snippet: '발췌 내용', score: 0.87 },
  ];
  return [
    answerFrame(sessionId, answerMsgId, answerSeq, {
      citations,
      restrictedResultsOmitted: params?.restrictedResultsOmitted ?? false,
      status: 'streaming',
    }),
    ...tokens.map((delta) =>
      answerFrame(sessionId, answerMsgId, answerSeq, { delta, status: 'streaming' }),
    ),
    answerFrame(sessionId, answerMsgId, answerSeq, { status: 'done' }),
  ];
}

/** 근거가 전부 RBAC로 걸러진 흐름 — citations는 빈 배열이지만 restrictedResultsOmitted는
 * true다. 이 둘이 서로 독립이라는 걸 보여주는 시나리오(PR #50 리뷰, bsjSunjoo). */
export function restrictedCitationsFrames(params?: {
  sessionId?: string;
  tokens?: string[];
}): WsFrame[] {
  const sessionId = params?.sessionId ?? 's1';
  // 한 턴의 패킷은 모두 같은 msgId·seq를 단다 — 실서버 계약과 같다(이슈 #190).
  const answerMsgId = crypto.randomUUID();
  const answerSeq = 0;
  const tokens = params?.tokens ?? ['안녕', '하세요'];
  return [
    answerFrame(sessionId, answerMsgId, answerSeq, {
      citations: [],
      restrictedResultsOmitted: true,
      status: 'streaming',
    }),
    ...tokens.map((delta) =>
      answerFrame(sessionId, answerMsgId, answerSeq, { delta, status: 'streaming' }),
    ),
    answerFrame(sessionId, answerMsgId, answerSeq, { status: 'done' }),
  ];
}

/** 근거 없이 답변만 오는 흐름 — RAG가 근거를 못 찾은 경우 등. 모든 패킷의 citations가 빈 배열이다. */
export function tokensOnlyFrames(params?: {
  sessionId?: string;
  tokens?: string[];
}): WsFrame[] {
  const sessionId = params?.sessionId ?? 's1';
  // 한 턴의 패킷은 모두 같은 msgId·seq를 단다 — 실서버 계약과 같다(이슈 #190).
  const answerMsgId = crypto.randomUUID();
  const answerSeq = 0;
  const tokens = params?.tokens ?? ['안녕', '하세요'];
  return [
    ...tokens.map((delta) =>
      answerFrame(sessionId, answerMsgId, answerSeq, { delta, status: 'streaming' }),
    ),
    answerFrame(sessionId, answerMsgId, answerSeq, { status: 'done' }),
  ];
}

/** 토큰이 일부 흐르다가 스트림 중간에 error 프레임으로 끊기는 흐름. status:'done' 패킷 없이
 * 종료된다 — 소비자가 error를 받으면 스트림을 닫아야 한다는 걸 검증하는 시나리오. */
export function errorMidStreamFrames(params?: {
  sessionId?: string;
  tokensBeforeError?: string[];
  code?: string;
  message?: string;
  traceId?: string;
}): WsFrame[] {
  const sessionId = params?.sessionId ?? 's1';
  // 한 턴의 패킷은 모두 같은 msgId·seq를 단다 — 실서버 계약과 같다(이슈 #190).
  const answerMsgId = crypto.randomUUID();
  const answerSeq = 0;
  const tokensBeforeError = params?.tokensBeforeError ?? ['안녕'];
  return [
    ...tokensBeforeError.map((delta) =>
      answerFrame(sessionId, answerMsgId, answerSeq, { delta, status: 'streaming' }),
    ),
    {
      type: 'error',
      sessionId,
      code: params?.code ?? 'MODEL_UNAVAILABLE',
      message: params?.message ?? '모델을 호출할 수 없습니다.',
      traceId: params?.traceId ?? 't1',
    },
  ];
}
