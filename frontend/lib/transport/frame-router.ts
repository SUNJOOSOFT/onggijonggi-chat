/********************************************************
 파일명 : frame-router.ts (lib/transport)
 설 명 : 검증된 WsFrame을 type별 핸들러로 라우팅한다. switch가 frame.type을 exhaustive하게
 다뤄서, frames.ts의 WsFrame 유니온에 새 타입이 추가되는데 여기 case를 안 붙이면 default 분기의
 `never` 대입에서 컴파일 에러가 난다 — 프레임 타입 추가를 여기서 빠뜨리는 걸 막는 안전장치다.
 핸들러는 전부 선택적이다 — 소비자마다 관심 있는 프레임 종류가 다르기 때문이다(예: 1:1 채팅
 스트림 소비자는 chat.message/presence.join에 관심이 없다). 관심 없는 타입은 조용히 무시한다.
 *********************************************************/

import type {
  ChatAnswerFrame,
  ChatMessageFrame,
  PresenceJoinFrame,
  PresenceLeaveFrame,
  PresenceSnapshotFrame,
  SystemNoticeFrame,
  WsErrorFrame,
  WsFrame,
} from './frames';

export interface FrameHandlers {
  onChatAnswer?: (frame: ChatAnswerFrame) => void;
  onChatMessage?: (frame: ChatMessageFrame) => void;
  onPresenceJoin?: (frame: PresenceJoinFrame) => void;
  onPresenceLeave?: (frame: PresenceLeaveFrame) => void;
  onPresenceSnapshot?: (frame: PresenceSnapshotFrame) => void;
  onSystemNotice?: (frame: SystemNoticeFrame) => void;
  onError?: (frame: WsErrorFrame) => void;
}

export function routeFrame(frame: WsFrame, handlers: FrameHandlers): void {
  switch (frame.type) {
    case 'chat.answer':
      handlers.onChatAnswer?.(frame);
      return;
    case 'chat.message':
      handlers.onChatMessage?.(frame);
      return;
    case 'presence.join':
      handlers.onPresenceJoin?.(frame);
      return;
    case 'presence.leave':
      handlers.onPresenceLeave?.(frame);
      return;
    case 'presence.snapshot':
      handlers.onPresenceSnapshot?.(frame);
      return;
    case 'system.notice':
      handlers.onSystemNotice?.(frame);
      return;
    case 'error':
      handlers.onError?.(frame);
      return;
    default: {
      // frames.ts에 새 서브타입을 추가하고 여기 case를 안 채우면, frame이 never로 좁혀지지
      // 않아 이 대입에서 컴파일이 깨진다 — 의도된 안전장치이니 지우지 않는다.
      const exhaustiveCheck: never = frame;
      throw new Error(
        `Unhandled WsFrame type: ${JSON.stringify(exhaustiveCheck)}`,
      );
    }
  }
}
