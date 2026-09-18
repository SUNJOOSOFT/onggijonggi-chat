/********************************************************
 파일명 : use-room.ts (lib/chat)
 설 명 : 방 하나에 붙어 프레임을 상태로 접고, 메시지를 올려보내는 훅(이슈 #19).
 연결 유지·재연결·재로그인은 전부 ws-connection.ts(#4)의 몫이고 여기서 다시 하지 않는다. 커넥션은
 탭이 공유하고(ws-rooms.ts, 이슈 #161) 이 훅은 그 위에 방 하나를 구독한다 — 화면을 떠나도 커넥션은
 남고 이 방 구독만 풀린다.

 협업방과 1:1 방이 이 훅을 함께 쓴다. 둘의 차이는 옵션 두 개로 좁혀진다(UseRoomOptions) —
 이력을 어느 엔드포인트에서 읽는지, 그리고 처음부터 구독을 걸지 첫 발화가 bootstrap을 겸할지다.
 나머지(최초 연결 따라잡기 #208, 재연결 따라잡기 #190, 커서 오염 방지, stalled 판정)는 방 종류와
 무관해서 한 벌만 둔다 — 복사해 두면 같은 함정을 두 곳에서 각각 밟게 된다.

 이 훅이 실제로 더하는 것은 화면이 알아야 하는 "지금 붙어 있나"의 네 단계다. 붙지 못한 이유를
 클라이언트가 알 수 없다는 제약(브라우저가 핸드셰이크 status를 안 준다, #4 주석) 때문에 처음
 연결이 오래 걸리는 것과 방 접근이 거부된 것이 똑같이 보인다 — 그래서 시간을 근거로 stalled를
 따로 두고, 화면은 단정하지 않는 문구로 안내한다. 인가 실패를 핸드셰이크 거부로 줄지 error
 프레임으로 줄지는 아직 #22에 미결이라 양쪽 다 대비해야 한다(프레임 쪽은 room-state.ts).
 *********************************************************/

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import type { ThreadMessageItem } from '@/lib/api/thread-history';
import {
  type RoomListenSubscription,
  type RoomListener,
  type RoomSubscription,
  listenRoom,
  subscribeRoom,
} from '@/lib/api/ws-rooms';
import type { ClientFrame } from '@/lib/transport/frames';
import { generateUUID } from '@/lib/utils';
import {
  type RoomState,
  advanceCursor,
  applyFrame,
  applyHistory,
  clearRoomError,
  dismissNotice as dismissNoticeIn,
  initialRoomState,
  isForbidden,
  isPresenceNotice,
  noticeMessage,
} from '@/lib/chat/room-state';

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

/**
 * 방 종류가 갈리는 두 지점. 나머지는 협업방과 1:1이 완전히 같아서 옵션으로 두지 않는다.
 */
export interface UseRoomOptions {
  /**
   * 이력 조회. 방 종류마다 엔드포인트만 다르다(이슈 #190). afterSeq를 주면 그보다 큰 것만
   * 돌려줘야 한다 — 재연결 따라잡기가 그 계약에 기댄다.
   *
   * 실패는 예외로 알린다. 훅은 이력을 못 얻어도 빈 흐름으로 계속 간다 — 과거를 못 읽는 것이
   * 방을 못 여는 이유는 아니기 때문이다. "못 여는 실패"인지는 호출부가 onHistoryError에서 가른다.
   */
  fetchHistory: (
    threadId: string,
    afterSeq?: number,
  ) => Promise<ThreadMessageItem[]>;
  /**
   * 서버가 이미 아는 방이면 true(기본) — 곧바로 room.subscribe를 건다.
   *
   * 새 1:1 draft는 false로 둔다(이슈 #162, §2.1). 아직 존재하지 않는 방이라 구독을 걸 수 없고,
   * 첫 chat.message가 방 생성을 겸한다. 서버가 그때 이 커넥션을 자동 구독시키므로, 자기
   * clientMsgId가 실린 에코를 본 뒤에 로컬 장부만 승격하면 된다(두 번째 room.subscribe를
   * 보내지 않는다).
   */
  startPromoted?: boolean;
  /** 이력 조회가 실패했을 때. 404·403처럼 "방을 못 연다"로 읽을지는 호출부가 정한다. */
  onHistoryError?: (error: unknown) => void;
}

