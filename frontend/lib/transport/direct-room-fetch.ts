/********************************************************
 파일명 : direct-room-fetch.ts (lib/transport)
 설 명 : DIRECT 1:1 화면이 useChat({fetch})에 넘길 WS 기반 fetch를 만든다(이슈 #162, §2.1·§3.1).
 방 하나에 하나씩 만들어 재사용해야 한다 — 첫 턴은 구독 없이 chat.message만 보내고, 자기
 clientMsgId가 든 에코를 받으면 활성 구독으로 승격하며, 이후 턴은 이미 활성 구독이므로 바로
 발화만 보낸다(같은 연결에 두 번째 room.subscribe를 보내지 않는다).

 useChat은 요청마다 fetch(input, init)를 부르고 그 반환 Response의 body를 읽어 스트리밍한다 —
 이 파일은 그 한 번의 fetch 호출을 "하나의 턴"으로 여기고, 방에서 오는 chat.answer 중 그 턴의
 turnId가 실린 것만 골라 frameSourceToResponse(#10)에 흘려보낸다. 같은 방의 다른 턴(다른 탭이
 부른 것 포함)이나 presence류는 여기서 조용히 버려진다 — 관심 있는 소비자가 없기 때문이다.

 system.notice(#29)는 턴과 무관하게 매번 onAnswerTerminal의 형제 콜백인 onSystemNotice로
 그대로 넘긴다 — 배너·토스트를 그릴지는 화면(chat.tsx) 몫이다. 다만 그 notice의 traceId가
 지금 활성 턴의 turnId와 같은 warning이면 그 턴도 함께 terminal 오류로 끝낸다(§2.2 —
 "배너를 보이면서 끝낸다"는 계약).
 *********************************************************/

import type { CitationsResponse } from '@/lib/api/chat';
import { generateUUID } from '@/lib/utils';
import {
  type RoomListenSubscription,
  type RoomListener,
  type RoomSubscription,
  listenRoom,
  subscribeRoom,
} from '@/lib/api/ws-rooms';
import {
  type FrameStreamCallbacks,
  frameSourceToResponse,
} from './frame-stream-fetch';
import type {
  ChatAnswerFrame,
  ChatMessageFrame,
  ClientFrame,
  SystemNoticeFrame,
  WsErrorFrame,
  WsFrame,
} from './frames';

/** push된 값을 pull 기반 AsyncIterable로 내보낸다. WS의 onFrame(콜백/push)과
 * frameSourceToResponse가 기대하는 FrameSource(AsyncIterable<string>, pull)를 잇는 다리다. */
function createAsyncQueue<T>() {
  const buffer: T[] = [];
  const waiting: Array<{
    resolve: (result: IteratorResult<T>) => void;
    reject: (error: unknown) => void;
  }> = [];
  let closed = false;
  let failure: unknown = null;

  return {
    push(value: T) {
      if (closed) return;
      const waiter = waiting.shift();
      if (waiter) {
        waiter.resolve({ value, done: false });
      } else {
        buffer.push(value);
      }
    },
    close() {
      if (closed) return;
      closed = true;
      for (const waiter of waiting.splice(0)) {
        waiter.resolve({ value: undefined as unknown as T, done: true });
      }
    },
    error(err: unknown) {
      if (closed) return;
      closed = true;
      failure = err;
      for (const waiter of waiting.splice(0)) {
        waiter.reject(err);
      }
    },
    [Symbol.asyncIterator](): AsyncIterator<T> {
      return {
        next(): Promise<IteratorResult<T>> {
          if (buffer.length > 0) {
            return Promise.resolve({ value: buffer.shift() as T, done: false });
          }
          if (closed) {
            return failure
              ? Promise.reject(failure)
              : Promise.resolve({
                  value: undefined as unknown as T,
                  done: true,
                });
          }
          return new Promise((resolve, reject) =>
            waiting.push({ resolve, reject }),
          );
        },
      };
    },
  };
}

type AsyncQueue<T> = ReturnType<typeof createAsyncQueue<T>>;

interface WireMessage {
  role: string;
  content: string;
}

interface ChatRequestBody {
  sessionId?: string;
  modelId?: string;
  clientMsgId?: string;
  messages?: WireMessage[];
}

function latestUserContent(messages: WireMessage[] | undefined): string {
  const last = messages?.at(-1);
  return last?.content ?? '';
}

export interface DirectChatFetch {
  /** useChat({ fetch })에 그대로 넘긴다. */
  fetch: typeof fetch;
  /** 화면이 떠날 때 구독을 닫는다(이슈 #161 — 커넥션은 다른 방과 공유하므로 이 방 구독만 푼다). */
  dispose: () => void;
}

