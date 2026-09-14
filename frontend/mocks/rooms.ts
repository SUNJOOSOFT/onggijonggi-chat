/********************************************************
 파일명 : rooms.ts (mocks)
 설 명 : 목업 WS 서버의 순수 로직. 단일 경로, 최소 inbound DTO, 방 방송과 AI FIFO를
 소켓·Bun 런타임에서 분리해 vitest로 검증한다.
 *********************************************************/

import type { Citation } from '@/lib/api/chat';
import { mentionsAi } from '@/lib/collab/mention';
import type {
  ChatAnswerFrame,
  WsErrorFrame,
  WsFrame,
} from '@/lib/transport/frames';

export { mentionsAi };

export const NORMAL_THREAD_ID = '11111111-1111-4111-8111-111111111111';
export const ERROR_BEFORE_THREAD_ID = '22222222-2222-4222-8222-222222222222';
export const ERROR_MID_THREAD_ID = '33333333-3333-4333-8333-333333333333';

/**
 * 방 접근 거부를 재현하는 threadId. 구독하면 실서버처럼 그 방 threadId로 FORBIDDEN이 오고 커넥션은
 * 유지된다. 핸드셰이크에는 방이 없어(이슈 #161) 방 단위 핸드셰이크 거부는 재현할 대상이 아니다.
 */
export const FORBIDDEN_FRAME_THREAD_ID = '55555555-5555-4555-8555-555555555555';

/**
 * 백엔드는 프레임의 threadId를 Jackson이 `UUID.fromString`으로 그대로 파싱한다. 이
 * 파서는 `-`로 나눈 조각을 각각 `Long.parseLong(part, 16)`으로만 읽어 조각별 자릿수를 강제하지
 * 않는다(표준 8-4-4-4-12보다 짧거나 길어도 통과한다). 목업이 RFC4122 정확한 자릿수만 받으면
 * 실서버는 받는 threadId를 목업만 튕겨내는 불일치가 생기므로, 여기서는 자릿수를 강제하지 않고
 * "16진수 조각 5개"만 확인한다.
 */
// 비표준 자릿수도 수치값이 Java Long.MAX_VALUE 안에 있을 때만 허용한다.
const JAVA_LONG_MAX_HEX = 0x7fff_ffff_ffff_ffffn;
const AI_MENTION_PATTERN = /(?<![\p{L}\p{N}_])@ai(?![A-Za-z0-9_])/giu;

function isJavaUuidPart(part: string): boolean {
  const match = /^\+?([0-9a-f]+)$/i.exec(part);
  return match !== null && BigInt(`0x${match[1]}`) <= JAVA_LONG_MAX_HEX;
}

function isJavaUuid(threadId: string): boolean {
  const parts = threadId.split('-');
  return parts.length === 5 && parts.every(isJavaUuidPart);
}

export type MockRoomScenario = 'normal' | 'error-before' | 'error-mid';

export function scenarioForRoom(threadId: string): MockRoomScenario {
  if (threadId === ERROR_BEFORE_THREAD_ID) return 'error-before';
  if (threadId === ERROR_MID_THREAD_ID) return 'error-mid';
  return 'normal';
}

export type RoomAccess = 'allow' | 'deny';

/** 위 예약 threadId 외에는 전부 허용한다(목업엔 참여자 테이블이 없다). */
export function roomAccess(threadId: string): RoomAccess {
  return threadId === FORBIDDEN_FRAME_THREAD_ID ? 'deny' : 'allow';
}

/** 실서버처럼 방 없는 `/api/ws` 하나만 받는다(이슈 #161). */
export function isWsPath(pathname: string): boolean {
  return pathname === '/api/ws';
}

export type InboundChatMessage = {
  type: 'chat.message';
  /** 말할 방(이슈 #161). 이 커넥션이 구독한 방이어야 한다. */
  threadId: string;
  content: string;
  /** 클라이언트가 만든 임시 메시지 id(이슈 #160). 에코에 그대로 돌려준다. */
  clientMsgId: string | null;
  /** 클라이언트가 만든 턴 식별자(이슈 #160). 에코·답변·대기 프레임에 돌려주고 취소 지목에 쓴다. */
  turnId: string | null;
};

export type InboundParseResult =
  | { kind: 'message'; message: InboundChatMessage }
  | { kind: 'cancel'; threadId: string; turnId: string }
  | { kind: 'ping' }
  | { kind: 'subscribe'; threadId: string }
  | { kind: 'unsubscribe'; threadId: string }
  | { kind: 'ignore' }
  | { kind: 'malformed' };

/** 실서버 CollabWebSocketHandler.SERVER_ONLY_TYPES와 같은 목록 — 올려보내도 조용히 무시한다. */
const SERVER_ONLY_TYPES = [
  'chat.answer',
  'presence.join',
  'presence.leave',
  'presence.snapshot',
  'error',
  'system.notice',
  'chat.queued',
  'pong',
];

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}

