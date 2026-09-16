import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RoomListener } from '@/lib/api/ws-rooms';
import type { ClientFrame, WsFrame } from './frames';

const THREAD_ID = '11111111-1111-4111-8111-111111111111';

interface FakeSubscription {
  onFrame: RoomListener['onFrame'];
  sent: ClientFrame[];
  send: ReturnType<typeof vi.fn>;
  promote: ReturnType<typeof vi.fn>;
  close: ReturnType<typeof vi.fn>;
}

function fakeSubscription(listener: RoomListener): FakeSubscription {
  const sent: ClientFrame[] = [];
  // 실제 소켓은 핸드셰이크 뒤에 열리지만, 이 목업은 즉시 연다 — direct-room-fetch.ts가
  // waitUntilOpen()에서 이 신호를 기다리므로 안 부르면 모든 테스트가 멈춘다.
  listener.onOpenChange?.(true);
  return {
    onFrame: listener.onFrame,
    sent,
    send: vi.fn((frame: ClientFrame) => {
      sent.push(frame);
      return true;
    }),
    promote: vi.fn(),
    close: vi.fn(),
  };
}

const listenRoom = vi.fn();
const subscribeRoom = vi.fn();

vi.mock('@/lib/api/ws-rooms', () => ({
  listenRoom: (threadId: string, listener: RoomListener) =>
    listenRoom(threadId, listener),
  subscribeRoom: (threadId: string, listener: RoomListener) =>
    subscribeRoom(threadId, listener),
}));

// 모킹 뒤에 임포트해야 위 vi.mock이 적용된 모듈을 가져온다.
const { createDirectChatFetch } = await import('./direct-room-fetch');

function chatAnswer(
  turnId: string,
  partial: Partial<
    Pick<
      Extract<WsFrame, { type: 'chat.answer' }>,
      'delta' | 'status' | 'msgId' | 'citations' | 'restrictedResultsOmitted'
    >
  >,
) {
  return JSON.stringify({
    type: 'chat.answer',
    threadId: THREAD_ID,
    msgId: partial.msgId ?? 'agent-msg-1',
    turnId,
    model: 'm',
    seq: 1,
    delta: partial.delta ?? '',
    citations: partial.citations ?? [],
    restrictedResultsOmitted: partial.restrictedResultsOmitted ?? false,
    status: partial.status ?? 'streaming',
  });
}

function chatMessageEcho(clientMsgId: string, turnId: string) {
  return JSON.stringify({
    type: 'chat.message',
    threadId: THREAD_ID,
    msgId: 'human-msg-1',
    clientMsgId,
    turnId,
    seq: 0,
    from: 'user-1',
    fromDisplayName: '사용자',
    content: '안녕',
  });
}

beforeEach(() => {
  listenRoom.mockReset();
  subscribeRoom.mockReset();
  listenRoom.mockImplementation((_threadId: string, listener: RoomListener) =>
    fakeSubscription(listener),
  );
  subscribeRoom.mockImplementation(
    (_threadId: string, listener: RoomListener) => fakeSubscription(listener),
  );
});

/** direct.fetch()는 waitUntilOpen()에서 한 틱 양보한 뒤에야 send()한다 — 이미 열려 있는
 * 목업 커넥션이라도 그 틱이 지나가야 sub.sent에 chat.message가 쌓인다. */
const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/** send()가 호출한 프레임에서 서버가 만든 clientMsgId·turnId를 읽어, 테스트가 그 값으로
 * 에코·답변 프레임을 흉내 낼 수 있게 한다. */
async function sentChatMessage(
  sub: FakeSubscription,
  nth = 0,
): Promise<{ clientMsgId: string; turnId: string }> {
  await flush();
  const frames = sub.sent.filter((f) => f.type === 'chat.message');
  const frame = frames[nth];
  if (
    !frame ||
    frame.type !== 'chat.message' ||
    !frame.clientMsgId ||
    !frame.turnId
  ) {
    throw new Error('chat.message가 전송되지 않았다');
  }
  return { clientMsgId: frame.clientMsgId, turnId: frame.turnId };
}

