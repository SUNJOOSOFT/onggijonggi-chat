/********************************************************
 파일명 : room-state.ts (lib/collab)
 설 명 : 협업방에 도착한 WS 프레임(#8 계약)을 화면이 그릴 수 있는 상태로 축약한다(이슈 #19).

 React 밖의 순수 함수로 둔 이유는 두 가지다. 하나는 vitest 환경이 'node'라 컴포넌트를 띄우지
 않고 프레임 순서에 따른 결과만 시험할 수 있다는 것이고, 다른 하나는 이 축약이 화면보다 오래
 살아남을 규칙이라는 것이다 — 방 레지스트리(#16)와 `@AI` 분기(#17)가 서버에 들어와도 프레임을
 상태로 접는 방식 자체는 바뀌지 않는다.

 입퇴장은 두 군데에 남는다 — 참여자 목록(#26)에는 지금 상태로, 메시지 흐름(#111)에는
 일어난 사건으로. 같은 프레임을 두 번 쓰는 것이지 중복이 아니다.

 `chat.answer`의 citations·restrictedResultsOmitted는 현재 AI 답변에 누적한다. 참여자 목록은
 붙는 순간 오는 `presence.snapshot`(#26)으로 채우고, 그 뒤로는 `presence.join`·`presence.leave`
 (#25)로 갱신한다 — 이 목록은 "지금 방에 붙어 있는 사람"이지 "이 방에 들어올 자격이 있는
 사람"이 아니다(후자는 #20·#23의 몫이다).
 *********************************************************/

import type { Citation } from '@/lib/api/chat';
import type { CollabMessageItem } from '@/lib/api/collab';
import { friendlyMessageForCode } from '@/lib/api/errors';
import type {
  PresenceParticipant,
  SystemNoticeFrame,
  WsFrame,
} from '@/lib/transport/frames';

/** 방에 접근할 수 없다는 뜻의 에러 코드(백엔드 ErrorFrame이 02·EDGE에서 쓰는 값). */
const FORBIDDEN_CODE = 'FORBIDDEN';
const TERMINAL_AI_ERROR_CODES = new Set([
  'MODEL_UNAVAILABLE',
  'INTERNAL_ERROR',
]);

/** 메시지 하나. 사람과 AI를 role이 아니라 보낸 사람 유무로 가른다 — 협업방에는 보낸 사람이 여럿이다. */
export interface CollabMessage {
  /** 서버가 준 msgId(이슈 #190). 이력과 실시간이 같은 메시지를 가리키는 근거다. */
  id: string;
  /** 방 안에서의 순서. 정렬과 따라잡기 커서로만 쓰고 연속성은 가정하지 않는다. */
  seq: number;
  /** 사람이 보낸 것이면 그 사람, AI 답변이면 null. */
  from: PresenceParticipant | null;
  content: string;
  /** AI 답변이 아직 흐르는 중인지. 사람 메시지는 언제나 false다. */
  streaming: boolean;
  citations: Citation[];
  restrictedResultsOmitted: boolean;
}

/**
 * 입퇴장을 시간 순서에 남기는 줄(이슈 #111). 목록(#26)이 "지금 누가 있나"라면 이쪽은
 * "언제 누가 오갔나"다 — 그래서 상태가 아니라 메시지 흐름에 낀다.
 *
 * 문구를 여기서 만들지 않고 사건과 사람만 담는다 — 어떻게 쓸지는 화면이 정하는 편이
 * 갈아끼우기 쉽다. 사람은 subject로 식별하고 표시는 displayName으로 한다(이슈 #130).
 *
 * 자기 입퇴장은 서버가 자기에게 보내지 않으므로 "내가 입장했습니다" 줄은 생기지 않는다.
 * 붙는 순간 받는 참여자 명단(presence.snapshot)도 여기에 줄을 만들지 않는다 — 명단은
 * 사건이 아니라 상태다.
 */
export interface CollabPresenceNotice {
  id: string;
  event: 'join' | 'leave';
  participant: PresenceParticipant;
}

/** 대화 흐름에 놓이는 것. 사람·AI 메시지이거나, 입퇴장 시스템 라인이다. */
export type CollabEntry = CollabMessage | CollabPresenceNotice;

/** 시스템 라인인지. 판별 태그를 따로 두지 않고 event 유무로 가른다 — 메시지 쪽 모양을
 * 건드리지 않으려는 것이다. */
export function isPresenceNotice(
  entry: CollabEntry,
): entry is CollabPresenceNotice {
  return 'event' in entry;
}