/** 실서버는 UUID로 못 읽는 threadId를 역직렬화 단계에서 형식 오류로 거른다. */
function threadIdValue(value: unknown): value is string {
  return nonEmptyString(value) && isJavaUuid(value);
}

/** 서버 전용 타입은 무시하고, 그 밖에는 클라이언트가 올려보낼 수 있는 타입(InboundFrame.java)만 허용한다. */
export function parseInboundMessage(raw: string): InboundParseResult {
  try {
    const value = JSON.parse(raw) as Record<string, unknown>;
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { kind: 'malformed' };
    }
    if (SERVER_ONLY_TYPES.includes(String(value.type))) {
      return { kind: 'ignore' };
    }
    if (value.type === 'ping') return { kind: 'ping' };
    if (value.type === 'chat.cancel') {
      return threadIdValue(value.threadId) && nonEmptyString(value.turnId)
        ? { kind: 'cancel', threadId: value.threadId, turnId: value.turnId }
        : { kind: 'malformed' };
    }
    if (value.type === 'room.subscribe' || value.type === 'room.unsubscribe') {
      if (!threadIdValue(value.threadId)) return { kind: 'malformed' };
      return value.type === 'room.subscribe'
        ? { kind: 'subscribe', threadId: value.threadId }
        : { kind: 'unsubscribe', threadId: value.threadId };
    }
    if (
      value.type !== 'chat.message' ||
      !threadIdValue(value.threadId) ||
      typeof value.content !== 'string' ||
      value.content.trim() === ''
    ) {
      return { kind: 'malformed' };
    }
    // model은 목업이 해석하지 않는다 — 모델이 하나뿐이다. 실어 보내도 malformed가 되지 않게 흘린다.
    return {
      kind: 'message',
      message: {
        type: 'chat.message',
        threadId: value.threadId,
        content: value.content,
        clientMsgId: nonEmptyString(value.clientMsgId)
          ? value.clientMsgId
          : null,
        turnId: nonEmptyString(value.turnId) ? value.turnId : null,
      },
    };
  } catch {
    return { kind: 'malformed' };
  }
}

export function aiPrompt(content: string): string | null {
  AI_MENTION_PATTERN.lastIndex = 0;
  if (!AI_MENTION_PATTERN.test(content)) return null;
  AI_MENTION_PATTERN.lastIndex = 0;
  return content.replace(AI_MENTION_PATTERN, '').trim();
}

export function errorFrame(
  threadId: string | null,
  code: string,
  message: string,
  traceId: string,
): WsErrorFrame {
  return { type: 'error', threadId, code, message, traceId };
}

/** 목업 전용 seq 카운터. 실서버는 방마다 블록으로 예약하지만(이슈 #190), 목업은 순서만
 * 맞으면 되므로 단조 증가만 보장한다. */
let mockSeq = 0;

export function nextMockSeq(): number {
  return mockSeq++;
}

/** 목업은 모델이 하나뿐이라 답변 프레임의 model을 이 값으로 채운다. */
export const MOCK_MODEL = 'mock-model';

export function answerFrame(
  threadId: string,
  msgId: string,
  turnId: string | null,
  seq: number,
  delta: string,
  status: 'streaming' | 'done',
  citations: Citation[] = [],
  restrictedResultsOmitted = false,
): ChatAnswerFrame {
  return {
    type: 'chat.answer',
    threadId,
    msgId,
    turnId,
    model: MOCK_MODEL,
    seq,
    delta,
    citations,
    restrictedResultsOmitted,
    status,
  };
}

/**
 * 예약 3방(`scenarioForRoom`)이 실제로 보낼 프레임을 순서대로 만든다. Bun 소켓·타이밍과
 * 분리해 두는 이유는 이 파일의 다른 파싱 함수들과 같다 — vitest(node)에서 `ws-server.ts`를
 * 직접 불러올 수 없어서다. 프레임 사이 sleep은 호출부(`ws-server.ts`) 책임이다.
 */
export function aiTurnFrames(
  threadId: string,
  traceId: string,
  prompt: string,
  scenario: MockRoomScenario,
  citation: Citation,
  turnId: string | null = null,
): WsFrame[] {
  if (scenario === 'error-before') {
    return [
      errorFrame(
        threadId,
        'MODEL_UNAVAILABLE',
        '목업 모델을 호출할 수 없습니다.',
        traceId,
      ),
    ];
  }

  const reply = `「목업 응답」 ${prompt}`;
  const tokens = reply.split(/(\s+)/).filter((chunk) => chunk.length > 0);
  const frames: WsFrame[] = [];
  // 한 턴의 모든 패킷은 같은 msgId·seq를 단다 — 실서버 계약과 같다(이슈 #190).
  const answerMsgId = crypto.randomUUID();
  const answerSeq = nextMockSeq();

  if (scenario === 'normal') {
    frames.push(
      answerFrame(threadId, answerMsgId, turnId, answerSeq, '', 'streaming', [
        citation,
      ]),
    );
  }

  for (const [index, token] of tokens.entries()) {
    frames.push(
      answerFrame(threadId, answerMsgId, turnId, answerSeq, token, 'streaming'),
    );
    if (scenario === 'error-mid' && index === 0) {
      frames.push(
        errorFrame(
          threadId,
          'MODEL_UNAVAILABLE',
          '목업 스트리밍이 도중에 중단됐습니다.',
          traceId,
        ),
      );
      return frames;
    }
  }

  frames.push(
    answerFrame(threadId, answerMsgId, turnId, answerSeq, '', 'done'),
  );
  return frames;
}

