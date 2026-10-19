/********************************************************
 파일명 : visible-text.test.ts (lib/chat)
 설 명 : 밖에서 온 글자의 제어·방향 바꾸기 문자 빼기와 주소 %인코딩 풀기(이슈 #397·#401).
 *********************************************************/

import { describe, expect, it } from 'vitest';
import { readableUrl, withoutDisguising } from './visible-text';

describe('withoutDisguising', () => {
  it('제어 문자와 방향 바꾸기 문자를 뺀다', () => {
    // 보이지 않는 글자는 소스에 그대로 두면 리뷰에서 안 보이니 코드 값으로 만든다.
    const [RLO, BEL, LRI, RLM, NEL, LS] = [
      0x202e, 0x07, 0x2066, 0x200f, 0x85, 0x2028,
    ].map((code) => String.fromCharCode(code));
    expect(withoutDisguising(`gpj.${RLO}exe`)).toBe('gpj.exe');
    expect(withoutDisguising(`a${BEL}b${LRI}c${RLM}d${NEL}e${LS}f`)).toBe(
      'abcdef',
    );
    expect(withoutDisguising('한글 😀 그대로')).toBe('한글 😀 그대로');
  });
});

describe('readableUrl', () => {
  it('%인코딩을 풀되, 풀면 숨은 글자가 드러나는 주소는 그대로 둔다', () => {
    expect(readableUrl('https://a.com/%EC%97%B0%EC%B0%A8')).toBe(
      'https://a.com/연차',
    );
    expect(readableUrl('https://a.com/%E2%80%AEexe')).toBe(
      'https://a.com/%E2%80%AEexe',
    );
    expect(readableUrl('https://a.com/%E0%A4%A')).toBe(
      'https://a.com/%E0%A4%A',
    );
  });

  it('서버가 잘라 끝에 반쯤 남은 %인코딩은 떼고 푼다', () => {
    expect(
      readableUrl('https://law.go.kr/%EB%B2%95%EB%A0%B9/%EC%A0%9C60%EC%A1…'),
    ).toBe('https://law.go.kr/법령/제60…');
    expect(readableUrl('https://a.com/%F0%9F%98%8…')).toBe('https://a.com/…');
    expect(readableUrl('https://a.com/%EC%97%B0…')).toBe('https://a.com/연…');
  });
});
