/********************************************************
 파일명 : use-collab-room.ts (lib/collab)
 설 명 : 협업방 하나에 붙어 프레임을 상태로 접고, 메시지를 올려보내는 훅(이슈 #19).
 연결 유지·재연결·재로그인은 전부 ws-connection.ts(#4)의 몫이고 여기서 다시 하지 않는다.

 이 훅이 실제로 더하는 것은 화면이 알아야 하는 "지금 붙어 있나"의 네 단계다. 붙지 못한 이유를
 클라이언트가 알 수 없다는 제약(브라우저가 핸드셰이크 status를 안 준다, #4 주석) 때문에 처음
 연결이 오래 걸리는 것과 방 접근이 거부된 것이 똑같이 보인다 — 그래서 시간을 근거로 stalled를
 따로 두고, 화면은 단정하지 않는 문구로 안내한다. 인가 실패를 핸드셰이크 거부로 줄지 error
 프레임으로 줄지는 아직 #22에 미결이라 양쪽 다 대비해야 한다(프레임 쪽은 room-state.ts).
 *********************************************************/

import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import { type WsConnection, openWsConnection } from '@/lib/api/ws-connection';
import { parseFrameFromText } from '@/lib/transport/parse-frame';
import {
  type RoomState,
  applyFrame,
  clearRoomError,
  dismissNotice as dismissNoticeIn,
  initialRoomState,
  isForbidden,
  noticeMessage,
} from './room-state';

/** 첫 연결이 이만큼 지나도 열리지 않으면 화면이 "붙지 못하고 있다"고 말한다. #4의 백오프가
 * 0·1·3초에 재시도하므로, 5초면 세 번은 두드려 본 뒤다. */
const STALLED_MS = 5_000;

export type RoomConnection =
  /** 아직 한 번도 열리지 않았다. */
  | 'connecting'
  /** 열려 있다 — 메시지를 보낼 수 있다. */
  | 'open'
  /** 열렸다가 끊겼고 #4가 되살리는 중이다. 한 번 열렸으니 접근 거부는 아니다. */
  | 'reconnecting'
  /** 한 번도 열리지 못한 채 STALLED_MS가 지났다. 서버 문제일 수도, 방 접근 거부일 수도 있다. */
  | 'stalled';

export interface CollabRoom {
  state: RoomState;
  connection: RoomConnection;
  /** 메시지를 올려보낸다. 끊겨 있으면 보내지 않고 false — 화면이 그 자리에서 안내한다. */
  send: (content: string) => boolean;
  /** 방을 막지 않는 최신 오류 알림을 닫는다. */
  dismissError: () => void;
  /** 방 위에 얹힌 시스템 알림(#29) 하나를 닫는다. */
  dismissNotice: (code: string) => void;
}

export function useCollabRoom(threadId: string): CollabRoom {
  const [state, setState] = useState<RoomState>(initialRoomState);
  const [connection, setConnection] = useState<RoomConnection>('connecting');
  const connectionRef = useRef<WsConnection | null>(null);

  useEffect(() => {
    const ws = openWsConnection(threadId, {
      onMessage: (data) => {
        // 해석되지 않는 프레임은 parse-frame.ts가 null로 흘려보낸다 — 화면을 멈출 이유가 아니다.
        const frame = parseFrameFromText(data);
        if (frame !== null) {
          if (frame.type === 'error') {
            console.error(`[collab] WS error traceId=${frame.traceId}`);
          }
          // info 알림(#29)은 지나가도 되는 안내라 토스트로만 띄운다 — 상태에 남기지 않아
          // applyFrame이 그대로 흘려보낸다. warning은 반대로 배너로 남는다.
          if (frame.type === 'system.notice' && frame.severity === 'info') {
            toast.info(noticeMessage(frame.message));
          }
          setState((current) => applyFrame(current, frame));
        }
      },
      onOpenChange: (open) => setConnection(open ? 'open' : 'reconnecting'),
    });
    connectionRef.current = ws;

    return () => {
      ws.close();
      connectionRef.current = null;
    };
  }, [threadId]);

  // 거부를 통보받았으면 재연결을 멈춘다. #4의 루프는 끊긴 이유를 모르니 그대로 두면 권한 없는
  // 방을 계속 두드리게 되고, 그 시도는 핸드셰이크 레이트리밋(#6)에도 그대로 쌓인다.
  useEffect(() => {
    if (isForbidden(state.error)) connectionRef.current?.close();
  }, [state.error]);

  useEffect(() => {
    if (connection !== 'connecting') return;
    const timer = setTimeout(() => setConnection('stalled'), STALLED_MS);
    return () => clearTimeout(timer);
  }, [connection]);

  const send = useCallback((content: string) => {
    const frame = {
      type: 'chat.message',
      content,
    };
    return connectionRef.current?.send(JSON.stringify(frame)) ?? false;
  }, []);

  const dismissError = useCallback(
    () => setState((current) => clearRoomError(current)),
    [],
  );

  const dismissNotice = useCallback(
    (code: string) => setState((current) => dismissNoticeIn(current, code)),
    [],
  );

  return { state, connection, send, dismissError, dismissNotice };
}
