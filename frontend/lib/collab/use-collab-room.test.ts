// @vitest-environment jsdom

/********************************************************
 파일명 : use-collab-room.test.ts (lib/collab)
 설 명 : 최초 연결 따라잡기(#208) 회귀 테스트. `subscribeRoom`·`fetchCollabMessages`를
 목으로 세우고 이력 REST와 방 구독 open의 순서를 직접 통제해, 어느 쪽이 먼저 끝나든 커서
 있는 따라잡기가 정확히 한 번만 나가는지 검증한다.

 이 훅은 지금까지 테스트가 없었다 — #208 자체가 그 사각지대(두 비동기 시작점의 순서 조율)에서
 나온 버그라, 여기서 닫는다.
 *********************************************************/

import { act, renderHook, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CollabMessageItem } from '@/lib/api/collab';
import type { RoomListener } from '@/lib/api/ws-rooms';
import { useCollabRoom } from './use-collab-room';

const THREAD = 'thread-1';

const mocks = vi.hoisted(() => ({
  fetchCollabMessages: vi.fn(),
  subscribeRoom: vi.fn(),
}));

vi.mock('@/lib/api/collab', () => ({
  fetchCollabMessages: mocks.fetchCollabMessages,
}));

vi.mock('@/lib/api/ws-rooms', () => ({
  subscribeRoom: mocks.subscribeRoom,
}));

vi.mock('sonner', () => ({
  toast: { info: vi.fn(), error: vi.fn() },
}));

/** 각 subscribeRoom 호출의 listener를 순서대로 담는다 — threadId 변경 테스트에서 여러 번
 * 구독하므로 배열로 받는다. */
let capturedListeners: RoomListener[] = [];

function latestListener(): RoomListener {
  const listener = capturedListeners.at(-1);
  if (!listener) throw new Error('subscribeRoom이 아직 호출되지 않았다');
  return listener;
}

/** 이력 REST 응답 한 줄. */
function item(id: string, seq: number): CollabMessageItem {
  return {
    id,
    seq,
    athKind: 'HUMAN',
    status: 'COMPLETE',
    content: `내용-${id}`,
    authorSubject: 'user-1',
    authorDisplayName: '사용자',
    createdAt: new Date(seq * 1000).toISOString(),
    completedAt: new Date(seq * 1000).toISOString(),
  };
}

