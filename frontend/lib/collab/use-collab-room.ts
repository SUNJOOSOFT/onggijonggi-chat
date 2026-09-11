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
import { fetchCollabMessages } from '@/lib/api/collab';
import { type WsConnection, openWsConnection } from '@/lib/api/ws-connection';
import { parseFrameFromText } from '@/lib/transport/parse-frame';
import {
  type RoomState,
  applyFrame,
  applyHistory,
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
  /**
   * 참여자 명단이 바뀌었다는 통보를 받을 때마다 1씩 오르는 눈금(이슈 #129·#172). 명단 자체는
   * 이 훅이 들지 않는다 — REST로 읽는 참여자 시트가 들고, 이 값은 그쪽에 "다시 불러와"만
   * 전한다. 값의 크기에는 의미가 없다.
   */
  participantsRevision: number;
}

export function useCollabRoom(threadId: string): CollabRoom {
  const [state, setState] = useState<RoomState>(initialRoomState);
  const [connection, setConnection] = useState<RoomConnection>('connecting');
  const connectionRef = useRef<WsConnection | null>(null);
  /** 따라잡기 요청에 실을 커서. WS 콜백이 최신 값을 봐야 해서 ref로 따로 둔다. */
  const lastSeqRef = useRef<number | null>(null);
  const [participantsRevision, setParticipantsRevision] = useState(0);

  /**
   * 과거 대화는 진입할 때 REST로 한 번만 불러온다(이슈 #190). 이후의 실시간은 아래 WS가 맡는다.
   *
   * WS 연결을 기다리지 않고 나란히 시작한다 — 둘이 겹쳐 도착해도 applyHistory가 msgId로 걸러
   * 한 번만 남기므로 한쪽을 늦출 이유가 없다. 이력을 못 얻는 것은 방을 못 열 이유가 아니라,
   * 실패해도 빈 흐름으로 계속 간다.
   */
  useEffect(() => {
    let alive = true;
    fetchCollabMessages(threadId)
      .then((items) => {
        if (alive) setState((current) => applyHistory(current, items));
      })
      .catch((error) => {
        console.error('[collab] 이력을 불러오지 못했습니다', error);
      });
    return () => {
      alive = false;
    };
  }, [threadId]);

  useEffect(() => {
    lastSeqRef.current = state.lastSeq;
  }, [state.lastSeq]);

  useEffect(() => {
    // 첫 연결은 위의 진입 이력이 이미 맡았다. 여기서 세는 것은 "그 뒤에 다시 붙었는가"다.
    let reconnected = false;
    let alive = true;

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
          // 참여자 명단은 이 훅의 상태가 아니라 REST를 읽는 참여자 시트가 들고 있다.
          // 값을 세지 않고 눈금만 올려, 시트가 "다시 불러오라"는 신호로만 쓰게 한다 —
          // action으로 분기할 이유가 없다(어느 액션이든 답은 재조회다).
          if (frame.type === 'participant.changed') {
            setParticipantsRevision((current) => current + 1);
          }
          setState((current) => applyFrame(current, frame));
        }
      },
      /**
       * 끊겼다 다시 붙으면 그 동안 오간 메시지를 따라잡는다(이슈 #190). 방송은 그 순간 붙어
       * 있는 연결에만 가고 다시 틀어주지 않아, 재연결만으로는 구멍이 그대로 남는다.
       *
       * 마지막으로 받은 seq 이후만 요청한다 — "빠진 번호를 기다린다"가 아니다. 서버가 seq를
       * 블록으로 예약해 쓰지 않은 번호가 구멍으로 남으므로, 다음 번호를 기다리면 영영 멈춘다.
       * 아직 하나도 못 받았으면(null) 커서 없이 전부 받는다.
       */
      onOpenChange: (open) => {
        setConnection(open ? 'open' : 'reconnecting');
        if (!open) {
          reconnected = true;
          return;
        }
        if (!reconnected) return;
        reconnected = false;
        fetchCollabMessages(threadId, lastSeqRef.current ?? undefined)
          .then((items) => {
            if (alive) setState((current) => applyHistory(current, items));
          })
          .catch((error) => {
            console.error('[collab] 끊긴 동안의 대화를 따라잡지 못했습니다', error);
          });
      },
    });
    connectionRef.current = ws;

    return () => {
      alive = false;
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

  return {
    state,
    connection,
    send,
    dismissError,
    dismissNotice,
    participantsRevision,
  };
}
