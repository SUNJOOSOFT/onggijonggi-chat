import { describe, expect, it, vi } from 'vitest';
import {
  FORCED_REFRESH_REQUEST,
  allowsForcedRefresh,
  isForcedRefreshRequest,
  needsProactiveRefresh,
} from './refresh-gate';

const NOW = 1_700_000_000_000;

/** iat·exp를 절대 ms로 지정해 JWT 모양 토큰을 만든다(초 단위로 변환해 인코딩). */
function jwt(issuedAtMs: number, expiresAtMs: number): string {
  const payload = btoa(
    JSON.stringify({
      iat: Math.floor(issuedAtMs / 1000),
      exp: Math.floor(expiresAtMs / 1000),
    }),
  );
  return `h.${payload}.s`;
}

describe('needsProactiveRefresh', () => {
  it('만료 시각을 모르면(account.expires_at 누락) 토큰과 무관하게 리프레시한다', () => {
    expect(needsProactiveRefresh(undefined, undefined, NOW)).toBe(true);
    expect(
      needsProactiveRefresh(jwt(NOW - 300_000, NOW + 300_000), null, NOW),
    ).toBe(true);
    expect(needsProactiveRefresh(undefined, 0, NOW)).toBe(true);
  });

  it('수명 300초 토큰 — 마진 30초(수명의 10%), 최초 하드코딩 값과 같은 경계에서 갈린다', () => {
    const expiresAtMs = NOW + 300_000;
    const token = jwt(NOW, expiresAtMs);
    expect(
      needsProactiveRefresh(token, expiresAtMs, expiresAtMs - 30_000),
    ).toBe(true);
    expect(
      needsProactiveRefresh(token, expiresAtMs, expiresAtMs - 30_001),
    ).toBe(false);
  });

  it('수명 60초 토큰 — 마진이 6초로 비례해서 줄어든다', () => {
    const expiresAtMs = NOW + 60_000;
    const token = jwt(NOW, expiresAtMs);
    expect(needsProactiveRefresh(token, expiresAtMs, expiresAtMs - 6_000)).toBe(
      true,
    );
    expect(needsProactiveRefresh(token, expiresAtMs, expiresAtMs - 6_001)).toBe(
      false,
    );
  });

  it('수명이 극단적으로 짧으면(5초) 마진이 하한(1초)에 걸린다', () => {
    // 5초 * 10% = 0.5초인데 MIN_MARGIN_MS(1초)가 하한이라 1초로 올라간다.
    const expiresAtMs = NOW + 5_000;
    const token = jwt(NOW, expiresAtMs);
    expect(needsProactiveRefresh(token, expiresAtMs, expiresAtMs - 1_000)).toBe(
      true,
    );
    expect(needsProactiveRefresh(token, expiresAtMs, expiresAtMs - 1_001)).toBe(
      false,
    );
  });

  it('토큰이 없거나 파싱 불가면 폴백 마진(30초)을 쓴다', () => {
    const expiresAtMs = NOW + 300_000;
    expect(
      needsProactiveRefresh(undefined, expiresAtMs, expiresAtMs - 30_000),
    ).toBe(true);
    expect(
      needsProactiveRefresh(undefined, expiresAtMs, expiresAtMs - 30_001),
    ).toBe(false);
    expect(
      needsProactiveRefresh('not-a-jwt', expiresAtMs, expiresAtMs - 30_000),
    ).toBe(true);
  });

  it('토큰에 iat 클레임이 없으면(exp만 있음) 폴백 마진을 쓴다', () => {
    const expiresAtMs = NOW + 300_000;
    const expOnly = `h.${btoa(JSON.stringify({ exp: Math.floor(expiresAtMs / 1000) }))}.s`;
    expect(
      needsProactiveRefresh(expOnly, expiresAtMs, expiresAtMs - 30_000),
    ).toBe(true);
    expect(
      needsProactiveRefresh(expOnly, expiresAtMs, expiresAtMs - 30_001),
    ).toBe(false);
  });

  it('now를 생략하면 현재 시각으로 판정한다', () => {
    const expiresAtMs = NOW + 300_000;
    const token = jwt(NOW, expiresAtMs);
    // 마진(30초) 밖 — 아직 리프레시할 필요 없음.
    vi.spyOn(Date, 'now').mockReturnValue(expiresAtMs - 40_000);
    expect(needsProactiveRefresh(token, expiresAtMs)).toBe(false);
    // 마진 안쪽 — 리프레시해야 함.
    vi.spyOn(Date, 'now').mockReturnValue(expiresAtMs - 10_000);
    expect(needsProactiveRefresh(token, expiresAtMs)).toBe(true);
    vi.restoreAllMocks();
  });
});

