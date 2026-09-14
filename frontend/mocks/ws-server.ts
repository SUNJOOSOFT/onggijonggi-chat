/********************************************************
 파일명 : ws-server.ts (mocks)
 설 명 : #16·#17 계약을 재현하는 협업채팅 목업 WS 서버. `bun run mock:ws`로 실행한다.
 방 없는 단일 경로에 붙어 room.subscribe로 방을 거는 구조(이슈 #161)와 최소 inbound DTO를 받고,
 사람 메시지 self-echo와 방별 AI FIFO를 제공한다.
 *********************************************************/

import type { Citation } from '@/lib/api/chat';
import type { WsFrame } from '@/lib/transport/frames';
import {
  PROTOCOL_NAME,
  bearerFromSubProtocol,
  displayNameFromToken,
  subjectFromToken,
} from './handshake';
import {
  aiPrompt,
  aiTurnFrames,
  errorFrame,
  isWsPath,
  MockAiQueue,
  type MockAiJob,
  MockRoomRegistry,
  parseInboundMessage,
  roomAccess,
  type RoomMember,
  scenarioForRoom,
  nextMockSeq,
} from './rooms';

const PORT = Number(process.env.MOCK_WS_PORT ?? 4001);
const TOKEN_INTERVAL_MS = 40;
const SAMPLE_CITATION: Citation = {
  docId: 'mock-doc-1',
  title: '목업 근거 문서',
  snippet: '협업방 citations UI를 확인하기 위한 목업 발췌입니다.',
  score: 0.9,
};

const registry = new MockRoomRegistry();
let connectionSequence = 0;
let turnSequence = 0;

