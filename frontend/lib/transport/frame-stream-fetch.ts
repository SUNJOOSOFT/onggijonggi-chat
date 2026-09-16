/********************************************************
 파일명 : frame-stream-fetch.ts (lib/transport)
 설 명 : 이슈 #10("메시지 통역기") 본체. FrameSource(타입 태그 붙은 JSON 프레임 문자열의
 스트림)를 받아, useChat(streamProtocol:'text')이 그대로 소비할 수 있는 raw text Response로
 재조립한다. chat.answer의 delta만 body에 쓰고, citations는 body를 오염시키지 않도록 별도
 콜백으로 빼낸다 — 이게 완료 기준 2번("병렬 채널 제거, 단일 패킷 통합")의 실제 구현이다.

 전송 계층은 아직 없다(#2 결정 대기) — 그래서 이 함수는 소스를 스스로 열지 않고 인자로
 받는다. 지금은 mock-frame-source.ts가 그 자리를 채우고, 실제 WS가 생기면 그 메시지를
 AsyncIterable<string>으로 감싸기만 하면 이 함수는 그대로 재사용된다.

 에러 처리는 기존 HTTP 계약(lib/api/errors.ts)을 그대로 재사용하도록 설계했다:
   - 스트림 시작 전에 error 프레임이 오면(첫 프레임을 미리 들여다본다) → code에 맞는 HTTP
     status + {error:{code,message,traceId}} JSON으로 응답한다. authFetchWithRetry(http.ts)의
     401 재시도·429 백오프, resolveChatError의 code→문구 매핑이 손 안 대고 그대로 작동한다.
   - 토큰이 이미 흐르기 시작한 뒤 error가 오면 → 응답 헤더가 이미 커밋된 뒤라 status를 못
     바꾼다. ReadableStream을 controller.error()로 끊어서, 기존에도 있던 "스트림 중단"
     경로(chat.tsx의 isStreamTruncated → STREAM_TRUNCATED_MESSAGE)로 자연스럽게 흘러가게 한다.
 *********************************************************/

import type { BffErrorEnvelope } from '@/lib/api/errors';
import type { CitationsResponse } from '@/lib/api/chat';
import { routeFrame } from './frame-router';
import { parseFrameFromText } from './parse-frame';
import type {
  ChatMessageFrame,
  PresenceJoinFrame,
  WsErrorFrame,
} from './frames';

/** WS message 이벤트든 NDJSON 한 줄이든, 결국 "프레임 하나 = 문자열 하나"로 도착한다는
 * 점만 계약으로 삼는다 — 전송 계층이 뭐든 이 인터페이스만 만족하면 이 파일은 안 바뀐다. */
export type FrameSource = AsyncIterable<string>;

/** 채팅 본문(chat.answer/error)이 아닌, 곁다리로 흘러나오는 프레임을 위한 콜백. 전부
 * 선택적이다 — 지금 chat.tsx가 실제로 쓸 건 onChatCitation 하나뿐이고, 나머지 둘은 협업
 * 채팅방(#13)이 붙을 때 쓰일 자리를 미리 비워둔 것이다. onChatCitation은 기존 REST
 * CitationsResponse와 같은 모양(citations + restrictedResultsOmitted)을 그대로 재사용한다 —
 * #163에서 chat.tsx의 citationsByMessageId 상태로 옮길 때 변환 없이 바로 쓸 수 있게 하기 위해서다.
 * citations가 비어 있어도 restrictedResultsOmitted가 true면(전부 걸러진 경우) 불린다 —
 * "이 패킷에 citation 관련 정보가 있다"는 기준은 둘 중 하나라도 참인지로 판단한다. */
export interface FrameStreamCallbacks {
  /** turnId는 이 citations가 어느 턴 것인지 호출부가 메시지에 정확히 짝지을 수 있게 실어
   * 보낸다(이슈 #163 후속) — "지금 활성 턴"만 아는 단일 ref로 짝짓던 방식은 턴을 취소하고
   * 바로 재전송하면 늦게 도착한 citations가 엉뚱한 메시지에 붙을 수 있었다. */
  onChatCitation?: (
    payload: CitationsResponse & { turnId: string | null },
  ) => void;
  onChatMessage?: (frame: ChatMessageFrame) => void;
  onPresenceJoin?: (frame: PresenceJoinFrame) => void;
  /** chat.answer가 terminal 상태로 끝날 때 한 번 불린다(이슈 #162). useChat의 text 스트림
   * body는 "끝났다"만 전할 뿐 "왜 끝났는지"(정상 완료·취소·FIFO 거절)를 못 실어서, DIRECT
   * 화면이 PENDING·CANCELLED·DENIED를 구분해 보여주려면 이 콜백이 따로 필요하다. */
  onChatAnswerTerminal?: (status: 'done' | 'cancelled' | 'denied') => void;
}

