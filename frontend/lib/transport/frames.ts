/********************************************************
 파일명 : frames.ts (lib/transport)
 설 명 : 서버 WsFrame(backend WsFrame.java, 이슈 #9) 계약을 그대로 미러링한 판별 유니온.
 type 태그는 Jackson @JsonTypeInfo(property = "type")가 쓰는 이름과 정확히 같아야 한다.
 필드명도 Java record 컴포넌트명을 그대로 따른다(커스텀 네이밍 전략 없음 → camelCase 그대로 직렬화).
 *********************************************************/

import type { Citation } from '@/lib/api/chat';

/**
 * 답변 스트리밍 패킷 — chat.token/chat.done/(제안했던)chat.citation 세 개를 흡수한 통합 타입
 * (이슈 #10 코멘트, bsjSunjoo 확정 스펙). 백엔드가 같은 이름·필드로 별도 이슈에서 구현 예정.
 *
 * delta·citations는 패킷마다 필요한 것만 채워서 온다 — 둘 다 비어 있는 패킷도 유효하다(예:
 * status만 알리는 하트비트성 패킷). citations를 delta보다 먼저(빈 delta + status:'streaming'
 * 조합으로) 보낼 수 있어, 답변이 다 끝나기 전에 근거 패널이 먼저 채워지는 지금의 UX
 * (CitationsPanel의 loading 상태)를 유지할 수 있다 — 이게 chat.done에 얹지 않고 별도 필드로
 * 분리해서 얻은 것이다.
 *
 * status:'done'인 패킷도 delta·citations를 함께 실어 보낼 수 있는지는 스펙에 명시돼 있지 않다
 * — 그래서 이 어댑터는 status와 무관하게 매 패킷마다 delta·citations를 항상 처리하고,
 * status:'done'만 별도로 "스트림 종료" 신호로 취급한다(frame-stream-fetch.ts 참고).
 *
 * restrictedResultsOmitted: 기존 REST CitationsResponse(lib/api/chat.ts)에 있던 필드를 그대로
 * 옮겼다 — citations-panel.tsx가 true일 때 "일부 문서는 접근 권한이 없어 결과에서
 * 제외되었습니다" RBAC 안내를 렌더링하는 데 쓴다(PR #50 리뷰, bsjSunjoo). citations가 빈
 * 배열이어도 이 값이 true일 수 있다(전부 걸러진 경우) — 그래서 두 필드는 서로 독립이다.
 */
export interface ChatAnswerFrame {
  type: 'chat.answer';
  sessionId: string;
  delta: string;
  citations: Citation[];
  restrictedResultsOmitted: boolean;
  status: 'streaming' | 'done';
}

/** 참여자 간 일반 대화 메시지. AI 호출 라우팅 정책은 이슈 #13에서 결정 중. */
export interface ChatMessageFrame {
  type: 'chat.message';
  sessionId: string;
  /** 작성자의 Keycloak subject. 참여자 관리 API가 쓰는 값과 같다(이슈 #130). */
  from: string;
  /** 작성자의 표시 이름. 서버가 발신 시점의 JWT claim에서 읽어 실어 보낸다. */
  fromDisplayName: string;
  content: string;
}

/**
 * presence 프레임이 사람 한 명을 가리키는 방식(이슈 #130). 식별은 subject로, 화면 표시는
 * displayName으로 한다 — 내부 app_user.id는 더 이상 클라이언트로 내려오지 않는다.
 */
export interface PresenceParticipant {
  subject: string;
  displayName: string;
}

/** 참여자 입장 이벤트. */
export interface PresenceJoinFrame {
  type: 'presence.join';
  sessionId: string;
  subject: string;
  displayName: string;
}

/**
 * 참여자 퇴장 이벤트(이슈 #25). join과 필드가 같아 한 타입으로 합치고 싶어지지만, 판별 유니온의
 * 태그가 곧 계약이라 서버 record와 1:1로 둔다.
 *
 * 같은 사용자의 다른 연결이 남아 있으면 서버가 보내지 않는다 — 탭을 하나 더 열었다 닫은 것은
 * 퇴장이 아니기 때문이다(RoomSessionRegistry.Departure). 방의 마지막 사람이 나갈 때도 받을
 * 상대가 없어 나가지 않는다.
 */
export interface PresenceLeaveFrame {
  type: 'presence.leave';
  sessionId: string;
  subject: string;
  displayName: string;
}

/**
 * 연결이 붙는 순간 그 연결에만 오는 참여자 명단(이슈 #26). join·leave가 "방금 일어난 일"이라면
 * 이쪽은 "지금 상태"다.
 *
 * 본인이 들어 있다. 자기 입장은 자기가 받지 않는 설계라 본인을 빼면 스스로를 목록에 넣을 방법이
 * 없다.
 *
 * 입장 이벤트를 되풀이하는 방식 대신 타입을 나눈 이유는 #111(입퇴장 시스템 메시지)이 같은
 * presence.join을 읽기 때문이다. 명단 재생과 실제 입장이 같은 타입이면 방에 들어갈 때마다
 * 이미 있던 사람들이 방금 들어온 것처럼 보인다.
 */