/**
 * 방 위에 얹어두는 시스템 알림(이슈 #29). severity가 warning인 것만 여기 남는다 — info는
 * 지나가도 되는 안내라 토스트로 띄우고 상태에 남기지 않는다(use-collab-room.ts).
 *
 * 같은 code는 최신 1건만 남긴다. 위험 질문 배치가 30초마다 도는 계약(#27)이라 같은 사유가
 * 되풀이해서 오는데, 그때마다 배너가 쌓이면 방을 덮는다. 자리를 옮기지 않고 그 자리의 내용만
 * 갈아끼우는 것은 읽던 배너가 튀지 않게 하려는 것이다.
 */
export interface SystemNotice {
  code: string;
  message: string;
  traceId: string;
}

/** 서버가 message를 비워 보냈을 때 대신 쓸 문구. 계약상 오지 않아야 하지만, 빈 배너는 아무것도
 * 알리지 못하면서 자리만 차지한다 — 알림이 왔다는 사실 자체는 남긴다. */
const GENERIC_NOTICE_MESSAGE = '확인이 필요한 알림이 도착했습니다.';

/** 알림 문구. 배너(상태)와 토스트(훅) 두 경로가 같은 폴백을 쓰도록 여기 한 곳에 둔다. */
export function noticeMessage(message: string): string {
  return message === '' ? GENERIC_NOTICE_MESSAGE : message;
}

/** 서버가 보낸 오류. code가 FORBIDDEN이면 방을 그릴 수 없고, 그 외에는 방 위에 얹어 알린다. */
export interface RoomError {
  code: string;
  message: string;
  traceId: string;
}

export interface RoomState {
  /** 입장 순서대로의 참여자. 같은 사람의 join이 두 번 도착해도(재연결·스냅샷 재생) 한 번만 센다. */
  participants: PresenceParticipant[];
  messages: CollabEntry[];
  /** 닫기 전까지 방 위에 남아 있는 알림. 도착 순서대로다. */
  notices: SystemNotice[];
  error: RoomError | null;
  /** 다음 메시지에 붙일 번호. 순수 함수로 두려고 상태에 담았다 — 시계나 난수에 기대지 않는다. */
  nextMessageId: number;
}

export const initialRoomState: RoomState = {
  participants: [],
  messages: [],
  notices: [],
  error: null,
  nextMessageId: 1,
};

/** error.code가 방 접근 거부인지. 화면은 이 경우에만 방 대신 안내를 그린다. */
export function isForbidden(error: RoomError | null): boolean {
  return error?.code === FORBIDDEN_CODE;
}

export function clearRoomError(state: RoomState): RoomState {
  return state.error === null ? state : { ...state, error: null };
}

/** 알림 하나를 닫는다. 같은 code는 어차피 한 건뿐이라 code로 지운다. */
export function dismissNotice(state: RoomState, code: string): RoomState {
  const notices = state.notices.filter((notice) => notice.code !== code);
  return notices.length === state.notices.length
    ? state
    : { ...state, notices };
}

/** 같은 code가 이미 있으면 그 자리에서 갈아끼우고, 없으면 뒤에 붙인다. */
function upsertNotice(state: RoomState, frame: SystemNoticeFrame): RoomState {
  const notice: SystemNotice = {
    code: frame.code,
    message: noticeMessage(frame.message),
    traceId: frame.traceId,
  };
  const index = state.notices.findIndex(
    (current) => current.code === notice.code,
  );
  if (index === -1) {
    return { ...state, notices: [...state.notices, notice] };
  }
  const notices = [...state.notices];
  notices[index] = notice;
  return { ...state, notices };
}

/** 메시지 하나를 덧붙인다. */
function appendMessage(
  state: RoomState,
  id: string,
  seq: number,
  from: PresenceParticipant | null,
  content: string,
  streaming: boolean,
): RoomState {
  return {
    ...state,
    messages: [
      ...state.messages,
      {
        id,
        seq,
        from,
        content,
        streaming,
        citations: [],
        restrictedResultsOmitted: false,
      },
    ],
  };
}

/** 같은 msgId가 이미 흐름에 있는지. 이력과 실시간이 겹칠 때 한 번만 남기는 근거다(#190). */
function messageIndexById(state: RoomState, id: string): number | null {
  const index = state.messages.findIndex(
    (entry) => !isPresenceNotice(entry) && entry.id === id,
  );
  return index === -1 ? null : index;
}

