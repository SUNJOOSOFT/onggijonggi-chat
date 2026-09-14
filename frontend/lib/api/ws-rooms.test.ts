import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WsConnectionOptions } from './ws-connection';
import { createRoomHub } from './ws-rooms';

const ROOM_A = '11111111-1111-4111-8111-111111111111';
const ROOM_B = '22222222-2222-4222-8222-222222222222';
const IDLE_CLOSE_MS = 1_000;

const subscribe = (threadId: string) =>
  JSON.stringify({ type: 'room.subscribe', threadId });
const unsubscribe = (threadId: string) =>
  JSON.stringify({ type: 'room.unsubscribe', threadId });

function snapshot(threadId: string): string {
  return JSON.stringify({
    type: 'presence.snapshot',
    threadId,
    participants: [],
  });
}

function error(threadId: string | null, code: string): string {
  return JSON.stringify({
    type: 'error',
    threadId,
    code,
    message: 'm',
    traceId: 't',
  });
}

/** ws-connection을 대신하는 가짜 커넥션. 열림·프레임 도착·스스로 끝남을 테스트가 직접 일으킨다. */
interface FakeConnection {
  options: WsConnectionOptions;
  sent: string[];
  isOpen: boolean;
  close: ReturnType<typeof vi.fn<() => void>>;
  open: () => void;
  receive: (data: string) => void;
}

function hub() {
  const connections: FakeConnection[] = [];
  const openConnection = vi.fn((options: WsConnectionOptions) => {
    const connection: FakeConnection = {
      options,
      sent: [],
      isOpen: false,
      close: vi.fn<() => void>(),
      open: () => {
        connection.isOpen = true;
        options.onOpenChange?.(true);
      },
      receive: (data) => options.onMessage(data),
    };
    connections.push(connection);
    return {
      send: (data: string) => {
        if (!connection.isOpen) return false;
        connection.sent.push(data);
        return true;
      },
      close: connection.close,
    };
  });
  return {
    subscribeRoom: createRoomHub(openConnection, IDLE_CLOSE_MS).subscribeRoom,
    openConnection,
    connections,
  };
}