describe('createDirectChatFetch — bootstrap과 승격', () => {
  it('첫 요청은 listenRoom으로 시작하고, 자기 clientMsgId 에코를 받으면 승격한다', async () => {
    const direct = createDirectChatFetch(THREAD_ID);
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({
        sessionId: THREAD_ID,
        modelId: 'm',
        messages: [{ role: 'user', content: '안녕' }],
      }),
    });

    expect(listenRoom).toHaveBeenCalledOnce();
    expect(subscribeRoom).not.toHaveBeenCalled();
    const sub: FakeSubscription = listenRoom.mock.results[0].value;
    const { clientMsgId, turnId } = await sentChatMessage(sub);

    expect(sub.promote).not.toHaveBeenCalled();
    sub.onFrame(JSON.parse(chatMessageEcho(clientMsgId, turnId)));
    expect(sub.promote).toHaveBeenCalledOnce();

    sub.onFrame(
      JSON.parse(chatAnswer(turnId, { delta: '반가워요', status: 'done' })),
    );
    const response = await responsePromise;
    await expect(response.text()).resolves.toBe('반가워요');
  });

  it('두 번째 턴부터는 같은 구독을 재사용하고 listenRoom·subscribeRoom을 다시 부르지 않는다', async () => {
    const direct = createDirectChatFetch(THREAD_ID);
    const first = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '하나' }] }),
    });
    const sub: FakeSubscription = listenRoom.mock.results[0].value;
    const firstIds = await sentChatMessage(sub);
    sub.onFrame(
      JSON.parse(chatMessageEcho(firstIds.clientMsgId, firstIds.turnId)),
    );
    sub.onFrame(JSON.parse(chatAnswer(firstIds.turnId, { status: 'done' })));
    await first;

    const second = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '둘' }] }),
    });
    expect(listenRoom).toHaveBeenCalledOnce();
    expect(subscribeRoom).not.toHaveBeenCalled();
    const secondIds = await sentChatMessage(sub, 1);
    sub.onFrame(JSON.parse(chatAnswer(secondIds.turnId, { status: 'done' })));
    await second;
  });

  it('이미 확인된 기존 방(startPromoted)은 처음부터 subscribeRoom을 쓴다', () => {
    createDirectChatFetch(THREAD_ID, { startPromoted: true }).fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
    });
    expect(subscribeRoom).toHaveBeenCalledOnce();
    expect(listenRoom).not.toHaveBeenCalled();
  });
});