/** 커넥션이 처음 열릴 때까지 기다리는 최대 시간. 이 안에 안 열리면 연결 문제로 보고 오류를
 * 돌려준다 — ws-connection.ts의 백오프가 계속 재시도하므로 다음 전송은 다시 시도해볼 수 있다. */
const OPEN_TIMEOUT_MS = 10_000;

export interface CreateDirectChatFetchOptions {
  /** 이미 확인된 기존 DIRECT 방(이력이 있는 재진입)이면 true로 시작해 첫 턴부터 바로
   * room.subscribe를 건다(§2.1 "기존 이력이 확인되면 room.subscribe를 먼저 전송"). 빈
   * draft(새 /chat)면 false로 시작해 첫 chat.message가 bootstrap을 겸하게 한다. */
  startPromoted?: boolean;
  /**
   * 매 턴이 끝날 때마다 어떻게 끝났는지 알린다(이슈 #162, §3.2). useChat의 text 스트림
   * body는 "끝났다"만 전할 뿐이라, PENDING·DENIED·CANCELLED를 구분해 보여주려는 화면은
   * 이 콜백과 useChat의 onFinish(resultMessage)를 짝지어 message.id별로 기억해야 한다 —
   * 이 함수는 어느 message.id에 매길지 모른다(그건 useChat 내부에서 정해지기 때문이다).
   */
  onAnswerTerminal?: (status: 'done' | 'cancelled' | 'denied') => void;

  /** 모든 HUMAN echo를 방 상태 병합 경로에 전달한다. 현재 요청의 echo만 처리하면 다른 탭이 사라진다. */
  onChatMessage?: (frame: ChatMessageFrame) => void;

  /** 모든 AGENT 프레임을 방 상태 병합 경로에 전달한다. */
  onChatAnswer?: (frame: ChatAnswerFrame) => void;

  /** 현재 fetch 턴의 terminal AGENT 프레임은 useChat 임시 assistant ID를 서버 msgId로
   * 정규화할 수 있도록 별도로 전달한다. */
  onCurrentAnswerTerminal?: (frame: ChatAnswerFrame) => void;

  onTurnStarted?: (turn: { clientMsgId: string; turnId: string }) => void;

  /** 이 턴의 근거 인용이 도착하면 한 번 불린다(이슈 #163). 기존 REST `fetchCitations`를 대신한다
   * — 서버가 `delta`보다 먼저 보내는 citations 전용 chat.answer 패킷에서 나온다. */
  onChatCitation?: (payload: CitationsResponse) => void;

  onOpenChange?: (open: boolean) => void;
  /** 이 방에서 온 system.notice를 턴 매칭 여부와 무관하게 전부 받는다(#29, 이슈 #162) — 배너·
   * 토스트를 그릴지는 화면 몫이다. */
  onSystemNotice?: (frame: SystemNoticeFrame) => void;
}