/** GlobalExceptionHandler.java가 code별로 매기는 HTTP status와 동일하게 맞춘다(errors.ts의
 * BffErrorCode 주석 참고). 알려지지 않은 code는 500(catch-all)으로 보낸다. */
function statusForErrorCode(code: string): number {
  switch (code) {
    case 'VALIDATION_ERROR':
    case 'MALFORMED_REQUEST':
      return 400;
    case 'UNAUTHENTICATED':
    case 'TOKEN_EXPIRED':
    case 'TOKEN_INVALID':
      return 401;
    case 'FORBIDDEN':
      return 403;
    case 'RATE_LIMITED':
      return 429;
    case 'MODEL_UNAVAILABLE':
      return 502;
    default:
      return 500;
  }
}

function errorFrameToResponse(frame: WsErrorFrame): Response {
  const envelope: BffErrorEnvelope = {
    error: { code: frame.code, message: frame.message, traceId: frame.traceId },
  };
  return new Response(JSON.stringify(envelope), {
    status: statusForErrorCode(frame.code),
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * FrameSource를 소비해 Response를 만든다. useChat({ fetch })에 그대로 넘길 수 있는 모양이다.
 *
 * 첫 프레임을 미리 한 번 들여다봐서(peek) 스트림을 열기 전에 상태 코드를 결정한다 — 그래야
 * "시작하자마자 실패"를 기존 HTTP 에러 흐름과 동일하게 처리할 수 있다. 이 peek 때문에 함수가
 * Promise<Response>다(즉시 Response를 반환하지 않는다).
 */
export async function frameSourceToResponse(
  source: FrameSource,
  callbacks: FrameStreamCallbacks = {},
): Promise<Response> {
  const iterator = source[Symbol.asyncIterator]();
  const first = await iterator.next();

  if (!first.done) {
    const firstFrame = parseFrameFromText(first.value);
    if (firstFrame?.type === 'error') {
      return errorFrameToResponse(firstFrame);
    }
  }

  const encoder = new TextEncoder();
  let pending: IteratorResult<string> | null = first;

  const body = new ReadableStream<Uint8Array>({
    async start(controller) {
      try {
        while (true) {
          const result = pending ?? (await iterator.next());
          pending = null;
          if (result.done) break;

          const frame = parseFrameFromText(result.value);
          if (!frame) {
            console.warn(
              '[frame-stream-fetch] 파싱 실패한 프레임을 건너뜁니다:',
              result.value,
            );
            continue;
          }

          routeFrame(frame, {
            onChatAnswer: (f) => {
              if (f.delta) controller.enqueue(encoder.encode(f.delta));
              if (f.citations.length > 0 || f.restrictedResultsOmitted) {
                callbacks.onChatCitation?.({
                  citations: f.citations,
                  restrictedResultsOmitted: f.restrictedResultsOmitted,
                  turnId: f.turnId,
                });
              }
              // cancelled·denied는 DIRECT 전용 terminal 상태다(이슈 #162) — done과 같이 스트림을 닫는다.
              if (f.status === 'done' || f.status === 'cancelled' || f.status === 'denied') {
                callbacks.onChatAnswerTerminal?.(f.status);
                controller.close();
              }
            },
            onError: (f) =>
              controller.error(
                new Error(
                  `WS 스트림 오류 (code=${f.code}, traceId=${f.traceId}): ${f.message}`,
                ),
              ),
            onChatMessage: (f) => callbacks.onChatMessage?.(f),
            onPresenceJoin: (f) => callbacks.onPresenceJoin?.(f),
          });

          const isTerminal =
            (frame.type === 'chat.answer' &&
              (frame.status === 'done' ||
                frame.status === 'cancelled' ||
                frame.status === 'denied')) ||
            frame.type === 'error';
          if (isTerminal) return;
        }
        // 소스가 status:'done' 없이 그냥 끝난 경우(비정상)의 폴백.
        controller.close();
      } catch (err) {
        controller.error(err);
      }
    },
  });

  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
  });
}
