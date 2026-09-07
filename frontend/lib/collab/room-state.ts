/********************************************************
 파일명 : room-state.ts (lib/collab)
 설 명 : 협업방에 도착한 WS 프레임(#8 계약)을 화면이 그릴 수 있는 상태로 축약한다(이슈 #19).

 React 밖의 순수 함수로 둔 이유는 두 가지다. 하나는 vitest 환경이 'node'라 컴포넌트를 띄우지
 않고 프레임 순서에 따른 결과만 시험할 수 있다는 것이고, 다른 하나는 이 축약이 화면보다 오래
 살아남을 규칙이라는 것이다 — 방 레지스트리(#16)와 `@AI` 분기(#17)가 서버에 들어와도 프레임을
 상태로 접는 방식 자체는 바뀌지 않는다.

 `chat.answer`의 citations·restrictedResultsOmitted는 현재 AI 답변에 누적한다. 참여자 목록은
 붙는 순간 오는 `presence.snapshot`(#26)으로 채우고, 그 뒤로는 `presence.join`·`presence.leave`
 (#25)로 갱신한다 — 이 목록은 "지금 방에 붙어 있는 사람"이지 "이 방에 들어올 자격이 있는
 사람"이 아니다(후자는 #20·#23의 몫이다).
 *********************************************************/

import type { Citation } from '@/lib/api/chat';
import { friendlyMessageForCode } from '@/lib/api/errors';
import type { WsFrame } from '@/lib/transport/frames';

/** 방에 접근할 수 없다는 뜻의 에러 코드(백엔드 ErrorFrame이 02·EDGE에서 쓰는 값). */
const FORBIDDEN_CODE = 'FORBIDDEN';
const TERMINAL_AI_ERROR_CODES = new Set([
  'MODEL_UNAVAILABLE',
  'INTERNAL_ERROR',
]);

/** 메시지 하나. 사람과 AI를 role이 아니라 보낸 사람 유무로 가른다 — 협업방에는 보낸 사람이 여럿이다. */
export interface CollabMessage {
  id: string;
  /** 사람이 보낸 것이면 그 사람 이름, AI 답변이면 null. */
  from: string | null;
  content: string;
  /** AI 답변이 아직 흐르는 중인지. 사람 메시지는 언제나 false다. */
  streaming: boolean;
  citations: Citation[];
  restrictedResultsOmitted: boolean;
}

/** 서버가 보낸 오류. code가 FORBIDDEN이면 방을 그릴 수 없고, 그 외에는 방 위에 얹어 알린다. */
export interface RoomError {
  code: string;
  message: string;
  traceId: string;
}

export interface RoomState {
  /** 입장 순서대로의 참여자. 같은 사람의 join이 두 번 도착해도(재연결·스냅샷 재생) 한 번만 센다. */
  participants: string[];
  messages: CollabMessage[];
  error: RoomError | null;
  /** 다음 메시지에 붙일 번호. 순수 함수로 두려고 상태에 담았다 — 시계나 난수에 기대지 않는다. */
  nextMessageId: number;
}

export const initialRoomState: RoomState = {
  participants: [],
  messages: [],
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

/** 메시지 하나를 덧붙인다. */
function appendMessage(
  state: RoomState,
  from: string | null,
  content: string,
  streaming: boolean,
): RoomState {
  return {
    ...state,
    messages: [
      ...state.messages,
      {
        id: `m${state.nextMessageId}`,
        from,
        content,
        streaming,
        citations: [],
        restrictedResultsOmitted: false,
      },
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
 * 흐르는 중인 AI 답변의 자리를 돌려준다. 없으면 null.
 *
 * 맨 끝만 보면 안 된다 — 답변이 흐르는 도중 다른 참여자가 말하면 그 메시지가 끝에 붙고, 이어지는
 * delta가 이 답변을 못 찾아 말풍선이 둘로 갈린다. 여러 명이 있는 방에서는 흔한 순서다(PR #80 리뷰).
 *
 * 흐르는 답변이 둘 이상이면 가장 최근 것에 잇는다. 프레임에 스트림 식별자가 없어(#8) 어느 답변의
 * delta인지 가릴 수 없기 때문이고, `@AI` 호출이 동시에 여러 개 흐를 수 있는지는 #17이 정한다.
 */
function streamingAnswerIndex(state: RoomState): number | null {
  for (let index = state.messages.length - 1; index >= 0; index -= 1) {
    const message = state.messages[index];
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
  const target = messages[index];
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
 * 여기 도착하는 것은 계약 안의 여섯 가지뿐이고, switch는 그 여섯을 모두 다룬다.
 */
export function applyFrame(state: RoomState, frame: WsFrame): RoomState {
  switch (frame.type) {
    case 'presence.join': {
      if (state.participants.includes(frame.userId)) return state;
      return { ...state, participants: [...state.participants, frame.userId] };
    }

    // 명단은 서버가 방금 뜬 것이라 지금까지 쌓인 것보다 정확하다 — 덧붙이지 않고 갈아끼운다.
    // 재연결하면 다시 오므로, 끊긴 사이에 오간 입퇴장을 놓쳤어도 여기서 맞춰진다.
    case 'presence.snapshot':
      return { ...state, participants: [...new Set(frame.participants)] };

    case 'presence.leave':
      return {
        ...state,
        participants: state.participants.filter(
          (participant) => participant !== frame.userId,
        ),
      };

    case 'chat.message':
      return appendMessage(state, frame.from, frame.content, false);

    case 'chat.answer': {
      const done = frame.status === 'done';
      const index = streamingAnswerIndex(state);
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
      const appended = appendMessage(state, null, frame.delta, !done);
      return extendAnswer(
        appended,
        appended.messages.length - 1,
        '',
        done,
        frame.citations,
        frame.restrictedResultsOmitted,
      );
    }

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
