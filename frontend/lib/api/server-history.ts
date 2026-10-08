/********************************************************
 파일명 : server-history.ts (lib/api)
 설 명 : 서버 컴포넌트 전용 BFF 조회(채팅 이력·모델 목록). http.ts의 authFetch는 클라이언트 전용 getSession()을
 써서 서버 컴포넌트에선 못 쓴다 — 여기선 @/auth의 auth()(서버사이드)로 세션을 얻어
 accessToken을 직접 Bearer로 붙인다. SSR 1회성 조회라 401 재시도·429 백오프는 두지 않는다.
 인증 만료(session.error·401)는 REAUTH_REQUIRED로 구분해 null을 반환한다 — 호출부가 이를
 "빈 목록"과 구분해 재인증 안내를 보여줄 수 있어야 한다.
 *********************************************************/

import { auth } from '@/auth';
import {
  CHAT_SESSIONS_PATH,
  MODELS_PATH,
  chatSessionMessagesPath,
  serverBffUrl,
} from './config';

const REAUTH_REQUIRED = Symbol('reauth-required');

/** GET /api/chat/sessions 응답 항목. */
export interface ChatSessSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

/** GET /api/chat/sessions/{id}/messages 응답 항목. */
export interface ChatMsgItem {
  id: string;
  role: string;
  content: string;
  createdAt: string;
}

/**
 * 인증 세션의 accessToken을 Bearer로 붙여 BFF를 GET한다. 세션이 없거나 BFF에 연결 자체가
 * 안 되면(이슈 #313) null, 리프레시 실패(session.error)나 401 응답이면 REAUTH_REQUIRED를
 * 반환한다 — REAUTH_REQUIRED는 "재인증 필요"를 뜻하지만 세션이 아예 없는 경우(미들웨어가
 * 이미 걸러내는 경로)와는 구분해둔다.
 */
async function authorizedGet(
  path: string,
): Promise<Response | null | typeof REAUTH_REQUIRED> {
  const session = await auth();
  if (!session?.accessToken) return null;
  // 리프레시가 이미 실패로 확정된 세션은 401로 왕복할 뿐이니 요청 자체를 생략한다.
  if (session.error === 'RefreshAccessTokenError') return REAUTH_REQUIRED;
  let res: Response;
  try {
    res = await fetch(serverBffUrl(path), {
      headers: { Authorization: `Bearer ${session.accessToken}` },
      cache: 'no-store',
    });
  } catch {
    // BFF가 응답조차 못 하면(연결 거부 등) fetch()가 던진다 — 4xx/5xx 응답과 같은 경로로
    // 흡수해 서버 컴포넌트가 그대로 죽지 않게 한다(이슈 #313).
    return null;
  }
  if (res.status === 401) return REAUTH_REQUIRED;
  return res;
}

/** 로그인 사용자의 세션 목록을 서버 컴포넌트에서 미리 조회한다(사이드바 초기 렌더용).
 * 반환 null은 "재인증 필요"를 뜻한다 — 진짜 빈 목록([])과 호출부가 구분해야 한다. */
export async function fetchSessionsForServer(): Promise<
  ChatSessSummary[] | null
> {
  const res = await authorizedGet(CHAT_SESSIONS_PATH);
  if (res === REAUTH_REQUIRED) return null;
  if (!res?.ok) return [];
  return res.json() as Promise<ChatSessSummary[]>;
}

/**
 * 게이트웨이가 서빙 중인 모델 목록을 서버 컴포넌트에서 조회한다(모델 선택 드롭다운 초기 렌더용).
 * 이력 조회와 달리 재인증 필요와 빈 목록을 구분하지 않고 []로 합친다 — 목록이 없으면 어차피
 * 선택기를 그릴 수 없고, 재인증은 같은 화면의 이력 조회가 이미 감지해 안내하기 때문이다.
 */
export async function fetchModelsForServer(): Promise<string[]> {
  const res = await authorizedGet(MODELS_PATH);
  if (res === REAUTH_REQUIRED) return [];
  if (!res?.ok) return [];
  return res.json() as Promise<string[]>;
}

/** 특정 세션의 메시지 목록을 서버 컴포넌트에서 미리 조회한다(chat/[id] 진입 시 초기 메시지용).
 * 반환 null은 "재인증 필요"를 뜻한다 — 진짜 빈 목록([])과 호출부가 구분해야 한다. */
export async function fetchSessionMessagesForServer(
  sessionId: string,
): Promise<ChatMsgItem[] | null> {
  const res = await authorizedGet(chatSessionMessagesPath(sessionId));
  if (res === REAUTH_REQUIRED) return null;
  if (!res?.ok) return [];
  return res.json() as Promise<ChatMsgItem[]>;
}