function listener() {
  return { onFrame: vi.fn(), onOpenChange: vi.fn() };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('createRoomHub', () => {
  it('여러 방이 커넥션 하나를 나눠 쓰고, 같은 방을 여러 화면이 들어도 서버 구독은 하나다', () => {
    const h = hub();
    h.subscribeRoom(ROOM_A, listener());
    h.subscribeRoom(ROOM_B, listener());
    h.subscribeRoom(ROOM_A, listener());

    expect(h.openConnection).toHaveBeenCalledOnce();
    // 아직 열리지 않았으니 보내지 못했고, 열리는 순간 이 목록이 구독된다(ws-connection.ts).
    expect(h.connections[0].options.rooms?.()).toEqual([ROOM_A, ROOM_B]);

    h.connections[0].open();
    const third = listener();
    h.subscribeRoom(ROOM_B, third);
    const late = listener();
    h.subscribeRoom('33333333-3333-4333-8333-333333333333', late);

    // 이미 구독한 방은 다시 보내지 않고, 새 방만 곧바로 건다.
    expect(h.connections[0].sent).toEqual([
      subscribe('33333333-3333-4333-8333-333333333333'),
    ]);
    // 이미 열린 커넥션에 붙은 화면은 곧바로 열림을 안다.
    expect(third.onOpenChange).toHaveBeenCalledWith(true);
    expect(late.onOpenChange).toHaveBeenCalledWith(true);
  });

  it('프레임을 threadId로 그 방 화면에만 넘기고, 전역 프레임은 모두에게, pong은 누구에게도 넘기지 않는다', () => {
    const h = hub();
    const a = listener();
    const b = listener();
    h.subscribeRoom(ROOM_A, a);
    h.subscribeRoom(ROOM_B, b);
    const connection = h.connections[0];
    connection.open();

    connection.receive(snapshot(ROOM_A));
    expect(a.onFrame).toHaveBeenCalledOnce();
    expect(b.onFrame).not.toHaveBeenCalled();

    connection.receive(error(null, 'MALFORMED_REQUEST'));
    expect(a.onFrame).toHaveBeenCalledTimes(2);
    expect(b.onFrame).toHaveBeenCalledOnce();

    connection.receive('{"type":"pong"}');
    connection.receive('not json');
    expect(a.onFrame).toHaveBeenCalledTimes(2);
    expect(b.onFrame).toHaveBeenCalledOnce();
  });

  it('서버가 구독을 잃었다고 알리면 그 방을 다시 걸고, 화면에도 그 프레임을 넘긴다', () => {
    const h = hub();
    const a = listener();
    h.subscribeRoom(ROOM_A, a);
    const connection = h.connections[0];
    connection.open();

    connection.receive(error(ROOM_A, 'NOT_SUBSCRIBED'));

    expect(connection.sent).toEqual([subscribe(ROOM_A)]);
    expect(a.onFrame).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'error', code: 'NOT_SUBSCRIBED' }),
    );

    // 듣는 화면이 없는 방이면 다시 걸 이유가 없다.
    connection.receive(error(ROOM_B, 'NOT_SUBSCRIBED'));
    expect(connection.sent).toEqual([subscribe(ROOM_A)]);
  });

  it('보내기는 방마다 구독 핸들로 하고, close() 뒤에는 보내지 않는다', () => {
    const h = hub();
    const room = h.subscribeRoom(ROOM_A, listener());
    h.connections[0].open();

    expect(room.send({ type: 'ping' })).toBe(true);
    room.close();
    expect(room.send({ type: 'ping' })).toBe(false);
    expect(h.connections[0].sent).toEqual([
      JSON.stringify({ type: 'ping' }),
      unsubscribe(ROOM_A),
    ]);
  });

  it('그 방의 마지막 화면이 떠나야 해지하고, 방이 모두 떠나면 유예 뒤에 커넥션을 닫는다', () => {
    vi.useFakeTimers();
    const h = hub();
    const first = h.subscribeRoom(ROOM_A, listener());
    const second = h.subscribeRoom(ROOM_A, listener());
    const connection = h.connections[0];
    connection.open();

    first.close();
    expect(connection.sent).toEqual([]);
    second.close();
    expect(connection.sent).toEqual([unsubscribe(ROOM_A)]);
    expect(connection.options.rooms?.()).toEqual([]);

    vi.advanceTimersByTime(IDLE_CLOSE_MS - 1);
    expect(connection.close).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(connection.close).toHaveBeenCalledOnce();

    // 닫힌 뒤의 구독은 새 커넥션을 연다.
    h.subscribeRoom(ROOM_B, listener());
    expect(h.openConnection).toHaveBeenCalledTimes(2);
  });

  it('방을 옮기는 동안(해지 직후 구독)은 커넥션을 닫지 않고 그대로 쓴다', () => {
    vi.useFakeTimers();
    const h = hub();
    const leaving = h.subscribeRoom(ROOM_A, listener());
    const connection = h.connections[0];
    connection.open();

    leaving.close();
    h.subscribeRoom(ROOM_B, listener());
    vi.advanceTimersByTime(IDLE_CLOSE_MS * 2);

    expect(connection.close).not.toHaveBeenCalled();
    expect(h.openConnection).toHaveBeenCalledOnce();
    expect(connection.sent).toEqual([unsubscribe(ROOM_A), subscribe(ROOM_B)]);
  });

  it('커넥션이 스스로 끝나면 버리고, 다음 구독이 새로 연다', () => {
    const h = hub();
    h.subscribeRoom(ROOM_A, listener());
    h.connections[0].open();

    h.connections[0].options.onEnd?.();
    h.subscribeRoom(ROOM_B, listener());

    expect(h.openConnection).toHaveBeenCalledTimes(2);
    expect(h.connections[1].options.rooms?.()).toEqual([ROOM_A, ROOM_B]);
  });
});