export interface Room {
  state: RoomState;
  connection: RoomConnection;
  /**
   * 메시지를 올려보낸다. 보냈으면 이 발화에 붙인 식별자를, 끊겨 있어 보내지 못했으면 null을
   * 돌려준다 — 화면이 그 자리에서 안내한다.
   *
   * clientMsgId는 서버가 에코에 돌려주는 임시 메시지 id이고, turnId는 이 발화가 부른 AI 턴의
   * 답변·대기 프레임에 돌려주는 값이자 cancel로 그 턴을 멈출 때 쓰는 값이다(이슈 #160). model을 주면
   * @AI 멘션일 때 그 모델로 답한다.
   *
   * reuseClientMsgId를 주면 새로 만들지 않고 그 값을 그대로 쓴다(이슈 #233) — WS 전송 확인을
   * 잃은 재시도가 서버 idempotency(DirectChatTurnService, DIRECT 전용)에 걸리려면 clientMsgId가
   * 원래 시도와 같아야 한다. turnId는 재시도여도 항상 새로 만든다 — 재접속 응답은 이 새
   * turnId로 이 화면에만 유니캐스트된다.
   */
  send: (
    content: string,
    model?: string,
    reuseClientMsgId?: string,
  ) => { clientMsgId: string; turnId: string } | null;
  /** 이 화면(이 커넥션)에서 보낸 발화가 부른 @AI 턴을 멈춘다(이슈 #160). 실제로 멈췄는지는
   * chat.answer(done)나 chat.queued(cancelled)로 온다 — 이미 끝난 턴이면 아무것도 오지 않는다.
   * 끊겨 있으면 false. */
  cancel: (turnId: string) => boolean;
  /**
   * 지금 중지할 수 있는 턴, 없으면 null. 흐르는 중인 AI 답변 중 이 화면이 부른 것만 고른다 —
   * 방에는 남이 부른 턴도 함께 흐르고, 서버는 자기 커넥션의 취소만 인정하기 때문이다.
   */
  cancellableTurnId: string | null;
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

export function useRoom(threadId: string, options: UseRoomOptions): Room {
  const { fetchHistory, startPromoted = true, onHistoryError } = options;
  const [state, setState] = useState<RoomState>(initialRoomState);
  const [connection, setConnection] = useState<RoomConnection>('connecting');
  const subscriptionRef = useRef<
    RoomSubscription | RoomListenSubscription | null
  >(null);

  /**
   * 콜백·옵션을 ref로 받아 둔다. 구독 이펙트는 threadId로만 다시 돌아야 하는데(방을 바꾸지
   * 않았는데 재구독하면 그때마다 따라잡기가 다시 돈다), 호출부가 매 렌더 새 함수를 넘겨도
   * 안전해야 한다.
   */
  const fetchHistoryRef = useRef(fetchHistory);
  fetchHistoryRef.current = fetchHistory;
  const onHistoryErrorRef = useRef(onHistoryError);
  onHistoryErrorRef.current = onHistoryError;
  /** 아직 에코를 못 받은 내 clientMsgId. bootstrap 승격 판단에만 쓰고 받는 즉시 지운다. */
  const pendingEchoesRef = useRef<Set<string>>(new Set());
  /** 따라잡기 요청에 실을 커서. WS 콜백이 최신 값을 봐야 해서 ref로 따로 둔다. */
  const lastSeqRef = useRef<number | null>(null);
  const [participantsRevision, setParticipantsRevision] = useState(0);

  /**
   * 이력 로딩(REST)과 WS 연결을 한 이펙트에 묶는다. 원래는 둘로 나뉘어 있었지만(#190),
   * 최초 연결 따라잡기(#208)가 "이력이 끝났는가"와 "WS가 한 번이라도 열렸는가"를 서로
   * 넘겨다봐야 해서 조율 상태가 필요해졌다 — 그 상태(`historyLoaded`·`wsOpenedOnce`·
   * `initialCatchUpFired`·`alive`)를 훅 최상단의 ref로 공유하면 StrictMode 이중 마운트나
   * 빠른 재마운트에서 이전 인스턴스의 값이 새 인스턴스로 새는 문제가 생긴다(ref는 인스턴스가
   * 갈려도 안 갈린다). 이 값들은 "이번 마운트에서만" 의미가 있으므로 이펙트 클로저의 지역
   * 변수로 두는 편이 맞다 — 재연결(`reconnected`)이 원래 그렇게 돼 있던 이유와 같다.
   * 그래서 두 이펙트를 하나로 합쳐 전부 지역 변수로 둔다.
   */
  useEffect(() => {
    let alive = true;
    let historyLoaded = false;
    let wsOpenedOnce = false;
    let initialCatchUpFired = false;
    // 재연결의 따라잡기는 최초 연결 따라잡기와 별개다 — 여기서 세는 것은 "그 뒤에 다시
    // 붙었는가"뿐이다(이슈 #190).
    let reconnected = false;

    // 이 마운트가 쓸 이력 조회를 여기서 한 번 집어 든다 — 이펙트의 다른 지역 변수와 수명을
    // 맞추려는 것이다. 호출부가 매 렌더 새 함수를 넘겨도 재구독이 일어나지 않는다.
    const fetchHistoryNow = fetchHistoryRef.current;

    /**
     * 최초 연결 따라잡기(#208). 이력 로딩과 WS open 둘 다 끝난 바로 그 순간 커서 있는
     * 따라잡기를 딱 한 번 쏜다 — 어느 쪽이 먼저 끝나든 상관없다.
     *
     * 재연결과 합치지 않는 이유: 이력이 아직 안 끝난 채 WS가 열리면 커서가 아직 null이다.
     * 재연결과 똑같이 그 자리에서 바로 쏘면 커서 없는 전체 재조회가 되고, 방이 클수록(=이력
     * REST가 느릴수록 WS가 먼저 열릴 확률도 같이 오른다) 그 비용이 커진다. 그래서 이력이
     * 아직이면 여기서 안 쏘고, 이력이 끝나 커서가 확정되는 순간 다시 불려 그때 쏜다.
     */
    function maybeFireInitialCatchUp() {
      if (initialCatchUpFired) return;
      if (!historyLoaded || !wsOpenedOnce) return;
      initialCatchUpFired = true;
      console.info(
        `[#208][room] 최초 연결 따라잡기 발동 threadId=${threadId} cursor=${lastSeqRef.current ?? 'none'}`,
      );
      catchUp();
    }

    /**
     * 과거 대화는 진입할 때 REST로 한 번만 불러온다(이슈 #190). 이후의 실시간은 아래 WS가
     * 맡는다.
     *
     * WS 연결을 기다리지 않고 나란히 시작한다 — 둘이 겹쳐 도착해도 applyHistory가 msgId로
     * 걸러 한 번만 남기므로 한쪽을 늦출 이유가 없다. 이력을 못 얻는 것은 방을 못 열 이유가
     * 아니라, 실패해도 빈 흐름으로 계속 간다.
     */
    fetchHistoryNow(threadId)
      .then((items) => {
        // lastSeqRef는 보통 아래 lastSeq 동기화 이펙트가 다음 렌더에서 채운다(#208 이전부터
        // 있던 별도 이펙트). 하지만 maybeFireInitialCatchUp은 바로 이어지는 .finally()에서
        // 같은 틱에 불릴 수 있어 그 렌더를 기다리지 못한다 — applyHistory와 같은 규칙
        // (advanceCursor)으로 여기서 미리 계산해 둔다. state.lastSeq가 실제로 반영하는
        // 값과는 다음 렌더에서 맞춰진다.
        //
        // alive로 반드시 감싼다 — lastSeqRef는 훅 전체가 공유하는 ref라서, 이미 정리된(예:
        // StrictMode 이중 마운트의 첫 번째) 인스턴스가 뒤늦게 이 줄을 실행하면 살아있는
        // 인스턴스가 나중에 읽을 커서를 영구히 오염시킨다(advanceCursor는 값을 절대 되돌리지
        // 않으므로 한 번 부풀려지면 이후 진짜 값이 와도 안 줄어든다) — setState 못지않게
        // 위험하다.
        if (alive) {
          lastSeqRef.current = items.reduce(
            (cursor, item) => advanceCursor(cursor, item.seq),
            lastSeqRef.current,
          );
          setState((current) => applyHistory(current, items));
        }
      })
      .catch((error) => {
        console.error('[room] 이력을 불러오지 못했습니다', error);
        // 훅은 빈 흐름으로 계속 간다. 이 실패가 "방을 못 연다"인지(404·403)는 호출부만
        // 판단할 수 있어 그대로 넘긴다 — alive로 감싸 떠난 화면에는 알리지 않는다.
        if (alive) onHistoryErrorRef.current?.(error);
      })
      .finally(() => {
        // 성공이든 실패든 "이력 단계는 끝났다"로 친다(#208) — 실패했는데 여기서 안 켜면
        // 이력이 계속 실패하는 방에서는 최초 따라잡기가 영영 안 켜진다. 커서가 없으니
        // 사실상 전체 재조회 재시도가 되고, 그게 여기서는 안전망이다.
        historyLoaded = true;
        maybeFireInitialCatchUp();
      });

    /**
     * 끊겼다 다시 붙으면 그 동안 오간 메시지를 따라잡는다(이슈 #190). 방송은 그 순간 붙어
     * 있는 연결에만 가고 다시 틀어주지 않아, 재연결만으로는 구멍이 그대로 남는다.
     *
     * 마지막으로 받은 seq 이후만 요청한다 — "빠진 번호를 기다린다"가 아니다. 서버가 seq를
     * 블록으로 예약해 쓰지 않은 번호가 구멍으로 남으므로, 다음 번호를 기다리면 영영 멈춘다.
     * 아직 하나도 못 받았으면(null) 커서 없이 전부 받는다.
     */
    const catchUp = () => {
      fetchHistoryNow(threadId, lastSeqRef.current ?? undefined)
        .then((items) => {
          if (alive) setState((current) => applyHistory(current, items));
        })
        .catch((error) => {
          console.error('[room] 끊긴 동안의 대화를 따라잡지 못했습니다', error);
        });
    };

    // bootstrap 중이면 아직 서버가 모르는 방이라 구독을 걸지 않는다(이슈 #162, §2.1) — 듣기만
    // 하다가 자기 발화의 에코를 보고 승격한다. 이 마운트에서만 의미가 있어 지역 변수로 둔다.
    let promoted = startPromoted;

    const listener: RoomListener = {
      onFrame: (frame) => {
        // 내 발화가 서버에 닿았다는 증거다 — 서버가 bootstrap 성공 시 이 커넥션을 이미
        // 자동 구독시켰으므로, 여기서는 로컬 장부만 승격한다(room.subscribe를 또 보내지 않는다).
        if (frame.type === 'chat.message' && frame.clientMsgId !== null) {
          if (pendingEchoesRef.current.delete(frame.clientMsgId) && !promoted) {
            promoted = true;
            const current = subscriptionRef.current;
            if (current !== null && 'promote' in current) current.promote();
          }
        }
        if (frame.type === 'error') {
          console.error(`[room] WS error traceId=${frame.traceId}`);
          // 서버가 이 방 구독을 잃었다(이슈 #161). 다시 거는 것은 허브가 이미 했고, 그 사이의
          // 구멍은 재연결과 같은 방법으로 메운다.
          if (frame.code === 'NOT_SUBSCRIBED') catchUp();
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
      },
      onOpenChange: (open) => {
        setConnection(open ? 'open' : 'reconnecting');
        if (!open) {
          reconnected = true;
          return;
        }
        // 최초 연결 따라잡기(#208) — 이 open이 이 마운트에서 처음이면 wsOpenedOnce를 세우고
        // maybeFireInitialCatchUp에 판단을 맡긴다. reconnected는 아직 false라 바로 아래
        // 재연결 분기는 여기서 안 탄다 — 같은 open에서 두 따라잡기가 겹쳐 쏘지 않는다.
        if (!wsOpenedOnce) {
          wsOpenedOnce = true;
          maybeFireInitialCatchUp();
        }
        if (!reconnected) return;
        reconnected = false;
        catchUp();
      },
    };

    // subscriptionRef를 먼저 비워 둔다 — listenRoom·subscribeRoom은 커넥션이 이미 열려 있으면
    // 그 자리에서 onOpenChange를 부르므로, 대입 전에 콜백이 먼저 돌 수 있다.
    subscriptionRef.current = null;
    const subscription = promoted
      ? subscribeRoom(threadId, listener)
      : listenRoom(threadId, listener);
    subscriptionRef.current = subscription;

    return () => {
      alive = false;
      subscription.close();
      subscriptionRef.current = null;
    };
  }, [threadId, startPromoted]);

  useEffect(() => {
    lastSeqRef.current = state.lastSeq;
  }, [state.lastSeq]);

  // 거부를 통보받았으면 이 방 구독을 푼다 — 커넥션은 다른 방과 함께 쓰므로 닫지 않는다(이슈 #161).
  // 구독을 남겨 두면 재연결할 때마다 권한 없는 방을 다시 두드린다.
  useEffect(() => {
    if (isForbidden(state.error)) subscriptionRef.current?.close();
  }, [state.error]);

  useEffect(() => {
    if (connection !== 'connecting') return;
    const timer = setTimeout(() => setConnection('stalled'), STALLED_MS);
    return () => clearTimeout(timer);
  }, [connection]);

  const sendFrame = useCallback(
    (frame: ClientFrame) => subscriptionRef.current?.send(frame) ?? false,
    [],
  );

  // 내가 시작한 턴만 기억한다. 서버는 그 턴을 시작한 커넥션이 보낸 취소만 인정하므로
  // (CollabMessageDispatcher.ignoresACancelFromAnotherConnection), 남의 답변에 중지 버튼을
  // 달아도 눌리기만 할 뿐 아무 일도 일어나지 않는다.
  const myTurnIdsRef = useRef<Set<string>>(new Set());

  const send = useCallback(
    (content: string, model?: string, reuseClientMsgId?: string) => {
      const ids = {
        clientMsgId: reuseClientMsgId ?? generateUUID(),
        turnId: generateUUID(),
      };
      const sent = sendFrame({
        type: 'chat.message',
        threadId,
        content,
        model,
        ...ids,
      });
      if (!sent) return null;
      myTurnIdsRef.current.add(ids.turnId);
      // bootstrap 승격은 "내가 보낸 발화의 에코"만 근거로 삼는다(이슈 #162, §2.1) — 남의
      // 발화 에코로 승격하면 서버가 이 커넥션을 구독시켰다는 보장이 없다. 이미 승격된
      // 방에서도 담아 두지만 onFrame이 곧바로 지우므로 쌓이지 않는다.
      pendingEchoesRef.current.add(ids.clientMsgId);
      return ids;
    },
    [sendFrame, threadId],
  );

  const cancel = useCallback(
    (turnId: string) => sendFrame({ type: 'chat.cancel', threadId, turnId }),
    [sendFrame, threadId],
  );

  /**
   * 지금 중지할 수 있는 턴. 흐르는 중인 AI 답변 중 내가 부른 것을 뒤에서 찾는다 — 방에는 남이
   * 부른 턴도 함께 흐르기 때문이다(이슈 #160).
   *
   * 아직 첫 delta가 오지 않은 턴은 말풍선이 없어 여기 잡히지 않는다. 그 구간을 취소하려면
   * chat.queued까지 상태로 들고 있어야 하는데, 눌러도 화면에 멈출 것이 없는 짧은 구간이라
   * 두지 않았다.
   */
  const cancellableTurnId = useMemo(() => {
    for (let index = state.messages.length - 1; index >= 0; index -= 1) {
      const entry = state.messages[index];
      if (isPresenceNotice(entry)) continue;
      if (!entry.streaming || entry.from !== null) continue;
      if (entry.turnId !== null && myTurnIdsRef.current.has(entry.turnId)) {
        return entry.turnId;
      }
    }
    return null;
  }, [state.messages]);

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
    cancel,
    cancellableTurnId,
    dismissError,
    dismissNotice,
    participantsRevision,
  };
}
