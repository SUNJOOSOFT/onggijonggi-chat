/********************************************************
 파일명 : http.ts (lib/api)
 설 명 : BFF 호출용 얇은 fetch 계층. 액세스 토큰을 "매 요청 시점"에 fresh하게 주입해야 하므로
 정적 headers가 아니라 커스텀 fetch로 주입한다(getSession()은 호출마다 세션을 다시 조회).
 401은 세션을 다시 불러 1회 재시도 후 강제 재로그인, 429는 Retry-After 백오프 후 재시도(상한 있음).
 이 전송 계층 재시도는 채팅 스트림(useChat)을 비롯한 authFetch 호출부가 함께 누린다.
 *********************************************************/

import { getSession, signIn } from 'next-auth/react';

interface AuthFetchDeps {
  fetch: typeof fetch;
  getSession: typeof getSession;
  signIn: typeof signIn;
  sleep: (ms: number) => Promise<void>;
}

const defaultDeps: AuthFetchDeps = {
  // 전역 fetch 참조를 그대로 저장해 deps.fetch(...)로 호출하면 this가 deps가 돼
  // "Illegal invocation" TypeError가 난다 → 래퍼로 감싸 전역 바인딩을 보존한다.
  fetch: (input, init) => fetch(input, init),
  getSession,
  signIn,
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

const MAX_RATE_LIMIT_RETRIES = 2;
const RATE_LIMIT_BACKOFF_CAP_MS = 10_000;

/** Retry-After(초) 헤더를 대기 ms로 환산한다. 없거나 숫자가 아니면 지수 백오프(1s, 2s, ...)로 폴백하며,
 * 어느 경우든 상한(CAP)을 적용한다. */
export function retryAfterMs(header: string | null, attempt: number): number {
  const seconds = header ? Number.parseInt(header, 10) : Number.NaN;
  const base = Number.isFinite(seconds) ? seconds * 1000 : 2 ** attempt * 500;
  return Math.min(base, RATE_LIMIT_BACKOFF_CAP_MS);
}

/** Authorization 헤더에 accessToken을 실어 deps.fetch로 전달하는 내부 헬퍼. */
async function requestWithToken(
  input: RequestInfo | URL,
  init: RequestInit | undefined,
  accessToken: string | undefined,
  deps: AuthFetchDeps,
): Promise<Response> {
  const headers = new Headers(init?.headers);
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  return deps.fetch(input, { ...init, headers });
}

/** 401 재시도·재로그인과 429 백오프를 한 루프에서 처리한다. deps는 테스트용 주입 지점이며,
 * 401은 정확히 1회, 429는 MAX_RATE_LIMIT_RETRIES로 상한을 둬 무한루프를 막는다.
 * onForcedReauth는 재로그인이 강제되는 순간 호출되는 콜백(createAuthFetchWithReauthSignal 참고). */
export async function authFetchWithRetry(
  input: RequestInfo | URL,
  init?: RequestInit,
  deps: AuthFetchDeps = defaultDeps,
  onForcedReauth?: () => void,
): Promise<Response> {
  let refreshed401 = false;
  let rateLimitRetries = 0;

  while (true) {
    // getSession()이 jwt 콜백(만료 시 서버측 리프레시)을 거치므로, 매 시도 재조회가
    // "매 요청 fresh"이자 401 후 리프레시가 된다.
    const session = await deps.getSession();

    // 리프레시 실패로 표시된 세션은 어떤 재시도로도 200이 될 수 없다 — 곧장 재로그인으로 보낸다.
    if (session?.error === 'RefreshAccessTokenError') {
      onForcedReauth?.();
      await deps.signIn('keycloak');
      return new Response(null, { status: 401 });
    }

    const response = await requestWithToken(
      input,
      init,
      session?.accessToken,
      deps,
    );

    // 401 → 세션 재조회로 1회 재시도, 그래도 401이면 재로그인.
    if (response.status === 401) {
      if (!refreshed401) {
        refreshed401 = true;
        continue;
      }
      onForcedReauth?.();
      await deps.signIn('keycloak');
      return response;
    }

    // 429 → Retry-After 백오프 재시도(상한까지). 초과하면 429 그대로 반환해 UI가 안내.
    if (response.status === 429 && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
      rateLimitRetries += 1;
      await deps.sleep(
        retryAfterMs(response.headers.get('Retry-After'), rateLimitRetries),
      );
      continue;
    }

    return response;
  }
}

/** BFF 호출용 커스텀 fetch. useChat의 `fetch` 옵션 및 일반 BFF 호출에 사용한다. */
export const authFetch: typeof fetch = (input, init) =>
  authFetchWithRetry(input, init);

/** authFetch와 동일하지만, 재로그인이 강제되는 순간 onForcedReauth를 호출한다.
 * chat.tsx가 그 메시지를 "전송 실패"로 표시할지 판단할 때 이 팩토리로 전용 fetch를 만들어 쓴다. */
export function createAuthFetchWithReauthSignal(
  onForcedReauth: () => void,
): typeof fetch {
  return (input, init) =>
    authFetchWithRetry(input, init, undefined, onForcedReauth);
}