/**
 * 방 진입 시 한 번 불러온 과거 대화를 흐름 앞에 붙인다(이슈 #190).
 *
 * 이미 있는 msgId는 건너뛴다 — 이력을 받기 전에 WS로 먼저 도착한 메시지가 있을 수 있고,
 * 그때 같은 말이 두 번 보이면 안 된다. 남는 것들은 seq로 정렬해 앞에 통째로 붙인다: 이력은
 * 진입 시점의 과거이고 지금 흐름에 있는 것은 그보다 새것이라, 둘을 섞어 정렬할 이유가 없다.
 * 입퇴장 줄(seq가 없다)이 제자리에 남는 것도 같은 이유다.
 *
 * PENDING·CANCELLED처럼 본문이 빈 행은 그리지 않는다 — 빈 말풍선은 아무것도 알리지 못하면서
 * 자리만 차지한다(chat.answer의 빈 패킷을 버리는 것과 같은 결).
 *
 * SYSTEM도 그리지 않는다. 사람도 AI도 아닌 줄을 이 화면이 표현할 방법이 아직 없어서다 —
 * from이 null이면 AI 답변으로 보인다. 위험 알림은 system.notice 배너가 따로 전한다(#29).
 */
export function applyHistory(
  state: RoomState,
  items: CollabMessageItem[],
): RoomState {
  const known = new Set(
    state.messages.filter((entry) => !isPresenceNotice(entry)).map((entry) => entry.id),
  );
  const restored = items
    .filter((item) => !known.has(item.id))
    .filter((item) => item.athKind !== 'SYSTEM')
    .filter((item) => item.content !== '')
    .sort((left, right) => left.seq - right.seq)
    .map(toCollabMessage);

  if (restored.length === 0) return state;
  return { ...state, messages: [...restored, ...state.messages] };
}

/** 이력 한 줄을 화면이 아는 모양으로. subject는 WS 프레임의 from과 같은 값이다(이슈 #190). */
function toCollabMessage(item: CollabMessageItem): CollabMessage {
  return {
    id: item.id,
    seq: item.seq,
    from:
      item.athKind === 'HUMAN'
        ? {
            subject: item.authorSubject ?? '',
            displayName: item.authorDisplayName ?? item.authorSubject ?? '',
          }
        : null,
    content: item.content,
    streaming: false,
    citations: [],
    restrictedResultsOmitted: false,
  };
}

/** 명단에 이미 있는 사람인지. 같은 사람인지는 subject로만 가른다 — 표시 이름은 바뀔 수 있다. */
function hasParticipant(state: RoomState, subject: string): boolean {
  return state.participants.some(
    (participant) => participant.subject === subject,
  );
}

/** 명단에서 같은 subject가 겹치면 먼저 온 쪽을 남긴다(서버가 보내는 순서가 입장 순서다). */
function distinctBySubject(
  participants: PresenceParticipant[],
): PresenceParticipant[] {
  const bySubject = new Map<string, PresenceParticipant>();
  for (const participant of participants) {
    if (!bySubject.has(participant.subject)) {
      bySubject.set(participant.subject, participant);
    }
  }
  return [...bySubject.values()];
}

/** 입퇴장 줄 하나를 덧붙인다. 번호는 메시지와 같은 자리에서 뽑는다 — 한 목록에 섞이므로
 * key가 겹치면 안 된다. */
function appendNotice(
  state: RoomState,
  event: 'join' | 'leave',
  participant: PresenceParticipant,
): RoomState {
  return {
    ...state,
    messages: [
      ...state.messages,
      { id: `m${state.nextMessageId}`, event, participant },
    ],
    nextMessageId: state.nextMessageId + 1,
  };
}

function mergeCitations(current: Citation[], incoming: Citation[]): Citation[] {
  const merged = [...current];
  const indexes = new Map(
    merged.map((citation, index) => [citation.docId, index]),
  );
  for (const citation of incoming) {
    const index = indexes.get(citation.docId);
    if (index === undefined) {
      indexes.set(citation.docId, merged.length);
      merged.push(citation);
    } else {
      merged[index] = citation;
    }
  }
  return merged;
}

/**
 * 흐르는 중인 AI 답변의 자리. chat.answer는 이제 msgId로 직접 찾지만(이슈 #190), error
 * 프레임에는 msgId가 없어 "지금 흐르는 답변"을 뒤에서 찾는 이 방식이 아직 필요하다.
 */
function streamingAnswerIndex(state: RoomState): number | null {
  for (let index = state.messages.length - 1; index >= 0; index -= 1) {
    const message = state.messages[index];
    if (isPresenceNotice(message)) continue;
    if (message.from === null && message.streaming) return index;
  }
  return null;
}

/** 흐르는 중인 AI 답변에 delta를 잇고 종료 여부를 반영한다. */
function extendAnswer(
  state: RoomState,
  index: number,
  delta: string,
  done: boolean,
  citations: Citation[],
  restrictedResultsOmitted: boolean,
): RoomState {
  const messages = [...state.messages];
  // index는 streamingAnswerIndex가 고른 자리라 언제나 흐르는 중인 AI 답변이다.
  const target = messages[index] as CollabMessage;
  messages[index] = {
    ...target,
    content: target.content + delta,
    streaming: !done,
    citations: mergeCitations(target.citations, citations),
    restrictedResultsOmitted:
      target.restrictedResultsOmitted || restrictedResultsOmitted,
  };
  return { ...state, messages };
}

