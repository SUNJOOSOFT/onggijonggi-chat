/********************************************************
 파일명 : config.ts (lib/api)
 설 명 : BFF 단일 진입점 base URL 및 목업 토글. CLIENT는 오직 BFF만 호출한다.
 NEXT_PUBLIC_BFF_BASE_URL 미설정('')이면 동일 오리진 Next Route Handler 목업을 쓰고,
 실 BFF 주소를 넣으면 교차출처 실연동으로 전환되며 목업 라우트는 우회된다.
 *********************************************************/

/** BFF 절대 베이스 URL. 비어 있으면 동일 오리진(목업) 의미. */
export const BFF_BASE_URL = process.env.NEXT_PUBLIC_BFF_BASE_URL ?? '';

/** 목업 모드 여부 (BFF 미설정 시 동일 오리진 목업 사용). */
export const isMockMode = (): boolean => BFF_BASE_URL === '';

/** 계약 경로를 BFF 베이스 URL 에 결합한다. 목업 모드에선 상대경로(동일 오리진). */
export const bffUrl = (path: string): string => `${BFF_BASE_URL}${path}`;

/**
 * 서버 컴포넌트가 쓰는 베이스 URL. BFF_BASE_URL은 브라우저가 보는 공개 주소라
 * 컨테이너 안에서는 자기 자신을 가리켜 ConnectionRefused가 난다 — 도커로 띄울 때만
 * 주입되는 내부 주소(BFF_INTERNAL_URL)를 먼저 쓰고, 없으면 공개 주소로 되돌아간다.
 *
 * 둘 다 비어 있는 목업 모드에서는 목업 라우트를 들고 있는 Next 자신의 주소를 쓴다. 빈 문자열을
 * 그대로 두면 경로가 상대주소가 되는데, 브라우저와 달리 Node의 fetch는 기준 오리진이 없어
 * "Failed to parse URL from /api/chat/sessions"로 죽는다 — 목업만으로는 채팅 화면이 아예 열리지
 * 않던 원인이다.
 */
const SERVER_BFF_BASE_URL =
  process.env.BFF_INTERNAL_URL ||
  BFF_BASE_URL ||
  process.env.NEXTAUTH_URL ||
  'http://localhost:3000';

/** 계약 경로를 서버용 BFF 베이스 URL 에 결합한다. 서버 컴포넌트 전용. */
export const serverBffUrl = (path: string): string =>
  `${SERVER_BFF_BASE_URL}${path}`;

export const MODELS_PATH = '/api/models';
export const CHAT_STREAM_PATH = '/api/chat/stream';
export const CHAT_CITATIONS_PATH = '/api/chat/citations';
export const CHAT_SESSIONS_PATH = '/api/chat/sessions';

/** 협업 채널(방) 목록 조회 경로(이슈 #19). 목록 필터링은 서버 몫이라 프론트는 표시만 한다. */
export const COLLAB_THREADS_PATH = '/api/collab/threads';

/**
 * 방 진입 시 과거 대화를 한 번 불러오는 경로(이슈 #190). 이후의 실시간은 WS가 맡는다 —
 * 역할이 "채팅 종류"가 아니라 "시점"으로 갈린다.
 *
 * 1:1(server-history.ts)과 달리 클라이언트에서 부른다. WS 연결 수명과 맞물려 커서를 주고받아야
 * 해서 서버사이드 prefetch로는 조율되지 않는다(#189 코멘트).
 */
export const collabThreadMessagesPath = (
  threadId: string,
  afterSeq?: number,
): string => {
  const base = `${COLLAB_THREADS_PATH}/${encodeURIComponent(threadId)}/messages`;
  // afterSeq를 주면 그 뒤만 받는다 — 재접속해서 끊긴 동안의 것만 따라잡을 때 쓴다(이슈 #190).
  return afterSeq === undefined ? base : `${base}?afterSeq=${afterSeq}`;
};

/** 협업채팅 WS 핸드셰이크 경로 — 서버 WsHandlerMappingConfig의 매핑과 같아야 한다(이슈 #3). */
export const WS_PATH = '/api/ws';

/**
 * 목업 WS 서버(mocks/ws-server.ts) 주소. WS는 Route Handler로 흉내 낼 수 없어 별도 프로세스로
 * 뜨므로, HTTP 목업을 가르는 BFF_BASE_URL과 달리 자기 토글이 따로 필요하다 — 이 값을 채우면
 * HTTP는 여전히 동일 오리진 목업 라우트를 쓰면서 WS만 목업 서버로 간다.
 * 이미 ws(s) 스킴이라 아래 스킴 치환을 거치지 않는다.
 */
export const MOCK_WS_BASE_URL = process.env.NEXT_PUBLIC_MOCK_WS_URL ?? '';

/**
 * WS 핸드셰이크용 절대 URL. WebSocket 생성자는 http(s)를 받지 않으므로 스킴을 ws(s)로 바꾼다.
 * BFF_BASE_URL이 비어 있으면(목업 모드) 동일 오리진이라 브라우저의 현재 오리진을 쓴다 —
 * 이 함수는 클라이언트 전용이다(서버 컴포넌트에는 WS를 열 이유가 없다).
 */
export const bffWsUrl = (path: string): string => {
  if (MOCK_WS_BASE_URL) return `${MOCK_WS_BASE_URL}${path}`;
  const base = BFF_BASE_URL || window.location.origin;
  return `${base}${path}`.replace(/^http/, 'ws');
};

/**
 * 협업방 WS 경로 — 어느 방에 들어갈지를 경로 세그먼트로 넘긴다(이슈 #19).
 *
 * 서버가 읽는 방식에 맞춘 것이다. #16(PR #77)의 WsHandlerMappingConfig가 `/api/ws/{threadId}`로
 * 매핑하고 CollabWebSocketHandler가 그 세그먼트를 UUID로 파싱한다 — 쿼리로 보내면 라우팅
 * 자체가 되지 않는다. 그래서 실서버에서는 threadId가 UUID여야 한다(목업은 검증하지 않는다).
 */
export const collabWsPath = (threadId: string): string =>
  `${WS_PATH}/${encodeURIComponent(threadId)}`;

/** 세션별 대화 이력 조회 경로. */
export const chatSessionMessagesPath = (sessionId: string): string =>
  `${CHAT_SESSIONS_PATH}/${sessionId}/messages`;

/** 단일 세션 경로 — 삭제(DELETE)·이름변경(PATCH)에 쓴다. */
export const chatSessionPath = (sessionId: string): string =>
  `${CHAT_SESSIONS_PATH}/${sessionId}`;