/** 방에 붙어 있는 커넥션 하나. 같은 사용자의 탭 두 개도 서로 다른 연결이다. */
export interface RoomMember {
  id: string;
  subject: string;
  displayName: string;
  send: (data: string) => void;
}

/** 목업도 실서버와 같은 단일 프로세스 in-memory 방 레지스트리를 사용한다. */
export class MockRoomRegistry {
  private readonly rooms = new Map<
    string,
    { generation: string; members: Map<string, RoomMember> }
  >();
  private generationSequence = 0;

  join(threadId: string, member: RoomMember): string {
    const room = this.rooms.get(threadId) ?? {
      generation: `g${++this.generationSequence}`,
      members: new Map<string, RoomMember>(),
    };
    room.members.set(member.id, member);
    this.rooms.set(threadId, room);
    return room.generation;
  }

  leave(threadId: string, memberId: string, generation: string): boolean {
    const room = this.rooms.get(threadId);
    if (!room || room.generation !== generation) return false;
    room.members.delete(memberId);
    if (room.members.size > 0) return false;
    this.rooms.delete(threadId);
    return true;
  }

  membersOf(threadId: string): RoomMember[] {
    return [...(this.rooms.get(threadId)?.members.values() ?? [])];
  }

  broadcastIfCurrent(
    threadId: string,
    generation: string,
    frame: WsFrame,
  ): boolean {
    const room = this.rooms.get(threadId);
    if (!room || room.generation !== generation) return false;
    const text = JSON.stringify(frame);
    for (const member of room.members.values()) member.send(text);
    return true;
  }
}

export interface MockAiJob {
  threadId: string;
  generation: string;
  prompt: string;
  traceId: string;
  /** 취소는 (turnId, 발화가 들어온 커넥션) 짝으로 찾는다 — 실서버 CollabMessageDispatcher.cancel과 같다. */
  turnId: string | null;
  connectionId: string;
  /** 실행 중에 취소됐는지. 스트리밍 루프가 프레임마다 확인한다. */
  cancelled?: boolean;
}

interface QueueState {
  active: MockAiJob | null;
  pending: MockAiJob[];
}

export type EnqueueResult = 'started' | 'queued' | 'rejected';

/** 취소한 결과 — 실행 중이던 턴은 루프가 done으로 닫고, 기다리던 턴은 호출부가 chat.queued로 알린다. */
export type CancelResult =
  | { kind: 'active'; job: MockAiJob }
  | { kind: 'pending'; job: MockAiJob }
  | { kind: 'none' };

/** 방마다 AI 작업 하나만 실행하고 나머지는 기본 20개까지 FIFO로 보관한다. */
export class MockAiQueue {
  private readonly rooms = new Map<string, QueueState>();

  constructor(
    private readonly run: (job: MockAiJob) => Promise<void>,
    private readonly maxPending = 20,
  ) {}

  enqueue(job: MockAiJob): EnqueueResult {
    const key = this.key(job.threadId, job.generation);
    const state = this.rooms.get(key) ?? { active: null, pending: [] };
    this.rooms.set(key, state);
    if (state.active !== null) {
      if (state.pending.length >= this.maxPending) return 'rejected';
      state.pending.push(job);
      return 'queued';
    }
    this.start(job, state);
    return 'started';
  }

  cancel(
    threadId: string,
    generation: string,
    turnId: string,
    connectionId: string,
  ): CancelResult {
    const state = this.rooms.get(this.key(threadId, generation));
    if (!state) return { kind: 'none' };
    const matches = (job: MockAiJob) =>
      job.turnId === turnId && job.connectionId === connectionId;
    if (state.active !== null && matches(state.active)) {
      state.active.cancelled = true;
      return { kind: 'active', job: state.active };
    }
    const index = state.pending.findIndex(matches);
    if (index === -1) return { kind: 'none' };
    const [job] = state.pending.splice(index, 1);
    return { kind: 'pending', job };
  }

  closeRoom(threadId: string, generation: string): void {
    this.rooms.delete(this.key(threadId, generation));
  }

  private start(job: MockAiJob, state: QueueState): void {
    state.active = job;
    void this.run(job).finally(() => {
      const key = this.key(job.threadId, job.generation);
      if (this.rooms.get(key) !== state) return;
      const next = state.pending.shift();
      if (next) {
        this.start(next, state);
      } else {
        this.rooms.delete(key);
      }
    });
  }

  private key(threadId: string, generation: string): string {
    return `${threadId}:${generation}`;
  }
}