export interface PresenceSnapshotFrame {
  type: 'presence.snapshot';
  sessionId: string;
  participants: PresenceParticipant[];
}

/** 스트림 중 발생한 오류. HTTP 쪽 BffErrorEnvelope(lib/api/errors.ts)와 code/message/traceId를
 * 같은 모양으로 재사용한다. 연결 수립 자체가 실패하는 등 특정 세션에 속하지 않는 오류는
 * sessionId가 null일 수 있다. */
export interface WsErrorFrame {
  type: 'error';
  sessionId: string | null;
  code: string;
  message: string;
  traceId: string;
}

/**
 * 시스템 알림(이슈 #29). 위험 질문 사후 검증 배치(#28)가 감지한 것이나 토큰 소진 안내처럼
 * "실패"가 아니라 "통보"로 도착하는 것들이다.
 *
 * error에 얹지 않고 타입을 나눈 이유는 error가 이미 "이 턴이나 연결이 실패했다"는 재시도
 * 신호로 쓰이고 있어서다 — 실패가 아닌 통보를 섞으면 그 판단 기준이 흐려진다.
 *
 * severity가 표시 방식을 정한다(warning=배너, info=토스트). code로 가르지 않는 이유는 서버가
 * code를 하나 추가할 때마다 프론트가 그 목록을 따라 배포돼야 하기 때문이다. 모르는 severity는
 * 버리지 않고 warning으로 받는다(parse-frame.ts) — 위험 알림에서는 "안 보이는 것"이 "덜
 * 정확하게 보이는 것"보다 나쁘다.
 *
 * code/message/traceId는 error 프레임과 같은 결이다. 문구가 바뀌어도 분기할 키는 code다.
 * 다만 error처럼 코드→문구 표(errors.ts)로 바꾸지 않고 서버가 실은 message를 그대로 쓴다 —
 * 알림 문구는 배치가 감지한 내용에 따라 서버가 정하는 계약이다.
 *
 * 감지된 메시지를 지목하는 필드(msgId)는 이번 범위에 없다 — chat.message에 서버 메시지 id가
 * 실리지 않아 어느 말풍선인지 특정할 수 없고, 그 변경은 별도 이슈로 분리했다(#29 코멘트).
 */
export interface SystemNoticeFrame {
  type: 'system.notice';
  sessionId: string | null;
  severity: 'warning' | 'info';
  code: string;
  message: string;
  traceId: string;
}

/**
 * 참여자 명단이 바뀌었음을 알리는 프레임(이슈 #129·#172). presence(#25·#26)가 "지금 접속해
 * 있는가"를 다루는 것과 달리 이쪽은 "이 방의 참여자인가"다 — 접속 중이 아닌 사람이 초대되는
 * 경우처럼 presence로는 표현되지 않는 변화가 있어 타입이 따로 있다.
 *
 * action으로 분기하지 않고 명단 재조회 트리거로만 쓴다. 서버가 값을 다섯으로 나눈 것은
 * 나중에 액션별 시스템 메시지가 필요해졌을 때를 위한 것이라, 지금 화면이 그 값을 해석할
 * 이유가 없다 — 어느 액션이든 "다시 불러와"가 정답이다.
 */
export interface ParticipantChangedFrame {
  type: 'participant.changed';
  sessionId: string;
  /**
   * 서버가 지금 보내는 값은 INVITED·REMOVED·OWNER_TRANSFERRED·INVITE_PENDING·INVITE_REVOKED
   * 다섯이지만 union으로 좁히지 않는다 — 화면이 이 값으로 분기하지 않는데 좁혀 두면 서버가
   * 액션을 하나 더한 순간 프론트를 배포하기 전까지 통지가 통째로 사라진다.
   */
  action: string;
  subject: string;
  displayName: string;
}

/** 서버 WsFrame과 대응하는 전체 유니온. 새 타입이 추가되면 여기 한 곳만 넓히면 되고,
 * frame-router.ts의 exhaustive switch가 미처리 케이스를 컴파일 타임에 잡아준다. */
export type WsFrame =
  | ChatAnswerFrame
  | ChatMessageFrame
  | PresenceJoinFrame
  | PresenceLeaveFrame
  | PresenceSnapshotFrame
  | ParticipantChangedFrame
  | SystemNoticeFrame
  | WsErrorFrame;

/** WsFrame 서브타입의 type 태그 리터럴만 뽑은 유니온. parse-frame.ts의 태그 검증에 쓴다. */
export type WsFrameType = WsFrame['type'];
