import { describe, expect, it } from 'vitest';
import {
  bearerFromSubProtocol,
  displayNameFromToken,
  subjectFromToken,
} from './handshake';

/** 서명 없이 payload만 실은 JWT 흉내 — 목업은 검증하지 않으므로 이걸로 충분하다. */
function fakeJwt(claims: Record<string, unknown>): string {
  const payload = Buffer.from(JSON.stringify(claims)).toString('base64url');
  return `header.${payload}.signature`;
}

describe('bearerFromSubProtocol', () => {
  it('두 번째 값을 토큰으로 꺼낸다', () => {
    expect(bearerFromSubProtocol('access_token, abc.def.ghi')).toBe(
      'abc.def.ghi',
    );
  });

  it('쉼표 뒤 공백이 없어도 꺼낸다', () => {
    expect(bearerFromSubProtocol('access_token,abc')).toBe('abc');
  });

  it('첫 값이 약속된 이름이 아니면 null', () => {
    expect(bearerFromSubProtocol('bearer, abc')).toBeNull();
  });

  it('토큰 없이 이름만 오면 null', () => {
    expect(bearerFromSubProtocol('access_token')).toBeNull();
    expect(bearerFromSubProtocol('access_token, ')).toBeNull();
  });

  it('헤더 자체가 없으면 null — 인증 없이 붙으려는 경우다', () => {
    expect(bearerFromSubProtocol(null)).toBeNull();
    expect(bearerFromSubProtocol('')).toBeNull();
  });
});

describe('displayNameFromToken', () => {
  it('preferred_username을 우선 쓴다', () => {
    const token = fakeJwt({ preferred_username: 'alice', sub: 'uuid-1' });
    expect(displayNameFromToken(token)).toBe('alice');
  });

  it('preferred_username이 없으면 sub로 되돌아간다', () => {
    expect(displayNameFromToken(fakeJwt({ sub: 'uuid-1' }))).toBe('uuid-1');
  });

  it('한글 이름도 왕복에서 깨지지 않는다', () => {
    expect(
      displayNameFromToken(fakeJwt({ preferred_username: '주승민' })),
    ).toBe('주승민');
  });

  it('URL-safe 알파벳이 섞인 payload도 그대로 왕복한다', () => {
    // 인코딩된 payload에 base64url 전용 문자(-·_)가 들어가는 값이다. 다만 이 테스트가
    // 'base64url' 플래그를 못 박아 주지는 않는다 — Node의 base64 디코더는 URL-safe
    // 알파벳도 받아주므로 플래그를 'base64'로 바꿔도 통과한다. 왕복이 되는지까지만 본다.
    const name = 'a~b~c??>>>';
    expect(displayNameFromToken(fakeJwt({ preferred_username: name }))).toBe(
      name,
    );
  });

  it('쓸 만한 클레임이 없으면 null — 호출부가 순번 이름으로 되돌아간다', () => {
    expect(displayNameFromToken(fakeJwt({ scope: 'openid' }))).toBeNull();
  });

  it('문자열이 아닌 클레임은 이름으로 쓰지 않는다', () => {
    expect(
      displayNameFromToken(fakeJwt({ preferred_username: 42 })),
    ).toBeNull();
  });

  it('JWT 모양이 아니거나 payload가 깨져 있으면 null', () => {
    expect(displayNameFromToken('dummy')).toBeNull();
    expect(displayNameFromToken('header..signature')).toBeNull();
    expect(displayNameFromToken('header.@@@깨진값@@@.signature')).toBeNull();
  });
});