describe('allowsForcedRefresh', () => {
  it('수명 300초 토큰 — 남은 수명이 절반(150초) 이하일 때만 강제 갱신을 허용한다(이슈 #382)', () => {
    const expiresAtMs = NOW + 300_000;
    const token = jwt(NOW, expiresAtMs);
    expect(allowsForcedRefresh(token, expiresAtMs, expiresAtMs - 150_000)).toBe(
      true,
    );
    expect(allowsForcedRefresh(token, expiresAtMs, expiresAtMs - 150_001)).toBe(
      false,
    );
    // WS가 실제로 묻는 시점(만료 30초 전)은 넉넉히 창 안이다.
    expect(allowsForcedRefresh(token, expiresAtMs, expiresAtMs - 30_000)).toBe(
      true,
    );
  });

  it('수명 60초 토큰 — 허용 창도 30초로 비례해서 줄어든다', () => {
    const expiresAtMs = NOW + 60_000;
    const token = jwt(NOW, expiresAtMs);
    expect(allowsForcedRefresh(token, expiresAtMs, expiresAtMs - 30_000)).toBe(
      true,
    );
    expect(allowsForcedRefresh(token, expiresAtMs, expiresAtMs - 30_001)).toBe(
      false,
    );
  });

  it('기준은 accessTokenExpires가 아니라 토큰의 exp다 — 갱신 왕복만큼 늦게 잡힌 값과 엇갈리지 않는다', () => {
    const expiresAtMs = NOW + 300_000;
    const token = jwt(NOW, expiresAtMs);
    // accessTokenExpires가 exp보다 1초 늦게 잡혀 있어도(갱신 응답을 받은 순간 기준) 판정은 exp로 한다.
    expect(
      allowsForcedRefresh(token, expiresAtMs + 1_000, expiresAtMs - 150_000),
    ).toBe(true);
  });

  it('iat이 없거나 파싱할 수 없으면 기존 마진 판정을 따른다', () => {
    const expiresAtMs = NOW + 300_000;
    const noIat = `h.${btoa(JSON.stringify({ exp: Math.floor(expiresAtMs / 1000) }))}.s`;
    for (const token of [noIat, 'not-a-jwt', undefined]) {
      for (const now of [expiresAtMs - 10_000, expiresAtMs - 100_000]) {
        expect(allowsForcedRefresh(token, expiresAtMs, now)).toBe(
          needsProactiveRefresh(token, expiresAtMs, now),
        );
      }
    }
  });
});

describe('isForcedRefreshRequest', () => {
  it('세션 update에 갱신 요청 표시가 있을 때만 참이다(이슈 #382)', () => {
    expect(isForcedRefreshRequest('update', FORCED_REFRESH_REQUEST)).toBe(true);
    expect(isForcedRefreshRequest('update', { refreshAccessToken: true })).toBe(
      true,
    );
  });

  it('update가 아니거나 다른 용도의 update면 갱신 요청으로 읽지 않는다', () => {
    expect(isForcedRefreshRequest(undefined, FORCED_REFRESH_REQUEST)).toBe(
      false,
    );
    expect(isForcedRefreshRequest('signIn', FORCED_REFRESH_REQUEST)).toBe(
      false,
    );
    for (const data of [
      undefined,
      null,
      'refreshAccessToken',
      { name: '홍길동' },
      { refreshAccessToken: 'true' },
    ]) {
      expect(isForcedRefreshRequest('update', data)).toBe(false);
    }
  });
});
