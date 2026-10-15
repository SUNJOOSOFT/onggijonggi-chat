/********************************************************
 파일명 : ws-rooms.ts (lib/api)
 설 명 : 탭 하나의 WS 커넥션 하나를 여러 방이 나눠 쓰게 하는 허브(이슈 #161).

 커넥션은 페이지가 아니라 이 모듈의 싱글턴이 든다 — 방을 옮겨도 새로 붙지 않아 핸드셰이크를 다시
 하지 않고(레이트리밋에도 안 쌓인다), 안 보는 방의 알림을 받을 자리가 생긴다. 연결 유지·재연결·
 재로그인은 여전히 ws-connection.ts의 몫이고, 여기서 더하는 것은 셋이다.

   - 방별 구독 관리. 같은 방을 여러 화면이 들어도 서버 구독은 하나다. 마지막 화면이 떠나면 해지하고,
     재연결 뒤 다시 걸 방 목록(rooms)을 ws-connection에 대 준다.
   - 프레임 demux. threadId로 그 방 화면에만 넘긴다. threadId가 없는 프레임(커넥션 전역 오류)은 모든
     방 화면에 넘긴다. pong은 연결 계층의 일이라 화면에 넘기지 않는다.
   - 서버가 구독을 잃었다고 알리면(NOT_SUBSCRIBED) 그 방을 곧바로 다시 건다. 놓친 대화를 따라잡는
     것은 같은 프레임을 받은 화면이 한다 — 커서(lastSeq)를 든 쪽이 화면이다.

 열린 방이 하나도 없으면 IDLE_CLOSE_MS 뒤 커넥션을 닫는다. 방 A에서 B로 옮기는 동안(해지 직후 구독)은
 닫지 않고, 채팅과 무관한 화면이나 로그아웃 뒤까지 소켓을 붙들고 있지도 않는다.
 *********************************************************/

import type { ClientFrame, WsFrame } from '../transport/frames';
import { parseFrameFromText } from '../transport/parse-frame';
import { type WsConnection, openWsConnection } from './ws-connection';

/** 마지막 방이 떠난 뒤 커넥션을 닫기까지 기다리는 시간. */
export const IDLE_CLOSE_MS = 30_000;

/** 서버가 이 커넥션에 그 방 구독이 없다고 알리는 코드(CollabWebSocketHandler.notSubscribed). */
const NOT_SUBSCRIBED_CODE = 'NOT_SUBSCRIBED';

export interface RoomListener {
  /** 이 방의 프레임, 그리고 어느 방에도 속하지 않는 커넥션 전역 프레임. */
  onFrame: (frame: WsFrame) => void;
  /** 커넥션이 열리고 끊길 때마다 불린다. 구독하는 순간 이미 열려 있으면 곧바로 true로 한 번 불린다. */
  onOpenChange?: (open: boolean) => void;
}

export interface RoomSubscription {
  /** 프레임 하나를 보낸다. 끊겨 있거나 이미 close()했으면 보내지 않고 false(ws-connection.ts의 send). */
  send: (frame: ClientFrame) => boolean;
  /** 이 화면의 구독을 푼다. 그 방을 듣는 화면이 더 없으면(활성 구독이었으면) 서버 구독도 해지한다. */
  close: () => void;
}

/**
 * DIRECT bootstrap 전용 구독(이슈 #162, §2.1). listenRoom은 수신 리스너와 공유 커넥션의
 * idle-close 방지만 하고, room.subscribe를 보내지 않으며 재연결·NOT_SUBSCRIBED 자동 재구독
 * 대상에도 넣지 않는다 — 서버가 아직 이 방을 모르기 때문이다(빈 draft일 수 있다).
 */
export interface RoomListenSubscription {
  /** 프레임 하나를 보낸다 — 구독 여부와 무관하게 보낼 수 있다(첫 chat.message는 구독 없이도 허용). */
  send: (frame: ClientFrame) => boolean;
  /**
   * 자기 clientMsgId가 든 chat.message 에코를 받은 뒤 부른다. 두 번째 room.subscribe를 보내지
   * 않는다 — 서버가 bootstrap 성공 시 이미 이 연결을 자동 구독해뒀다(§2.1). 이 호출은 로컬
   * 장부에서만 "재연결 대상·NOT_SUBSCRIBED 자동 재구독 대상"으로 승격한다.
   */
  promote: () => void;
  /** 이 화면의 구독을 푼다. 승격 전이었으면 로컬 장부에서만 지운다(서버에 구독한 적이 없다). */
  close: () => void;
}

interface RoomEntry {
  listeners: Set<RoomListener>;
  /** true면 room.subscribe를 걸었고 재연결·NOT_SUBSCRIBED 복구 대상이다. false는 listenRoom이
   * 아직 promote되지 않은 상태(이슈 #162) — 서버가 이 방을 모르므로 그 무엇도 재구독하지 않는다. */
  active: boolean;
}