/** DIRECT 방 하나의 WS 기반 useChat 전송을 만든다. */
export function createDirectChatFetch(
  threadId: string,
  options?: CreateDirectChatFetchOptions,
): DirectChatFetch {
  let subscription: RoomSubscription | RoomListenSubscription | null = null;
  let promoted = options?.startPromoted ?? false;
  let isOpen = false;
  let openWaiters: Array<() => void> = [];
  const activeTurns = new Map<
    string,
    { queue: AsyncQueue<string>; cleanup: () => void }
  >();
  /** 아직 에코를 못 받은 clientMsgId → 그 발화의 turnId. 에코 도착 시 승격 판단에만 쓰고
   * 곧바로 지운다 — 그 발화의 실제 답변 라우팅은 turnId로 activeTurns가 이미 맡는다. */
  const pendingEchoes = new Map<string, string>();

  const handleFrame: RoomListener['onFrame'] = (frame: WsFrame) => {
    if (frame.type === 'chat.message') {
      options?.onChatMessage?.(frame);
      const turnId =
        frame.clientMsgId !== null
          ? pendingEchoes.get(frame.clientMsgId)
          : undefined;
      if (turnId !== undefined) {
        pendingEchoes.delete(frame.clientMsgId as string);
        if (!promoted) {
          promoted = true;
          if (subscription && 'promote' in subscription) subscription.promote();
        }
      }
      return;
    }
    if (frame.type === 'chat.answer') {
      options?.onChatAnswer?.(frame);
      const active =
        frame.turnId !== null ? activeTurns.get(frame.turnId) : undefined;
      if (
        active &&
        (frame.status === 'done' ||
          frame.status === 'cancelled' ||
          frame.status === 'denied')
      ) {
        options?.onCurrentAnswerTerminal?.(frame);
      }
      active?.queue.push(JSON.stringify(frame));
      return;
    }
    // 워커·비동기 저장 실패는 system.notice(warning)로 온다(§2.2). 종류·턴 매칭과 무관하게
    // 전부 화면으로 넘겨 배너·토스트를 그리게 하고(§3.1), 해당 턴의 traceId==turnId인 warning은
    // 배너와 별개로 그 턴의 응답도 terminal 오류로 끝낸다("배너를 보이면서 끝낸다"는 §2.2 계약).
    if (frame.type === 'system.notice') {
      options?.onSystemNotice?.(frame);
      if (frame.severity === 'warning' && frame.traceId) {
        const active = activeTurns.get(frame.traceId);
        if (active) {
          const synthetic: WsErrorFrame = {
            type: 'error',
            threadId: frame.threadId,
            code: frame.code,
            message: frame.message,
            traceId: frame.traceId,
          };
          active.queue.push(JSON.stringify(synthetic));
          active.cleanup();
        }
      }
      return;
    }
    if (frame.type === 'error' && frame.traceId) {
      const active = activeTurns.get(frame.traceId);
      active?.queue.push(JSON.stringify(frame));
      active?.cleanup();
    }
  };

  const onOpenChange = (open: boolean) => {
    isOpen = open;
    options?.onOpenChange?.(open);
    if (open) {
      const waiters = openWaiters.splice(0);
      for (const resolve of waiters) resolve();
    }
  };

  const ensureSubscription = () => {
    if (subscription) return;
    subscription = promoted
      ? subscribeRoom(threadId, { onFrame: handleFrame, onOpenChange })
      : listenRoom(threadId, { onFrame: handleFrame, onOpenChange });
  };

  /** listenRoom·subscribeRoom은 커넥션을 여는 동안에도 즉시 반환한다 — 그 순간 send()하면
   * 아직 안 열린 소켓이라 조용히 실패한다. 열릴 때까지 기다렸다가 보낸다(핸드셰이크는
   * 보통 수십 ms지만 첫 발화가 그사이 끼어들면 도착조차 못 한다). */
  const waitUntilOpen = (): Promise<void> => {
    if (isOpen) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        openWaiters = openWaiters.filter((w) => w !== onOpen);
        reject(new Error('WebSocket 연결을 여는 데 실패했습니다.'));
      }, OPEN_TIMEOUT_MS);
      function onOpen() {
        clearTimeout(timer);
        resolve();
      }
      openWaiters.push(onOpen);
    });
  };

  const send = (frame: ClientFrame): boolean =>
    subscription?.send(frame) ?? false;

  const directFetch: typeof fetch = async (_input, init) => {
    ensureSubscription();
    await waitUntilOpen();

    const body = JSON.parse(String(init?.body ?? '{}')) as ChatRequestBody;
    const content = latestUserContent(body.messages);
    const clientMsgId = body.clientMsgId ?? generateUUID();
    const turnId = generateUUID();

    const queue = createAsyncQueue<string>();
    pendingEchoes.set(clientMsgId, turnId);

    const cleanup = () => {
      activeTurns.delete(turnId);
      pendingEchoes.delete(clientMsgId);
      init?.signal?.removeEventListener('abort', onAbort);
    };

    activeTurns.set(turnId, { queue, cleanup });
    options?.onTurnStarted?.({ clientMsgId, turnId });
    const onAbort = () => {
      send({ type: 'chat.cancel', threadId, turnId });
    };
    init?.signal?.addEventListener('abort', onAbort);

    const sent = send({
      type: 'chat.message',
      threadId,
      content,
      model: body.modelId,
      clientMsgId,
      turnId,
    });
    if (!sent) {
      cleanup();
      return new Response(
        JSON.stringify({
          error: {
            code: 'CONNECTION_CLOSED',
            message: '연결이 끊겨 있어 메시지를 보내지 못했습니다.',
            traceId: turnId,
          },
        }),
        { status: 503, headers: { 'Content-Type': 'application/json' } },
      );
    }

    const callbacks: FrameStreamCallbacks = {
      onChatCitation: options?.onChatCitation,
      onChatAnswerTerminal: (status) => {
        cleanup();
        options?.onAnswerTerminal?.(status);
      },
    };
    try {
      return await frameSourceToResponse(queue, callbacks);
    } catch (error) {
      cleanup();
      throw error;
    }
  };

  return {
    fetch: directFetch,
    dispose: () => {
      subscription?.close();
      subscription = null;
      activeTurns.clear();
      pendingEchoes.clear();
    },
  };
}
