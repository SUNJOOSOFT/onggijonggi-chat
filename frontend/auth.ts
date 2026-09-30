/********************************************************
 파일명 : auth.ts
 설 명 : next-auth(Auth.js) v5 설정 — Keycloak OIDC 로그인/세션/보호 라우트/리프레시 로테이션/로그아웃.
 middleware.ts와 app/api/auth/[...nextauth]/route.ts가 이 파일을 그대로 가져다 쓴다 —
 next-auth 설정의 단일 진실 원천은 이 파일뿐이다.
 *********************************************************/

import {
  needsProactiveRefresh,
  proactiveRefreshMarginMs,
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
    const refreshed = await response.json();
    // [#181 계측] 리프레시 결과 — 근본 원인 규명 후 제거한다.
    console.info(
      `[#181][auth] refresh status=${response.status} ok=${response.ok} hasToken=${!!refreshed.access_token} expiresIn=${refreshed.expires_in} err=${refreshed.error ?? 'none'}`,
    );
    // 성공 응답인데 access_token이 없으면 실패로 간주한다 — "error 없이 토큰만 사라지는" 상태를 막는다.
    if (!response.ok || !refreshed.access_token) {
      throw refreshed;
    }

    return {
      ...token,
      accessToken: refreshed.access_token,
      accessTokenExpires: Date.now() + refreshed.expires_in * 1000,
      // Keycloak이 로테이션 정책에 따라 새 refresh_token을 안 줄 수도 있으므로 기존 값 유지.
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      error: undefined,
    };
  } catch {
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
    // 별도 배치/타이머 없이 "다음 요청 전 선제 리프레시"가 된다.
    async jwt({ token, account, profile }) {
      if (account) {
        // 사이드바의 내 이름. Keycloak 기본 name은 "이름 성" 순서라 성+이름으로 붙인다 — 서버의 표시 이름
        // (PersonNames)과 같은 규칙이다. 둘 다 없으면 NextAuth가 채운 name(없으면 username)을 그대로 둔다.
        const fullName = `${profile?.family_name ?? ''}${profile?.given_name ?? ''}`.trim();
        if (fullName) token.name = fullName;
        token.idToken = account.id_token;
        token.accessToken = account.access_token;
        token.refreshToken = account.refresh_token;
        token.accessTokenExpires = account.expires_at
          ? account.expires_at * 1000
          : undefined;
        return token;
      }

      // [#181 계측] accessTokenExpires가 실제로 세팅되는지 + 리프레시 게이트 판정 — 근본 원인 규명 후 제거한다.
      const expIn =
        typeof token.accessTokenExpires === 'number'
          ? Math.round((token.accessTokenExpires - Date.now()) / 1000)
          : 'unset';
      if (!needsProactiveRefresh(token.accessToken, token.accessTokenExpires)) {
        const marginS = Math.round(
          proactiveRefreshMarginMs(token.accessToken) / 1000,
        );
        console.info(
          `[#181][auth] jwt: token still valid, skipping refresh (expIn=${expIn}s, margin=${marginS}s)`,
        );
        return token;
      }

      console.info(
        `[#181][auth] jwt: refreshing (expIn=${expIn}s, prevError=${token.error ?? 'none'})`,
      );
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