interface SocketData {
  connectionId: string;
  subject: string;
  displayName: string;
  /** 이 커넥션이 구독한 방 → 구독할 때의 방 세대. 실서버 RoomSessionRegistry의 역방향 인덱스 자리다. */
  rooms: Map<string, string>;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function broadcast(job: MockAiJob, frame: WsFrame): boolean {
  return registry.broadcastIfCurrent(job.threadId, job.generation, frame);
}

/** 같은 사람의 탭이 여럿이어도 명단에는 한 번만 — 실서버 RoomState.participants와 같은 규칙. */
function distinctParticipants(
  members: RoomMember[],
): { subject: string; displayName: string }[] {
  const bySubject = new Map<string, { subject: string; displayName: string }>();
  for (const member of members) {
    if (!bySubject.has(member.subject)) {
      bySubject.set(member.subject, {
        subject: member.subject,
        displayName: member.displayName,
      });
    }
  }
  return [...bySubject.values()];
}

/**
 * presence 프레임을 정해진 상대에게만 보낸다. registry.broadcastIfCurrent를 쓰지 않는 이유는
 * 그쪽이 방 전원에게 보내기 때문이다 — 실서버는 입퇴장을 당사자에게 보내지 않는다
 * (RoomSessionRegistry). 목업이 계약과 어긋나면 화면 검증이 거짓 통과한다(#33·#19).
 */
function sendPresence(
  targets: RoomMember[],
  type: 'presence.join' | 'presence.leave',
  threadId: string,
  subject: string,
  displayName: string,
): void {
  const text = JSON.stringify({
    type,
    threadId: threadId,
    subject,
    displayName,
  });
  for (const target of targets) target.send(text);
}

/** 어떤 프레임을 보낼지는 `aiTurnFrames`(순수 함수, rooms.ts)가 정하고, 여기서는 실제
 * 소켓 방송과 프레임 사이 sleep만 맡는다. 마지막 프레임 뒤에는 sleep하지 않는다. */
async function streamAiAnswer(job: MockAiJob): Promise<void> {
  const scenario = scenarioForRoom(job.threadId);
  const frames = aiTurnFrames(
    job.threadId,
    job.traceId,
    job.prompt,
    scenario,
    SAMPLE_CITATION,
    job.turnId,
  );

  for (let i = 0; i < frames.length; i++) {
    // 부른 사람이 멈췄으면 실서버처럼 빈 done 한 장으로 스트림을 닫는다(이슈 #160).
    if (job.cancelled) {
      const last = frames.at(-1);
      if (last?.type === 'chat.answer') {
        broadcast(job, { ...last, delta: '', citations: [], status: 'done' });
      }
      return;
    }
    if (!broadcast(job, frames[i])) return;
    if (i < frames.length - 1) await sleep(TOKEN_INTERVAL_MS);
  }
}

function queuedFrame(job: MockAiJob, status: 'queued' | 'cancelled'): WsFrame {
  return {
    type: 'chat.queued',
    threadId: job.threadId,
    turnId: job.turnId,
    status,
  };
}

/**
 * 위험 질문 사후 검증 배치(#28)를 흉내 낸다. 실서버는 30초마다 최근 대화를 스캔하지만 목업은
 * 화면 검증이 목적이라, 트리거 낱말이 섞인 메시지에 대해서만 잠깐 뒤에 알림을 방송한다 —
 * "말한 직후가 아니라 조금 지나서 도착한다"는 사후 검증의 성질만 남긴 것이다.
 *
 * `위험`이 들어가면 배너(warning), `@notice`가 들어가면 토스트(info)를 확인할 수 있다.
 */
const NOTICE_DELAY_MS = 1_500;

function scheduleMockNotice(
  threadId: string,
  generation: string,
  content: string,
): void {
  let frame: WsFrame | null = null;
  if (content.includes('위험')) {
    frame = {
      type: 'system.notice',
      threadId: threadId,
      severity: 'warning',
      code: 'RISKY_CONTENT',
      message: '이 방의 최근 질문 중 검토가 필요한 내용이 감지되었습니다.',
      traceId: `mock-notice-${++turnSequence}`,
    };
  } else if (content.includes('@notice')) {
    frame = {
      type: 'system.notice',
      threadId: threadId,
      severity: 'info',
      code: 'TOKEN_BUDGET_LOW',
      message: '이번 달 토큰 사용량이 한도에 가까워지고 있습니다.',
      traceId: `mock-notice-${++turnSequence}`,
    };
  }
  if (frame === null) return;
  const notice = frame;
  setTimeout(
    () => registry.broadcastIfCurrent(threadId, generation, notice),
    NOTICE_DELAY_MS,
  );
}

const aiQueue = new MockAiQueue(streamAiAnswer);

/** 이 커넥션을 방에서 뺀다. 방이 비면 그 방 AI 대기열을 닫고, 그 사람의 마지막 연결이면 퇴장을 알린다. */
function leaveRoom(data: SocketData, threadId: string): void {
  const generation = data.rooms.get(threadId);
  if (generation === undefined) return;
  data.rooms.delete(threadId);
  if (registry.leave(threadId, data.connectionId, generation)) {
    aiQueue.closeRoom(threadId, generation);
  }
  // 방이 비었으면 remaining이 빈 배열이라 아무 데도 나가지 않는다 — 실서버도 그렇다.
  const remaining = registry.membersOf(threadId);
  if (!remaining.some((other) => other.subject === data.subject)) {
    sendPresence(
      remaining,
      'presence.leave',
      threadId,
      data.subject,
      data.displayName,
    );
  }
  console.log(`[mock-ws] leave ${data.subject} ← ${threadId}`);
}

const server = Bun.serve<SocketData>({
  port: PORT,
  fetch(request, server) {
    const url = new URL(request.url);
    if (!isWsPath(url.pathname)) {
      return new Response('Not Found', { status: 404 });
    }

    const token = bearerFromSubProtocol(
      request.headers.get('Sec-WebSocket-Protocol'),
    );
    if (token === null) return new Response('Unauthorized', { status: 401 });

    connectionSequence += 1;
    const data: SocketData = {
      connectionId: `c${connectionSequence}`,
      subject:
        url.searchParams.get('user') ??
        subjectFromToken(token) ??
        `user-${connectionSequence}`,
      displayName:
        url.searchParams.get('name') ??
        displayNameFromToken(token) ??
        `사용자 ${connectionSequence}`,
      rooms: new Map(),
    };

    const upgraded = server.upgrade(request, {
      data,
      headers: { 'Sec-WebSocket-Protocol': PROTOCOL_NAME },
    });
    return upgraded
      ? undefined
      : new Response('Upgrade failed', { status: 400 });
  },

  websocket: {
    open(ws) {
      console.log(`[mock-ws] connect ${ws.data.subject}`);
    },

    message(ws, raw) {
      const { connectionId, subject, displayName, rooms } = ws.data;
      const reply = (frame: WsFrame) => ws.send(JSON.stringify(frame));

      const parsed = parseInboundMessage(String(raw));
      if (parsed.kind === 'ignore') return;
      if (parsed.kind === 'ping') {
        reply({ type: 'pong' });
        return;
      }
      if (parsed.kind === 'malformed') {
        reply(
          errorFrame(
            null,
            'MALFORMED_REQUEST',
            'WebSocket 프레임 형식이 올바르지 않습니다.',
            `mock-malformed-${++turnSequence}`,
          ),
        );
        return;
      }

      if (parsed.kind === 'subscribe') {
        const { threadId } = parsed;
        if (rooms.has(threadId)) return;
        if (roomAccess(threadId) === 'deny') {
          // 실서버처럼 그 방만 거부하고 커넥션은 유지한다.
          reply(
            errorFrame(
              threadId,
              'FORBIDDEN',
              '이 방에 들어갈 권한이 없습니다.',
              'mock-forbidden',
            ),
          );
          return;
        }
        const member: RoomMember = {
          id: connectionId,
          subject,
          displayName,
          send: (text) => ws.send(text),
        };
        // 입장을 알릴 상대는 "들어가기 전에 이미 있던 사람"이라 join 전에 찍어둔다.
        const others = registry.membersOf(threadId);
        rooms.set(threadId, registry.join(threadId, member));
        // 스냅샷은 이 구독에게만, 본인을 포함해서 보낸다(#26). 클라이언트는 이것을 구독 완료로 읽는다.
        member.send(
          JSON.stringify({
            type: 'presence.snapshot',
            threadId: threadId,
            participants: distinctParticipants(registry.membersOf(threadId)),
          }),
        );
        // 그 사용자의 첫 연결일 때만 입장이다 — 탭을 더 여는 것은 입장이 아니다.
        if (!others.some((other) => other.subject === subject)) {
          sendPresence(others, 'presence.join', threadId, subject, displayName);
        }
        console.log(`[mock-ws] join ${subject} → ${threadId}`);
        return;
      }
      if (parsed.kind === 'unsubscribe') {
        leaveRoom(ws.data, parsed.threadId);
        return;
      }

      if (parsed.kind === 'cancel') {
        // 멈출 턴이 없거나 다른 커넥션의 턴이면 실서버처럼 아무것도 보내지 않는다. 실행 중인
        // 턴은 streamAiAnswer 루프가 done으로 닫는다. 구독하지 않은 방이면 찾을 턴이 없다.
        const generation = rooms.get(parsed.threadId);
        if (generation === undefined) return;
        const result = aiQueue.cancel(
          parsed.threadId,
          generation,
          parsed.turnId,
          connectionId,
        );
        if (result.kind === 'pending') {
          broadcast(result.job, queuedFrame(result.job, 'cancelled'));
        }
        return;
      }

      const { threadId, clientMsgId, turnId, content } = parsed.message;
      const generation = rooms.get(threadId);
      if (generation === undefined) {
        reply(
          errorFrame(
            threadId,
            'NOT_SUBSCRIBED',
            '이 방을 구독하고 있지 않습니다.',
            `mock-not-subscribed-${++turnSequence}`,
          ),
        );
        return;
      }
      registry.broadcastIfCurrent(threadId, generation, {
        type: 'chat.message',
        threadId: threadId,
        msgId: crypto.randomUUID(),
        clientMsgId,
        turnId,
        seq: nextMockSeq(),
        from: subject,
        fromDisplayName: displayName,
        content,
      });

      scheduleMockNotice(threadId, generation, content);

      const prompt = aiPrompt(content);
      if (prompt === null) return;
      const traceId = `mock-turn-${++turnSequence}`;
      if (prompt === '') {
        reply(
          errorFrame(
            threadId,
            'MALFORMED_REQUEST',
            '@AI 뒤에 요청 내용을 입력해 주세요.',
            traceId,
          ),
        );
        return;
      }

      const job: MockAiJob = {
        threadId,
        generation,
        prompt,
        traceId,
        turnId,
        connectionId,
      };
      const enqueued = aiQueue.enqueue(job);
      if (enqueued === 'queued') {
        broadcast(job, queuedFrame(job, 'queued'));
      } else if (enqueued === 'rejected') {
        reply(
          errorFrame(
            threadId,
            'RATE_LIMITED',
            '이 방의 AI 요청 대기열이 가득 찼습니다.',
            traceId,
          ),
        );
      }
    },

    close(ws) {
      // 끊긴 커넥션은 구독한 방 전부에서 뺀다 — 실서버 RoomSessionRegistry.leaveAll과 같다.
      for (const threadId of [...ws.data.rooms.keys()]) {
        leaveRoom(ws.data, threadId);
      }
      console.log(`[mock-ws] disconnect ${ws.data.subject}`);
    },
  },
});

console.log(`[mock-ws] ws://localhost:${server.port}/api/ws 준비 완료`);
