/********************************************************
 파일명 : auth.ts
 설 명 : next-auth(Auth.js) v5 설정 — Keycloak OIDC 로그인/세션/보호 라우트/리프레시 로테이션/로그아웃.
 middleware.ts와 app/api/auth/[...nextauth]/route.ts가 이 파일을 그대로 가져다 쓴다 —
 next-auth 설정의 단일 진실 원천은 이 파일뿐이다.
 *********************************************************/

import {
  allowsForcedRefresh,
  isForcedRefreshRequest,
  needsProactiveRefresh,
} from '@/lib/auth/refresh-gate';
import NextAuth from 'next-auth';
import type { JWT } from 'next-auth/jwt';
import Keycloak from 'next-auth/providers/keycloak';

// KEYCLOAK_ISSUER는 브라우저가 보는 공개 주소라 컨테이너 안에서는 닿지 못한다. 도커로 띄울
// 때만 내부 주소가 주입되고, 호스트에서 직접 개발할 때는 비어 있어 공개 주소로 되돌아간다.
const INTERNAL_ISSUER =
  process.env.KEYCLOAK_INTERNAL_ISSUER ?? process.env.KEYCLOAK_ISSUER;

/** Keycloak의 refresh_token 그랜트로 accessToken을 갱신한다. 실패해도 예외를 던지지 않고
 * token.error='RefreshAccessTokenError'로 표시만 한다 — 여기서 던지면 next-auth가 세션
 * 자체를 깨뜨리므로, 실패 신호를 session 콜백까지 옮겨 클라이언트(authFetch)가 감지해 재로그인시키게 한다. */
async function refreshAccessToken(token: JWT): Promise<JWT> {
  try {
    const response = await fetch(
      `${INTERNAL_ISSUER}/protocol/openid-connect/token`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'refresh_token',
          refresh_token: token.refreshToken as string,
          client_id: process.env.KEYCLOAK_CLIENT_ID as string,
          client_secret: process.env.KEYCLOAK_CLIENT_SECRET as string,
        }),
      },
    );
    // 앞단 프록시가 HTML 오류 페이지를 주면 파싱 오류 메시지에 본문이 섞이고 상태 코드도 잃는다 —
    // 빈 본문으로 보고 아래 실패 처리로 넘긴다.
    const refreshed = await response.json().catch(() => ({}));
    // 성공 응답인데 access_token이 없으면 실패로 간주한다 — "error 없이 토큰만 사라지는" 상태를 막는다.
    if (!response.ok || !refreshed.access_token) {
      throw { status: response.status, error: refreshed.error };
    }

    return {
      ...token,
      accessToken: refreshed.access_token,
      accessTokenExpires: Date.now() + refreshed.expires_in * 1000,
      // Keycloak이 로테이션 정책에 따라 새 refresh_token을 안 줄 수도 있으므로 기존 값 유지.
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      error: undefined,
    };
  } catch (failure) {
    // 운영에서 갱신 실패로 세션이 끊기면 원인을 볼 곳이 있어야 한다 — 응답 상태와 오류 이름만 남기고
    // 토큰 값은 남기지 않는다(이슈 #382). 네트워크 오류는 상태 없이 메시지만 남는다.
    const { status, error } = (failure ?? {}) as {
      status?: number;
      error?: string;
    };
    console.warn(
      `[auth] Keycloak 토큰 갱신 실패 status=${status ?? '-'} error=${error ?? (failure instanceof Error ? failure.message : '-')}`,
    );
    return { ...token, error: 'RefreshAccessTokenError' as const };
  }
}

// env 이름은 infra/docker-compose.yml과 맞춘다(next-auth v5의 AUTH_* 자동 감지 대신 명시 지정).
export const { handlers, auth, signIn, signOut } = NextAuth({
  secret: process.env.NEXTAUTH_SECRET,
  providers: [
    Keycloak({
      clientId: process.env.KEYCLOAK_CLIENT_ID,
      clientSecret: process.env.KEYCLOAK_CLIENT_SECRET,
      // issuer는 공개 주소 그대로 둔다 — 토큰의 iss 클레임과 대조하는 값이다.
      issuer: process.env.KEYCLOAK_ISSUER,
      // 엔드포인트를 직접 지정해 discovery를 건너뛴다 — discovery에 맡기면 issuer 한 주소로
      // 브라우저용(authorization)과 서버용(token·userinfo)을 모두 부르게 돼 컨테이너에서 실패한다.
      authorization: `${process.env.KEYCLOAK_ISSUER}/protocol/openid-connect/auth`,
      token: `${INTERNAL_ISSUER}/protocol/openid-connect/token`,
      userinfo: `${INTERNAL_ISSUER}/protocol/openid-connect/userinfo`,
    }),
  ],
  callbacks: {
    // middleware.ts에서 `auth`를 그대로 쓸 때 이 콜백이 게이트 역할을 한다.
    // false 반환 시 next-auth가 로그인 페이지로 리다이렉트한다.
    authorized({ auth }) {
      return !!auth?.user;
    },
    // accessToken은 세션까지, refreshToken은 JWT(httpOnly 쿠키)에만 두고 만료 임박 시
    // 여기서 선제 로테이션한다. 매 요청(authFetch의 getSession())이 이 콜백을 거치므로
    // 별도 배치/타이머 없이 "다음 요청 전 선제 리프레시"가 된다. 다만 WS 선제 갱신은 연결을 오래 붙잡아
    // 경계에서 옛 토큰을 받으면 끊기므로, POST(update)로 갱신을 직접 요청한다(이슈 #382).
    async jwt({ token, account, profile, trigger, session }) {
      if (account) {
        // 사이드바의 내 이름. Keycloak 기본 name은 "이름 성" 순서라 성+이름으로 붙인다 — 서버의 표시 이름
        // (PersonNames)과 같은 규칙이다. 둘 다 없으면 NextAuth가 채운 name(없으면 username)을 그대로 둔다.
        const fullName =
          `${profile?.family_name ?? ''}${profile?.given_name ?? ''}`.trim();
        if (fullName) token.name = fullName;
        token.idToken = account.id_token;
        token.accessToken = account.access_token;
        token.refreshToken = account.refresh_token;
        token.accessTokenExpires = account.expires_at
          ? account.expires_at * 1000
          : undefined;
        return token;
      }

      // WS 선제 갱신이 직접 요청한 갱신(이슈 #382). 마진 판정 대신 허용 창(수명의 절반)만 본다 — 묻는
      // 시점(토큰 exp·브라우저 시계)과 마진 판정(accessTokenExpires·서버 시계)의 기준이 달라 경계에서
      // 옛 토큰을 돌려주던 문제를 없앤다. session 본문은 갱신 요청 표시로만 읽고 토큰에 합치지 않는다.
      if (isForcedRefreshRequest(trigger, session)) {
        return allowsForcedRefresh(token.accessToken, token.accessTokenExpires)
          ? refreshAccessToken(token)
          : token;
      }

      if (!needsProactiveRefresh(token.accessToken, token.accessTokenExpires)) {
        return token;
      }
      return refreshAccessToken(token);
    },
    async session({ session, token }) {
      session.idToken = token.idToken as string | undefined;
      session.accessToken = token.accessToken as string | undefined;
      // 리프레시 실패를 클라이언트(authFetch)가 감지해 재로그인시킬 수 있게 error를 노출한다.
      session.error = token.error as 'RefreshAccessTokenError' | undefined;
      return session;
    },
  },
});
