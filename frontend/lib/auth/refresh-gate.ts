/********************************************************
 파일명 : refresh-gate.ts (lib/auth)
 설 명 : accessToken을 언제 선제 리프레시할지(auth.ts) / 언제 "근-만료"로 볼지(ws-connection.ts)
 판정한다(이슈 #181). 두 판정 모두 Keycloak 서버 설정을 몰라도 되게 만든다 — JWT 자체가
 iat·exp 클레임으로 자기 수명을 알려주므로, 마진을 그 수명에 비례해 잡으면 Access Token
 Lifespan이 나중에 바뀌어도(운영자가 Admin Console에서 손으로 바꿔도) 코드 변경 없이
 따라간다. 최초 구현은 마진을 30초로 고정했는데, 그건 그 순간의 실서버 값(300초)에 맞춘
 것일 뿐이라 lifespan이 바뀌면 다시 안 맞는다 — 2026-09-11 지적으로 비례식으로 교체했다.

 WS 선제 갱신이 서버에 갱신을 직접 요청할 때의 허용 창(남은 수명이 절반 이하, 이슈 #382)도 여기서 판정한다.

 auth.ts에서도 쓰지만 별도 모듈로 뗀 이유는 그대로다 — auth.ts는 next-auth를 로드해
 vitest(node) 환경에서 import되지 않는다(`Cannot find module 'next/server'`).
 *********************************************************/

/** JWT payload에서 읽은 iat(발급)·exp(만료). issuedAtMs는 iat 클레임이 없으면 null —
 * exp만 있어도 잔여 수명(스큐)은 계산할 수 있어야 하기 때문이다. */
export interface JwtTimes {
  issuedAtMs: number | null;
  expiresAtMs: number;
}

/** JWT payload를 디코드해 iat·exp를 읽는다. exp가 없거나 파싱에 실패하면 null. */
export function decodeJwtTimes(
  token: string | null | undefined,
): JwtTimes | null {
  if (!token) return null;
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    if (typeof payload.exp !== 'number') return null;
    return {
      issuedAtMs: typeof payload.iat === 'number' ? payload.iat * 1000 : null,
      expiresAtMs: payload.exp * 1000,
    };
  } catch {
    return null;
  }
}

/** 선제 리프레시 마진을 토큰 수명(exp - iat)의 이 비율로 잡는다. backend
 * KeycloakAdminClient.EXPIRY_SAFETY_MARGIN이 물려준 건 "30초"라는 절대값이 아니라
 * "수명 300초에서 30초"라는 비율이다 — 그 비율만 가져온다. */
const MARGIN_FRACTION = 0.1;

/** 마진 하한. 수명이 극단적으로 짧은 토큰이어도 최소 이만큼은 남기고 리프레시해 네트워크
 * 왕복 여유를 준다. */
const MIN_MARGIN_MS = 1_000;

/** 토큰을 디코드할 수 없을 때(파싱 실패, iat 클레임 없음)만 쓰는 폴백. 실 Keycloak 토큰은
 * 항상 iat·exp를 갖고 있어 정상 운영에서는 거의 타지 않는 경로다. */
const FALLBACK_MARGIN_MS = 30_000;

/** accessToken의 실제 잔여 수명 대비 선제 리프레시 마진(ms). 디코드 가능하고 iat도 있으면
 * 그 토큰 수명의 MARGIN_FRACTION(하한 MIN_MARGIN_MS), 아니면 FALLBACK_MARGIN_MS. */
export function proactiveRefreshMarginMs(
  accessToken: string | null | undefined,
): number {
  const times = decodeJwtTimes(accessToken);
  if (!times || times.issuedAtMs === null) return FALLBACK_MARGIN_MS;
  return Math.max(
    MIN_MARGIN_MS,
    (times.expiresAtMs - times.issuedAtMs) * MARGIN_FRACTION,
  );
}

/** 선제 리프레시가 필요한가. 만료까지 {@link proactiveRefreshMarginMs} 미만 남았거나, 만료
 * 시각을 아예 모르면(로그인 때 `account.expires_at` 누락 등) `true`. `now`는 테스트 주입용. */
export function needsProactiveRefresh(
  accessToken: string | null | undefined,
  expiresAtMs: number | undefined | null,
  now: number = Date.now(),
): boolean {
  if (!expiresAtMs) return true;
  return now >= expiresAtMs - proactiveRefreshMarginMs(accessToken);
}

/** WS 선제 갱신이 POST /api/auth/session의 data로 보내는 갱신 요청 표시(이슈 #382). 보내는 쪽
 * (ws-connection.ts)과 읽는 쪽(auth.ts의 jwt 콜백)이 이 값 하나를 함께 써서 서로 어긋나지 않게 한다. */
export const FORCED_REFRESH_REQUEST = { refreshAccessToken: true } as const;

/** jwt 콜백이 받은 요청이 WS 선제 갱신의 직접 요청인가. 다른 용도의 세션 update(data가 다른 것)는
 * 갱신 요청으로 읽지 않는다. */
export function isForcedRefreshRequest(
  trigger: string | undefined,
  data: unknown,
): boolean {
  return (
    trigger === 'update' &&
    typeof data === 'object' &&
    data !== null &&
    (data as { refreshAccessToken?: unknown }).refreshAccessToken ===
      FORCED_REFRESH_REQUEST.refreshAccessToken
  );
}

/** WS 선제 갱신이 서버에 "지금 갱신해"를 직접 요청했을 때(이슈 #382) 갱신해도 되는가. 남은 수명이 토큰
 * 수명의 절반 이하일 때만 허용한다 — 브라우저·서버 시계 차를 넉넉히 흡수하면서, 반복 요청이 Keycloak을
 * 계속 부르지 못하게 한다. 기준은 accessTokenExpires가 아니라 토큰의 exp다 — WS가 갱신 시점을 정할 때
 * 쓰는 것과 같은 기준이라, 갱신 왕복만큼 늦게 잡히는 accessTokenExpires와 경계에서 엇갈리지 않는다.
 * 디코드 실패·iat 없음이면 판단할 수명이 없으니 기존 마진 판정을 따른다. */
export function allowsForcedRefresh(
  accessToken: string | null | undefined,
  expiresAtMs: number | undefined | null,
  now: number = Date.now(),
): boolean {
  const times = decodeJwtTimes(accessToken);
  if (!times || times.issuedAtMs === null) {
    return needsProactiveRefresh(accessToken, expiresAtMs, now);
  }
  return times.expiresAtMs - now <= (times.expiresAtMs - times.issuedAtMs) / 2;
}