/**
 * 프레임 하나를 상태에 접는다. 알 수 없는 프레임은 parse-frame.ts가 이미 걸러내므로
 * 여기 도착하는 것은 계약 안의 여덟 가지뿐이고, switch는 그 여덟을 모두 다룬다.
 */
export function applyFrame(state: RoomState, frame: WsFrame): RoomState {
  switch (frame.type) {
    case 'presence.join': {
      // 이미 아는 사람의 join이 또 오면(재연결 등) 목록도 흐름도 건드리지 않는다.
      if (hasParticipant(state, frame.subject)) return state;
      const participant = {
        subject: frame.subject,
        displayName: frame.displayName,
      };
      const joined = {
        ...state,
        participants: [...state.participants, participant],
      };
      return appendNotice(joined, 'join', participant);
    }

    // 명단은 서버가 방금 뜬 것이라 지금까지 쌓인 것보다 정확하다 — 덧붙이지 않고 갈아끼운다.
    // 재연결하면 다시 오므로, 끊긴 사이에 오간 입퇴장을 놓쳤어도 여기서 맞춰진다.
    case 'presence.snapshot':
      return { ...state, participants: distinctBySubject(frame.participants) };

    case 'presence.leave': {
      // join과 대칭이다. 모르는 사람의 퇴장은 목록도 흐름도 건드리지 않는다 — 본 적 없는
      // 사람이 나갔다는 줄만 남으면 읽는 쪽은 놓친 입장이 있다고 오해한다.
      if (!hasParticipant(state, frame.subject)) return state;
      const left = {
        ...state,
        participants: state.participants.filter(
          (participant) => participant.subject !== frame.subject,
        ),
      };
      return appendNotice(left, 'leave', {
        subject: frame.subject,
        displayName: frame.displayName,
      });
    }

    // 참여자 명단이 바뀌었다는 신호일 뿐 이 상태에 접을 것이 없다(이슈 #129·#172).
    // 여기 participants는 접속자(presence)라 명단과 다른 목록이고, 명단은 REST로 읽는
    // 참여자 시트가 들고 있다 — 재조회는 use-collab-room이 신호로 내보낸다.
    case 'participant.changed':
      return state;

    case 'chat.message':
      // 같은 메시지가 이력으로도 실시간으로도 올 수 있다 — msgId로 한 번만 남긴다(이슈 #190).
      if (messageIndexById(state, frame.msgId) !== null) return state;
      return appendMessage(
        state,
        frame.msgId,
        frame.seq,
        { subject: frame.from, displayName: frame.fromDisplayName },
        frame.content,
        false,
      );

    case 'chat.answer': {
      const done = frame.status === 'done';
      // 이전에는 "흐르는 중인 답변"을 뒤에서 찾았다. 이제 프레임이 자기 msgId를 들고 오므로
      // 그 값으로 직접 찾는다(이슈 #190) — 턴이 겹쳐도 어느 답변의 delta인지 분명하다.
      const index = messageIndexById(state, frame.msgId);
      if (index !== null) {
        return extendAnswer(
          state,
          index,
          frame.delta,
          done,
          frame.citations,
          frame.restrictedResultsOmitted,
        );
      }
      // 아직 아무것도 안 실린 패킷으로 빈 말풍선을 만들 이유는 없다 — delta 없이 status만
      // 알리는 패킷도 유효한 계약이다(frames.ts 주석).
      if (
        frame.delta === '' &&
        frame.citations.length === 0 &&
        !frame.restrictedResultsOmitted
      ) {
        return state;
      }
      const appended = appendMessage(
        state,
        frame.msgId,
        frame.seq,
        null,
        frame.delta,
        !done,
      );
      return extendAnswer(
        appended,
        appended.messages.length - 1,
        '',
        done,
        frame.citations,
        frame.restrictedResultsOmitted,
      );
    }

    // info 알림은 상태로 남기지 않는다 — 지나가도 되는 안내라 훅이 그 자리에서 토스트로
    // 띄운다(use-collab-room.ts). 여기서 걸러야 배너가 info로 채워지지 않는다.
    case 'system.notice':
      return frame.severity === 'info' ? state : upsertNotice(state, frame);

    case 'error': {
      const index = streamingAnswerIndex(state);
      const nextState =
        index !== null && TERMINAL_AI_ERROR_CODES.has(frame.code)
          ? extendAnswer(state, index, '', true, [], false)
          : state;
      return {
        ...nextState,
        error: {
          code: frame.code,
          message: friendlyMessageForCode(frame.code),
          traceId: frame.traceId,
        },
      };
    }
  }
}
