// @vitest-environment jsdom

/********************************************************
 파일명 : use-room.test.ts (lib/chat)
 설 명 : 1:1 방의 bootstrap 승격(이슈 #162, §2.1) 검증. 협업방 쪽 동작은
 use-collab-room.test.ts가 래퍼를 통해 이미 덮으므로, 여기서는 방 종류로 갈리는 부분만 본다 —
 구독을 어떻게 시작하는지와, 무엇을 근거로 승격하는지다.

 승격을 "내가 보낸 발화의 에코"로 한정하는 것이 핵심이다. 서버는 bootstrap이 성공한 그
 커넥션만 자동 구독시키므로, 남의 발화 에코로 승격하면 구독되지 않은 방을 구독된 것으로 착각해
 재연결 뒤 복구 대상에서 빠진다.
 *********************************************************/

import type { RoomListener } from '@/lib/api/ws-rooms';
import type { WsFrame } from '@/lib/transport/frames';
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useRoom } from './use-room';

const THREAD = 'thread-1';

const mocks = vi.hoisted(() => ({
  subscribeRoom: vi.fn(),
  listenRoom: vi.fn(),
  promote: vi.fn(),
}));

vi.mock('@/lib/api/ws-rooms', () => ({
  subscribeRoom: mocks.subscribeRoom,
  listenRoom: mocks.listenRoom,
}));

vi.mock('sonner', () => ({
  toast: { info: vi.fn(), error: vi.fn() },
}));

let capturedListeners: RoomListener[] = [];

function latestListener(): RoomListener {
  const listener = capturedListeners.at(-1);
  if (!listener) throw new Error('아직 구독하지 않았다');
  return listener;
}

function captureAnd(extra: Record<string, unknown>) {
  return (_threadId: string, listener: RoomListener) => {
    capturedListeners.push(listener);
    return { close: vi.fn(), send: vi.fn(() => true), ...extra };
  };
}

mocks.subscribeRoom.mockImplementation(captureAnd({}));
mocks.listenRoom.mockImplementation(captureAnd({ promote: mocks.promote }));

/** 서버가 돌려주는 발화 에코. clientMsgId가 누구 것인지가 승격 판단의 전부다. */
function echo(clientMsgId: string | null, msgId: string): WsFrame {
  return {
    type: 'chat.message',
    threadId: THREAD,
    msgId,
    clientMsgId,
    turnId: null,
    seq: 0,
    from: 'someone',
    fromDisplayName: '누군가',
    content: '안녕',
  };
}

const emptyHistory = () => Promise.resolve([]);

afterEach(() => {
  vi.clearAllMocks();
  capturedListeners = [];
});

describe('useRoom — 구독 시작 방식', () => {
  it('기본값은 곧바로 room.subscribe를 건다 — 서버가 이미 아는 방이다', () => {
    renderHook(() => useRoom(THREAD, { fetchHistory: emptyHistory }));
    expect(mocks.subscribeRoom).toHaveBeenCalledTimes(1);
    expect(mocks.listenRoom).not.toHaveBeenCalled();
  });

  it('startPromoted:false면 듣기만 한다 — 아직 없는 방은 구독할 수 없다', () => {
    renderHook(() =>
      useRoom(THREAD, { fetchHistory: emptyHistory, startPromoted: false }),
    );
    expect(mocks.listenRoom).toHaveBeenCalledTimes(1);
    expect(mocks.subscribeRoom).not.toHaveBeenCalled();
  });
});

describe('useRoom — bootstrap 승격(이슈 #162, §2.1)', () => {
  it('내 발화의 에코를 받으면 승격한다', () => {
    const { result } = renderHook(() =>
      useRoom(THREAD, { fetchHistory: emptyHistory, startPromoted: false }),
    );

    let ids: { clientMsgId: string; turnId: string } | null = null;
    act(() => {
      ids = result.current.send('안녕');
    });
    expect(ids).not.toBeNull();
    expect(mocks.promote).not.toHaveBeenCalled();

    act(() => {
      latestListener().onFrame(
        echo((ids as { clientMsgId: string }).clientMsgId, 'server-msg-1'),
      );
    });
    expect(mocks.promote).toHaveBeenCalledTimes(1);
  });

  it('남의 발화 에코로는 승격하지 않는다', () => {
    renderHook(() =>
      useRoom(THREAD, { fetchHistory: emptyHistory, startPromoted: false }),
    );
    act(() => {
      latestListener().onFrame(echo('남의-clientMsgId', 'server-msg-1'));
      // clientMsgId가 없는 에코(이력 재생 등)도 근거가 되지 않는다.
      latestListener().onFrame(echo(null, 'server-msg-2'));
    });
    expect(mocks.promote).not.toHaveBeenCalled();
  });

  it('한 번 승격한 뒤에는 다시 부르지 않는다', () => {
    const { result } = renderHook(() =>
      useRoom(THREAD, { fetchHistory: emptyHistory, startPromoted: false }),
    );

    const sent: Array<{ clientMsgId: string }> = [];
    act(() => {
      const first = result.current.send('하나');
      const second = result.current.send('둘');
      if (first) sent.push(first);
      if (second) sent.push(second);
    });

    act(() => {
      latestListener().onFrame(echo(sent[0].clientMsgId, 'server-msg-1'));
      latestListener().onFrame(echo(sent[1].clientMsgId, 'server-msg-2'));
    });
    expect(mocks.promote).toHaveBeenCalledTimes(1);
  });
});

describe('useRoom — 이력 실패', () => {
  it('실패를 호출부에 넘긴다 — 방을 못 여는 실패인지는 호출부가 가른다', async () => {
    const onHistoryError = vi.fn();
    const failure = new Error('404');
    renderHook(() =>
      useRoom(THREAD, {
        fetchHistory: () => Promise.reject(failure),
        onHistoryError,
      }),
    );
    await act(async () => {
      await Promise.resolve();
    });
    expect(onHistoryError).toHaveBeenCalledWith(failure);
  });
});
