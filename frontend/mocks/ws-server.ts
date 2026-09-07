/********************************************************
 파일명 : ws-server.ts (mocks)
 설 명 : #16·#17 계약을 재현하는 협업채팅 목업 WS 서버. `bun run mock:ws`로 실행한다.
 정확한 UUID 방 경로와 최소 inbound DTO를 받고, 사람 메시지 self-echo와 방별 AI FIFO를 제공한다.
 *********************************************************/

import type { Citation } from '@/lib/api/chat';
import type { WsFrame } from '@/lib/transport/frames';
import {
  PROTOCOL_NAME,
  bearerFromSubProtocol,
  userIdFromToken,
} from './handshake';
import {
  aiPrompt,
  aiTurnFrames,
  errorFrame,
  MockAiQueue,
  type MockAiJob,
  MockRoomRegistry,
  parseInboundMessage,
  parseWsPath,
  roomAccess,
  type RoomMember,
  scenarioForRoom,
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
  threadId: string;
  userId: string;
  invalidThread: boolean;
  /** true면 open 직후 error 프레임을 보내고 닫는다(방 접근 거부의 "프레임" 방식). */
  denyOnOpen: boolean;
  generation: string | null;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function broadcast(job: MockAiJob, frame: WsFrame): boolean {
  return registry.broadcastIfCurrent(job.threadId, job.generation, frame);
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
  userId: string,
): void {
  const text = JSON.stringify({ type, sessionId: threadId, userId });
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
  );

  for (let i = 0; i < frames.length; i++) {
    if (!broadcast(job, frames[i])) return;
    if (i < frames.length - 1) await sleep(TOKEN_INTERVAL_MS);
  }
}

const aiQueue = new MockAiQueue(streamAiAnswer);

const server = Bun.serve<SocketData>({
  port: PORT,
  fetch(request, server) {
    const url = new URL(request.url);
    const path = parseWsPath(url.pathname);
    if (path === null) return new Response('Not Found', { status: 404 });

    const token = bearerFromSubProtocol(
      request.headers.get('Sec-WebSocket-Protocol'),
    );
    if (token === null) return new Response('Unauthorized', { status: 401 });

    const access = path.kind === 'valid' ? roomAccess(path.threadId) : 'allow';
    // 핸드셰이크 자체를 거부하면 브라우저에는 status도 body도 닿지 않고 1006으로만 온다
    // (PR #68). 화면이 그 상황을 어떻게 다루는지 보려고 남겨 둔 시나리오다.
    if (access === 'deny-handshake') {
      return new Response('Forbidden', { status: 403 });
    }

    connectionSequence += 1;
    const data: SocketData = {
      connectionId: `c${connectionSequence}`,
      threadId: path.threadId,
      userId:
        url.searchParams.get('user') ??
        userIdFromToken(token) ??
        `user-${connectionSequence}`,
      invalidThread: path.kind === 'invalid-thread',
      denyOnOpen: access === 'deny-frame',
      generation: null,
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
      const { connectionId, threadId, userId, invalidThread, denyOnOpen } =
        ws.data;
      if (invalidThread) {
        ws.send(
          JSON.stringify(
            errorFrame(
              threadId,
              'MALFORMED_REQUEST',
              'threadId는 UUID여야 합니다.',
              'mock-invalid-thread',
            ),
          ),
        );
        ws.close(1000, 'invalid threadId');
        return;
      }

      if (denyOnOpen) {
        ws.send(
          JSON.stringify(
            errorFrame(
              threadId,
              'FORBIDDEN',
              '이 방에 들어갈 권한이 없습니다.',
              'mock-forbidden',
            ),
          ),
        );
        // 1000으로 닫는다 — 재연결해도 같은 거부라 ws-connection.ts가 루프를 멈추게 한다.
        ws.close(1000, 'room forbidden');
        return;
      }

      const member: RoomMember = {
        id: connectionId,
        userId,
        send: (text) => ws.send(text),
      };
      // 입장을 알릴 상대는 "들어가기 전에 이미 있던 사람"이라 join 전에 찍어둔다.
      const others = registry.membersOf(threadId);
      ws.data.generation = registry.join(threadId, member);
      // 스냅샷은 이 연결에게만, 본인을 포함해서 보낸다(#26).
      member.send(
        JSON.stringify({
          type: 'presence.snapshot',
          sessionId: threadId,
          participants: [
            ...new Set(registry.membersOf(threadId).map((m) => m.userId)),
          ],
        }),
      );
      // 그 사용자의 첫 연결일 때만 입장이다 — 탭을 더 여는 것은 입장이 아니다.
      if (!others.some((other) => other.userId === userId)) {
        sendPresence(others, 'presence.join', threadId, userId);
      }
      console.log(`[mock-ws] join ${userId} → ${threadId}`);
    },

    message(ws, raw) {
      const { threadId, userId, generation } = ws.data;
      if (generation === null) return;

      const parsed = parseInboundMessage(String(raw));
      if (parsed.kind === 'ignore') return;
      if (parsed.kind === 'malformed') {
        ws.send(
          JSON.stringify(
            errorFrame(
              threadId,
              'MALFORMED_REQUEST',
              'WebSocket 프레임 형식이 올바르지 않습니다.',
              `mock-malformed-${++turnSequence}`,
            ),
          ),
        );
        return;
      }

      registry.broadcastIfCurrent(threadId, generation, {
        type: 'chat.message',
        sessionId: threadId,
        from: userId,
        content: parsed.message.content,
      });

      const prompt = aiPrompt(parsed.message.content);
      if (prompt === null) return;
      const traceId = `mock-turn-${++turnSequence}`;
      if (prompt === '') {
        ws.send(
          JSON.stringify(
            errorFrame(
              threadId,
              'MALFORMED_REQUEST',
              '@AI 뒤에 요청 내용을 입력해 주세요.',
              traceId,
            ),
          ),
        );
        return;
      }

      if (!aiQueue.enqueue({ threadId, generation, prompt, traceId })) {
        ws.send(
          JSON.stringify(
            errorFrame(
              threadId,
              'RATE_LIMITED',
              '이 방의 AI 요청 대기열이 가득 찼습니다.',
              traceId,
            ),
          ),
        );
      }
    },

    close(ws) {
      const { connectionId, threadId, userId, generation } = ws.data;
      if (generation === null) return;
      if (registry.leave(threadId, connectionId, generation)) {
        aiQueue.closeRoom(threadId, generation);
      }
      // 방이 비었으면 remaining이 빈 배열이라 아무 데도 나가지 않는다 — 실서버도 그렇다.
      const remaining = registry.membersOf(threadId);
      if (!remaining.some((other) => other.userId === userId)) {
        sendPresence(remaining, 'presence.leave', threadId, userId);
      }
      console.log(`[mock-ws] leave ${userId} ← ${threadId}`);
    },
  },
});

console.log(
  `[mock-ws] ws://localhost:${server.port}/api/ws/{threadId} 준비 완료`,
);