/** 테스트가 직접 resolve/reject 시점을 정하기 위한 미결 Promise. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

afterEach(() => {
  vi.clearAllMocks();
  capturedListeners = [];
});

mocks.subscribeRoom.mockImplementation(
  (_threadId: string, listener: RoomListener) => {
    capturedListeners.push(listener);
    return { close: vi.fn(), send: vi.fn(() => true) };
  },
);

describe('useCollabRoom — 최초 연결 따라잡기(#208)', () => {
  it('이력이 WS open보다 먼저 끝나면, open 시 커서 있는 따라잡기가 1회 나간다', async () => {
    const history = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(history.promise);

    renderHook(() => useCollabRoom(THREAD));
    expect(mocks.fetchCollabMessages).toHaveBeenNthCalledWith(1, THREAD);

    history.resolve([item('m1', 5)]);
    await waitFor(() => {
      // 이력 이펙트의 setState+finally가 다 돌 때까지 기다린다. lastSeq 동기화는 별도
      // 이펙트라 한 틱 더 걸릴 수 있어 open은 이 이후에 보낸다.
    });

    const catchUp = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(catchUp.promise);

    act(() => {
      latestListener().onOpenChange?.(true);
    });

    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(2);
    });
    expect(mocks.fetchCollabMessages).toHaveBeenNthCalledWith(2, THREAD, 5);
  });

  it('WS open이 이력보다 먼저 오면, open 시점엔 추가 호출이 없다가 이력이 끝나는 순간 커서 있는 따라잡기가 1회 나간다', async () => {
    const history = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(history.promise);

    renderHook(() => useCollabRoom(THREAD));

    act(() => {
      latestListener().onOpenChange?.(true);
    });

    // 이력이 아직 안 끝났다 — 커서 없는 전체 재조회가 나가면 안 된다(#208의 핵심).
    expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(1);

    const catchUp = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(catchUp.promise);

    history.resolve([item('m1', 7)]);

    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(2);
    });
    expect(mocks.fetchCollabMessages).toHaveBeenNthCalledWith(2, THREAD, 7);
  });

  it('이력 REST가 실패해도 최초 따라잡기가 켜진다(안전망) — 커서 없이 나간다', async () => {
    const history = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(history.promise);

    renderHook(() => useCollabRoom(THREAD));

    history.reject(new Error('network error'));
    await history.promise.catch(() => {});

    const catchUp = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(catchUp.promise);

    act(() => {
      latestListener().onOpenChange?.(true);
    });

    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(2);
    });
    expect(mocks.fetchCollabMessages).toHaveBeenNthCalledWith(
      2,
      THREAD,
      undefined,
    );
  });

  it('최초 따라잡기 이후의 재연결은 기존 재연결 따라잡기로 그대로 동작하고, 최초 따라잡기가 중복 발동하지 않는다', async () => {
    mocks.fetchCollabMessages.mockResolvedValueOnce([]); // 진입 이력
    renderHook(() => useCollabRoom(THREAD));
    await waitFor(() => {
      // 이력이 정착할 때까지 기다린다(아래 open에서 바로 최초 따라잡기가 나가야 하므로).
    });

    mocks.fetchCollabMessages.mockResolvedValueOnce([]); // 최초 따라잡기
    act(() => {
      latestListener().onOpenChange?.(true);
    });
    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(2);
    });

    // 끊김 → 재연결. 최초 따라잡기는 이미 한 번 발동했으니 다시 나가면 안 된다.
    act(() => {
      latestListener().onOpenChange?.(false);
    });
    mocks.fetchCollabMessages.mockResolvedValueOnce([]); // 재연결 따라잡기
    act(() => {
      latestListener().onOpenChange?.(true);
    });

    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(3);
    });
  });

  it('threadId가 바뀌면(방 이동) 래치가 리셋돼 새 방에서도 한 번 더 발동한다', async () => {
    mocks.fetchCollabMessages.mockResolvedValueOnce([]); // 방 A 이력
    const { rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useCollabRoom(threadId),
      { initialProps: { threadId: 'thread-a' } },
    );
    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(1);
    });

    mocks.fetchCollabMessages.mockResolvedValueOnce([]); // 방 A 최초 따라잡기
    act(() => {
      capturedListeners[0]?.onOpenChange?.(true);
    });
    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(2);
    });

    mocks.fetchCollabMessages.mockResolvedValueOnce([]); // 방 B 이력
    rerender({ threadId: 'thread-b' });
    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(3);
    });
    expect(mocks.fetchCollabMessages).toHaveBeenNthCalledWith(3, 'thread-b');

    mocks.fetchCollabMessages.mockResolvedValueOnce([]); // 방 B 최초 따라잡기
    act(() => {
      capturedListeners.at(-1)?.onOpenChange?.(true);
    });
    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(4);
    });
  });

  it('React StrictMode 이중 마운트에서 첫 번째(버려지는) 마운트의 뒤늦은 이력 응답이 두 번째(생존) 마운트를 오염시키지 않는다', async () => {
    // StrictMode는 mount → cleanup → mount를 초기 렌더 한 커밋 안에서 동기적으로 두 번
    // 돈다 — 이력·WS 호출이 각 마운트마다 한 번씩, 총 두 번 나가는 게 정상이다. 1차 마운트의
    // 소켓은 cleanup에서 close()가 불리므로 실제로는 그 뒤 open을 내지 않는다(WebSocket
    // 스펙상 CONNECTING 중 close()하면 open 없이 닫힌다) — 여기서는 그 마운트가 실제로 낼 수
    // 있는 유일한 뒤늦은 결과, 즉 이미 나간 이력 REST 응답만 재현한다.
    const firstHistory = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(firstHistory.promise); // 1차(버려질) 마운트 이력
    const secondHistory = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(secondHistory.promise); // 2차(생존) 마운트 이력

    const { result } = renderHook(() => useCollabRoom(THREAD), {
      wrapper: StrictMode,
    });
    expect(capturedListeners).toHaveLength(2);
    const secondListener = capturedListeners[1];

    firstHistory.resolve([item('stale', 999)]);
    await firstHistory.promise;

    // 버려진 마운트의 결과가 상태로 안 새야 한다 — 최초 따라잡기도 안 나가야 한다(그 마운트의
    // wsOpenedOnce는 open을 받은 적이 없어 계속 false다).
    expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(2);
    expect(
      result.current.state.messages.some(
        (entry) => !('event' in entry) && entry.id === 'stale',
      ),
    ).toBe(false);

    // 2차(생존) 마운트는 정상 진행된다 — 진짜 커서(3)로 따라잡기가 나가야 한다.
    act(() => {
      secondListener.onOpenChange?.(true);
    });
    const realCatchUp = deferred<CollabMessageItem[]>();
    mocks.fetchCollabMessages.mockReturnValueOnce(realCatchUp.promise);
    secondHistory.resolve([item('real', 3)]);

    await waitFor(() => {
      expect(mocks.fetchCollabMessages).toHaveBeenCalledTimes(3);
    });
    expect(mocks.fetchCollabMessages).toHaveBeenNthCalledWith(3, THREAD, 3);
  });
});

/**
 * 중지 버튼이 무엇을 고르는지(#205). 서버는 턴을 시작한 커넥션이 보낸 취소만 인정하므로
 * (CollabMessageDispatcher.cancel), 화면도 "내가 부른 턴"만 멈출 수 있는 것으로 보여야 한다.
 */
