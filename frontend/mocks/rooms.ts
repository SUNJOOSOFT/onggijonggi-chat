/********************************************************
 파일명 : rooms.ts (mocks)
 설 명 : 목업 WS 서버의 순수 로직. UUID 방 경로, 최소 inbound DTO, 방 방송과 AI FIFO를
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
 * 방 접근 거부를 재현하는 threadId. 실서버가 인가 실패를 **핸드셰이크 거부**로 줄지
 * **error 프레임**으로 줄지는 이슈 #22 완료 기준에 미정 항목으로 남아 있다 — 화면(#19) 처리가
 * 완전히 갈리는 분기라, 목업은 두 방식을 모두 재현해 양쪽 UI를 다 시험해볼 수 있게 한다.
 */
export const FORBIDDEN_HANDSHAKE_THREAD_ID =
  '44444444-4444-4444-8444-444444444444';
export const FORBIDDEN_FRAME_THREAD_ID = '55555555-5555-4555-8555-555555555555';

/**
 * 백엔드 `CollabWebSocketHandler`는 threadId를 `UUID.fromString`으로 그대로 파싱한다. 이
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

export type RoomAccess = 'allow' | 'deny-handshake' | 'deny-frame';

/** 위 두 예약 threadId 외에는 전부 허용한다(목업엔 참여자 테이블이 없다). */
export function roomAccess(threadId: string): RoomAccess {
  if (threadId === FORBIDDEN_HANDSHAKE_THREAD_ID) return 'deny-handshake';
  if (threadId === FORBIDDEN_FRAME_THREAD_ID) return 'deny-frame';
  return 'allow';
}

export type WsPathResult =
  | { kind: 'valid'; threadId: string }
  | { kind: 'invalid-thread'; threadId: string };

/** 실서버처럼 `/api/ws/{threadId}` 한 세그먼트만 받고 UUID 형식은 별도로 판정한다. */
export function parseWsPath(pathname: string): WsPathResult | null {
  const match = /^\/api\/ws\/([^/]+)$/.exec(pathname);
  if (!match) return null;
  try {
    const threadId = decodeURIComponent(match[1]);
    return isJavaUuid(threadId)
      ? { kind: 'valid', threadId }
      : { kind: 'invalid-thread', threadId };
  } catch {
    return null;
  }
}

export type InboundChatMessage = {
  type: 'chat.message';
  content: string;
};

export type InboundParseResult =
  | { kind: 'message'; message: InboundChatMessage }
  | { kind: 'ignore' }
  | { kind: 'malformed' };

/** 서버 전용 타입은 무시하고, 그 밖에는 최소 chat.message DTO만 허용한다. */
export function parseInboundMessage(raw: string): InboundParseResult {
  try {
    const value = JSON.parse(raw) as Record<string, unknown>;
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { kind: 'malformed' };
    }
    if (
      [
        'chat.answer',
        'presence.join',
        'presence.leave',
        'presence.snapshot',
        'error',
      ].includes(String(value.type))
    ) {
      return { kind: 'ignore' };
    }
    if (
      value.type !== 'chat.message' ||
      typeof value.content !== 'string' ||
      value.content.trim() === ''
    ) {
      return { kind: 'malformed' };
    }
    return {
      kind: 'message',
      message: { type: 'chat.message', content: value.content },
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
  sessionId: string,
  code: string,
  message: string,
  traceId: string,
): WsErrorFrame {
  return { type: 'error', sessionId, code, message, traceId };
}

/** 목업 전용 seq 카운터. 실서버는 방마다 블록으로 예약하지만(이슈 #190), 목업은 순서만
 * 맞으면 되므로 단조 증가만 보장한다. */
let mockSeq = 0;

export function nextMockSeq(): number {
  return mockSeq++;
}

export function answerFrame(
  sessionId: string,
  msgId: string,
  seq: number,
  delta: string,
  status: 'streaming' | 'done',
  citations: Citation[] = [],
  restrictedResultsOmitted = false,
): ChatAnswerFrame {
  return {
    type: 'chat.answer',
    sessionId,
    msgId,
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
      answerFrame(threadId, answerMsgId, answerSeq, '', 'streaming', [citation]),
    );
  }

  for (const [index, token] of tokens.entries()) {
    frames.push(
      answerFrame(threadId, answerMsgId, answerSeq, token, 'streaming'),
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

  frames.push(answerFrame(threadId, answerMsgId, answerSeq, '', 'done'));
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
}

interface QueueState {
  active: boolean;
  pending: MockAiJob[];
}

/** 방마다 AI 작업 하나만 실행하고 나머지는 기본 20개까지 FIFO로 보관한다. */
export class MockAiQueue {
  private readonly rooms = new Map<string, QueueState>();

  constructor(
    private readonly run: (job: MockAiJob) => Promise<void>,
    private readonly maxPending = 20,
  ) {}

  enqueue(job: MockAiJob): boolean {
    const key = this.key(job.threadId, job.generation);
    const state = this.rooms.get(key) ?? {
      active: false,
      pending: [],
    };
    this.rooms.set(key, state);
    if (state.active) {
      if (state.pending.length >= this.maxPending) return false;
      state.pending.push(job);
      return true;
    }
    state.active = true;
    this.start(job, state);
    return true;
  }

  closeRoom(threadId: string, generation: string): void {
    this.rooms.delete(this.key(threadId, generation));
  }

  private start(job: MockAiJob, state: QueueState): void {
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