export function createRoomHub(
  openConnection: typeof openWsConnection = openWsConnection,
  idleCloseMs = IDLE_CLOSE_MS,
) {
  const rooms = new Map<string, RoomEntry>();
  let connection: WsConnection | null = null;
  let isOpen = false;
  let idleTimer: ReturnType<typeof setTimeout> | null = null;

  const sendFrame = (frame: ClientFrame): boolean =>
    connection?.send(JSON.stringify(frame)) ?? false;

  const route = (data: string) => {
    // 해석되지 않는 프레임은 parse-frame.ts가 null로 흘려보낸다 — 화면을 멈출 이유가 아니다.
    const frame = parseFrameFromText(data);
    // pong과 room.subscribed는 커넥션 계층(ws-connection.ts)의 신호라 화면에 넘기지 않는다.
    if (
      frame === null ||
      frame.type === 'pong' ||
      frame.type === 'room.subscribed'
    )
      return;
    const { threadId } = frame;
    const entry = threadId === null ? undefined : rooms.get(threadId);
    if (
      frame.type === 'error' &&
      frame.code === NOT_SUBSCRIBED_CODE &&
      threadId !== null &&
      entry?.active
    ) {
      sendFrame({ type: 'room.subscribe', threadId });
    }
    const targets =
      threadId === null
        ? [...rooms.values()].flatMap((entry) => [...entry.listeners])
        : [...(entry?.listeners ?? [])];
    for (const listener of targets) listener.onFrame(frame);
  };

  const ensureConnection = () => {
    if (connection !== null) return;
    const opened = openConnection({
      onMessage: route,
      // 재연결 뒤 다시 걸 방은 active뿐이다 — listenRoom(미승격)은 서버가 아직 몰라 대상이 아니다.
      rooms: () =>
        [...rooms.entries()]
          .filter(([, entry]) => entry.active)
          .map(([threadId]) => threadId),
      onOpenChange: (open) => {
        isOpen = open;
        for (const entry of rooms.values()) {
          for (const listener of entry.listeners) listener.onOpenChange?.(open);
        }
      },
      // 스스로 끝난 커넥션(정상 종료·재로그인)은 다시 살아나지 않는다 — 다음 구독이 새로 열게 버린다.
      onEnd: () => {
        if (connection !== opened) return;
        connection = null;
        isOpen = false;
      },
    });
    connection = opened;
  };

  const clearIdleTimer = () => {
    if (idleTimer === null) return;
    clearTimeout(idleTimer);
    idleTimer = null;
  };

  const scheduleIdleCloseIfEmpty = () => {
    if (rooms.size > 0) return;
    idleTimer = setTimeout(() => {
      idleTimer = null;
      connection?.close();
      connection = null;
      isOpen = false;
    }, idleCloseMs);
  };

  const subscribeRoom = (
    threadId: string,
    listener: RoomListener,
  ): RoomSubscription => {
    clearIdleTimer();
    ensureConnection();

    let entry = rooms.get(threadId);
    if (entry === undefined) {
      entry = { listeners: new Set(), active: true };
      rooms.set(threadId, entry);
      // 열려 있으면 지금 건다. 아직 열리지 않았으면 보내지지 않고(false), 열릴 때 rooms()로 걸린다.
      sendFrame({ type: 'room.subscribe', threadId });
    }
    entry.listeners.add(listener);
    if (isOpen) listener.onOpenChange?.(true);

    let closed = false;
    return {
      send: (frame) => !closed && sendFrame(frame),
      close: () => {
        if (closed) return;
        closed = true;
        const current = rooms.get(threadId);
        if (current === undefined) return;
        current.listeners.delete(listener);
        if (current.listeners.size > 0) return;
        rooms.delete(threadId);
        if (current.active) sendFrame({ type: 'room.unsubscribe', threadId });
        scheduleIdleCloseIfEmpty();
      },
    };
  };

  /** DIRECT bootstrap 전용 — room.subscribe를 보내지 않고 커넥션·리스너만 연다(이슈 #162). */
  const listenRoom = (
    threadId: string,
    listener: RoomListener,
  ): RoomListenSubscription => {
    clearIdleTimer();
    ensureConnection();

    let entry = rooms.get(threadId);
    if (entry === undefined) {
      entry = { listeners: new Set(), active: false };
      rooms.set(threadId, entry);
    }
    entry.listeners.add(listener);
    if (isOpen) listener.onOpenChange?.(true);

    let closed = false;
    return {
      send: (frame) => !closed && sendFrame(frame),
      promote: () => {
        const current = rooms.get(threadId);
        if (current !== undefined) current.active = true;
      },
      close: () => {
        if (closed) return;
        closed = true;
        const current = rooms.get(threadId);
        if (current === undefined) return;
        current.listeners.delete(listener);
        if (current.listeners.size > 0) return;
        rooms.delete(threadId);
        // active로 승격된 뒤 닫히는 것도 정상 흐름이라(#162 승격 후 화면 이탈), 그 경우만 해지한다.
        if (current.active) sendFrame({ type: 'room.unsubscribe', threadId });
        scheduleIdleCloseIfEmpty();
      },
    };
  };

  return { subscribeRoom, listenRoom };
}

const sharedHub = createRoomHub();

/** 탭 하나가 공유하는 허브로 방 하나를 구독한다. */
export const subscribeRoom = sharedHub.subscribeRoom;

/** 탭 하나가 공유하는 허브로 DIRECT bootstrap 전용 리스너를 연다(이슈 #162). */
export const listenRoom = sharedHub.listenRoom;