describe('useCollabRoom — 중지할 수 있는 턴 고르기(#205)', () => {
  /** chat.answer 패킷 한 장. status만 바꿔 흐름과 종료를 만든다. */
  function answer(turnId: string | null, done: boolean, msgId = 'ans-1') {
    return {
      type: 'chat.answer' as const,
      threadId: THREAD,
      msgId,
      turnId,
      model: 'gemma',
      seq: 10,
      delta: '답',
      citations: [],
      restrictedResultsOmitted: false,
      status: done ? ('done' as const) : ('streaming' as const),
    };
  }

  async function openedRoom() {
    mocks.fetchCollabMessages.mockResolvedValue([]);
    const { result } = renderHook(() => useCollabRoom(THREAD));
    await act(async () => {
      latestListener().onOpenChange?.(true);
    });
    return result;
  }

  it('내가 부른 턴이 흐르는 동안에는 그 turnId를 돌려준다', async () => {
    const result = await openedRoom();

    let turnId = '';
    await act(async () => {
      turnId = result.current.send('@AI 질문')?.turnId ?? '';
    });
    expect(turnId).not.toBe('');

    await act(async () => {
      latestListener().onFrame(answer(turnId, false));
    });

    expect(result.current.cancellableTurnId).toBe(turnId);
  });

  it('턴이 끝나면 다시 null이다 — 멈출 것이 없다', async () => {
    const result = await openedRoom();

    let turnId = '';
    await act(async () => {
      turnId = result.current.send('@AI 질문')?.turnId ?? '';
    });
    await act(async () => {
      latestListener().onFrame(answer(turnId, false));
    });
    await act(async () => {
      latestListener().onFrame(answer(turnId, true));
    });

    expect(result.current.cancellableTurnId).toBeNull();
  });

  it('남이 부른 턴은 고르지 않는다 — 눌러도 서버가 인정하지 않는다', async () => {
    const result = await openedRoom();

    await act(async () => {
      latestListener().onFrame(answer('남의-턴', false));
    });

    expect(result.current.cancellableTurnId).toBeNull();
  });

  it('turnId 없이 온 답변도 고르지 않는다 — 지목할 대상이 없다', async () => {
    const result = await openedRoom();

    await act(async () => {
      latestListener().onFrame(answer(null, false));
    });

    expect(result.current.cancellableTurnId).toBeNull();
  });
});