describe('createDirectChatFetch — DIRECT 전용 terminal 상태', () => {
  it('denied로 끝난 턴은 스트림을 닫고 이후 프레임을 더 이상 라우팅하지 않는다', async () => {
    const direct = createDirectChatFetch(THREAD_ID, { startPromoted: true });
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
    });
    const sub: FakeSubscription = subscribeRoom.mock.results[0].value;
    const { turnId } = await sentChatMessage(sub);

    sub.onFrame(JSON.parse(chatAnswer(turnId, { status: 'denied' })));
    const response = await responsePromise;
    await expect(response.text()).resolves.toBe('');
  });

  it('취소 시 AbortSignal을 받으면 chat.cancel을 보낸다', async () => {
    const direct = createDirectChatFetch(THREAD_ID, { startPromoted: true });
    const controller = new AbortController();
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
      signal: controller.signal,
    });
    const sub: FakeSubscription = subscribeRoom.mock.results[0].value;
    const { turnId } = await sentChatMessage(sub);

    controller.abort();
    expect(sub.sent).toContainEqual({
      type: 'chat.cancel',
      threadId: THREAD_ID,
      turnId,
    });

    sub.onFrame(JSON.parse(chatAnswer(turnId, { status: 'cancelled' })));
    await responsePromise;
  });

  it('스트림 시작 전(첫 프레임)에 내 턴의 system.notice(warning)가 오면 에러 프레임처럼 상태 코드로 답한다', async () => {
    const direct = createDirectChatFetch(THREAD_ID, { startPromoted: true });
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
    });
    const sub: FakeSubscription = subscribeRoom.mock.results[0].value;
    const { turnId } = await sentChatMessage(sub);

    sub.onFrame(
      JSON.parse(
        JSON.stringify({
          type: 'system.notice',
          threadId: THREAD_ID,
          severity: 'warning',
          code: 'MESSAGE_DELIVERY_FAILED',
          message: '메시지를 전달하지 못했습니다.',
          traceId: turnId,
        }),
      ),
    );
    const response = await responsePromise;
    expect(response.ok).toBe(false);
    const body = (await response.json()) as { error: { code: string } };
    expect(body.error.code).toBe('MESSAGE_DELIVERY_FAILED');
  });

  it('토큰이 이미 흐른 뒤 내 턴의 system.notice(warning)가 오면 스트림 읽기가 끊긴다', async () => {
    const direct = createDirectChatFetch(THREAD_ID, { startPromoted: true });
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
    });
    const sub: FakeSubscription = subscribeRoom.mock.results[0].value;
    const { turnId } = await sentChatMessage(sub);

    sub.onFrame(
      JSON.parse(chatAnswer(turnId, { delta: '안', status: 'streaming' })),
    );
    const response = await responsePromise;
    const reader = response.body?.getReader();
    if (!reader) throw new Error('no body');
    await reader.read();

    sub.onFrame(
      JSON.parse(
        JSON.stringify({
          type: 'system.notice',
          threadId: THREAD_ID,
          severity: 'warning',
          code: 'MESSAGE_DELIVERY_FAILED',
          message: '메시지를 전달하지 못했습니다.',
          traceId: turnId,
        }),
      ),
    );
    await expect(reader.read()).rejects.toThrow();
  });

  it('다른 턴의 system.notice는 이 턴에 영향을 주지 않는다', async () => {
    const direct = createDirectChatFetch(THREAD_ID, { startPromoted: true });
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
    });
    const sub: FakeSubscription = subscribeRoom.mock.results[0].value;
    const { turnId } = await sentChatMessage(sub);

    sub.onFrame(
      JSON.parse(
        JSON.stringify({
          type: 'system.notice',
          threadId: THREAD_ID,
          severity: 'warning',
          code: 'MESSAGE_DELIVERY_FAILED',
          message: '다른 턴 알림',
          traceId: 'other-turn-id',
        }),
      ),
    );
    sub.onFrame(
      JSON.parse(chatAnswer(turnId, { delta: '괜찮음', status: 'done' })),
    );
    const response = await responsePromise;
    await expect(response.text()).resolves.toBe('괜찮음');
  });

  it('onAnswerTerminal은 매 턴이 done·cancelled·denied 무엇으로 끝났는지 알린다', async () => {
    const onAnswerTerminal = vi.fn();
    const onCurrentAnswerTerminal = vi.fn();
    const direct = createDirectChatFetch(THREAD_ID, {
      startPromoted: true,
      onAnswerTerminal,
      onCurrentAnswerTerminal,
    });
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
    });
    const sub: FakeSubscription = subscribeRoom.mock.results[0].value;
    const { turnId } = await sentChatMessage(sub);

    sub.onFrame(JSON.parse(chatAnswer(turnId, { status: 'denied' })));
    await responsePromise;

    expect(onAnswerTerminal).toHaveBeenCalledExactlyOnceWith('denied');
    expect(onCurrentAnswerTerminal).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ status: 'denied' }),
    );
  });

  it('onChatCitation은 delta보다 먼저 오는 citations 전용 패킷을 그대로 넘긴다(이슈 #163)', async () => {
    const onChatCitation = vi.fn();
    const direct = createDirectChatFetch(THREAD_ID, {
      startPromoted: true,
      onChatCitation,
    });
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
    });
    const sub: FakeSubscription = subscribeRoom.mock.results[0].value;
    const { turnId } = await sentChatMessage(sub);

    sub.onFrame(
      JSON.parse(
        chatAnswer(turnId, {
          delta: '',
          citations: [
            { docId: 'doc-001', title: '제목', snippet: '발췌', score: 0.9 },
          ],
          restrictedResultsOmitted: false,
        }),
      ),
    );
    sub.onFrame(
      JSON.parse(chatAnswer(turnId, { delta: '반가워요', status: 'done' })),
    );
    const response = await responsePromise;
    await response.text();

    expect(onChatCitation).toHaveBeenCalledExactlyOnceWith({
      citations: [
        { docId: 'doc-001', title: '제목', snippet: '발췌', score: 0.9 },
      ],
      restrictedResultsOmitted: false,
    });
  });

  it('onSystemNotice는 턴 매칭 여부와 무관하게 방의 모든 notice를 전달한다', async () => {
    const onSystemNotice = vi.fn();
    const direct = createDirectChatFetch(THREAD_ID, {
      startPromoted: true,
      onSystemNotice,
    });
    const responsePromise = direct.fetch('ignored', {
      body: JSON.stringify({ messages: [{ role: 'user', content: '안녕' }] }),
    });
    const sub: FakeSubscription = subscribeRoom.mock.results[0].value;
    const { turnId } = await sentChatMessage(sub);

    const infoNotice = {
      type: 'system.notice',
      threadId: THREAD_ID,
      severity: 'info',
      code: 'TOKEN_BUDGET_LOW',
      message: '토큰이 얼마 안 남았습니다',
      traceId: 'other-turn-id',
    };
    sub.onFrame(JSON.parse(JSON.stringify(infoNotice)));
    expect(onSystemNotice).toHaveBeenCalledExactlyOnceWith(infoNotice);

    sub.onFrame(JSON.parse(chatAnswer(turnId, { status: 'done' })));
    await responsePromise;
  });
});
