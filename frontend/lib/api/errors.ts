/********************************************************
 파일명 : errors.ts (lib/api)
 설 명 : BFF 에러 모델. 서버의 공통 에러 봉투 {error:{code,message,traceId}}를 code 기준으로
 사용자 친화 문구에 매핑한다. 서버 message는 내부 상세를 담을 수 있어 노출하지 않고,
 CLIENT가 code로 자체 문구를 고른다. 순수 함수만 두어 단위 검증 가능하게 한다.
 *********************************************************/

/** 알려진 에러 코드 — EDGE·CORE 실측 기준. */
export type BffErrorCode =
  | 'VALIDATION_ERROR' // 400 CORE — @Valid 검증 실패
  | 'MALFORMED_REQUEST' // 400 CORE — JSON 파싱 실패
  | 'UNAUTHENTICATED' // 401 EDGE — Authorization 헤더 없음
  | 'TOKEN_EXPIRED' // 401 EDGE — 만료 토큰
  | 'TOKEN_INVALID' // 401 EDGE — 서명 불일치·형식 오류
  | 'FORBIDDEN' // 403 EDGE·CORE — role 불충분, 또는 방의 OWNER가 아님
  | 'NOT_FOUND' // 404 CORE — 없는 리소스, 또는 존재를 노출하지 않으려 404로 답한 남의 것
  | 'PARTICIPANT_STATE_CONFLICT' // 409 CORE — 참여자 상태 전제조건 위반(소유권 위임 전 탈퇴 등)
  | 'RATE_LIMITED' // 429 EDGE — 요청 한도 초과
  | 'IDEMPOTENCY_KEY_CONFLICT' // 409 CORE — 같은 Idempotency-Key에 이전과 다른 요청 내용
  | 'MODEL_UNAVAILABLE' // 502 CORE — 게이트웨이가 모델 호출을 거절
  | 'MESSAGE_DELIVERY_FAILED'; // WS — 협업방 메시지 방송 실패

export interface BffErrorEnvelope {
  error: { code: string; message?: string; traceId?: string };
}

// 401 계열은 authFetch에서 리프레시·재로그인으로 먼저 처리되므로, 이 문구는 그 경로가
// 실패해 화면까지 온 경우의 안내다.
const FRIENDLY_BY_CODE: Record<string, string> = {
  VALIDATION_ERROR:
    '요청을 처리할 수 없어요. 입력을 확인하고 다시 시도해 주세요.',
  MALFORMED_REQUEST:
    '요청을 처리할 수 없어요. 입력을 확인하고 다시 시도해 주세요.',
  UNAUTHENTICATED: '세션이 만료되었어요. 다시 로그인해 주세요.',
  TOKEN_EXPIRED: '세션이 만료되었어요. 다시 로그인해 주세요.',
  TOKEN_INVALID: '세션이 만료되었어요. 다시 로그인해 주세요.',
  FORBIDDEN: '이 작업을 수행할 권한이 없어요.',
  // 없는 것과 남의 것을 구분하지 않는다(존재 비노출) — 문구도 어느 쪽인지 밝히지 않는다.
  NOT_FOUND: '요청한 항목을 찾을 수 없어요.',
  // 남이 먼저 바꿔놓은 상태(소유권이 넘어갔거나 이미 나간 사람)라 다시 불러오면 달라진다.
  PARTICIPANT_STATE_CONFLICT:
    '참여자 정보가 방금 바뀌었어요. 목록을 새로고침한 뒤 다시 시도해 주세요.',
  RATE_LIMITED: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.',
  // GENERIC_MESSAGE(재시도 유도)를 쓰면 안 된다 — 같은 키를 쓰는 한 재시도해도 계속 같은
  // 결과(409)라, 페이지를 새로고침해 새 시도로 인식되게 해야 실제로 풀린다.
  IDEMPOTENCY_KEY_CONFLICT:
    '같은 요청을 다른 내용으로 다시 보냈어요. 페이지를 새로고침한 뒤 다시 시도해 주세요.',
  MESSAGE_DELIVERY_FAILED:
    '메시지를 전달하지 못했어요. 연결을 확인하고 다시 보내 주세요.',
  // 모델 목록에는 API 키를 채우지 않은 모델도 뜬다 — 어떤 키가 설정됐는지는 게이트웨이만 알기
  // 때문이다. 그래서 고르기 전에 막지 못하고 이 시점에 안내한다.
  MODEL_UNAVAILABLE:
    '이 모델을 사용할 수 없어요. API 키가 설정되어 있는지 확인하거나 다른 모델을 선택해 주세요.',
};

const GENERIC_MESSAGE =
  '일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.';

/** code로 친화 문구를 고른다. 미지 code/누락 시 제네릭 문구로 폴백한다. */
export function friendlyMessageForCode(code?: string): string {
  if (code && code in FRIENDLY_BY_CODE) {
    return FRIENDLY_BY_CODE[code];
  }
  return GENERIC_MESSAGE;
}

/** 공통 에러 봉투 문자열을 파싱한다. 봉투가 아니면 null을 반환해 호출부가 제네릭 처리로 폴백하게 한다. */
export function parseErrorEnvelope(
  body: string,
): { code?: string; traceId?: string } | null {
  try {
    const parsed = JSON.parse(body) as Partial<BffErrorEnvelope>;
    if (parsed && typeof parsed === 'object' && parsed.error) {
      return { code: parsed.error.code, traceId: parsed.error.traceId };
    }
  } catch {
    // 봉투 JSON이 아님 — 5xx 평문·네트워크 오류·스트림 중단 등.
  }
  return null;
}

// 스트림 시작 후 오류는 서버가 이미 200을 보낸 뒤라 연결 종료로만 신호되고 에러 봉투가 없다
// — resolveChatError로는 "첫 바이트 이전 오류"와 구분이 안 돼 별도 문구를 둔다.
export const STREAM_TRUNCATED_MESSAGE = '응답이 중단되었습니다.';

/** 직전 메시지가 이미 내용을 쌓고 있던 assistant 응답인지로 스트림 중단 여부를 판정한다. */
export function isStreamTruncated(
  lastMessage: { role: string; content: string } | undefined,
): boolean {
  return lastMessage?.role === 'assistant' && lastMessage.content.length > 0;
}

/** useChat onError가 받는 Error를 친화 문구로 변환한다. AI SDK는 non-2xx 시 응답 바디 텍스트를
 * Error.message에 실으므로, 그 안의 에러 봉투를 파싱해 code→문구로 매핑한다. 봉투가 아니면 제네릭 문구로 폴백한다. */
export function resolveChatError(error: Error): {
  message: string;
  code?: string;
  traceId?: string;
} {
  const envelope = parseErrorEnvelope(error.message);
  return {
    message: friendlyMessageForCode(envelope?.code),
    code: envelope?.code,
    traceId: envelope?.traceId,
  };
}
