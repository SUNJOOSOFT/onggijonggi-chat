/********************************************************
 파일명 : activity.test.ts (lib/chat)
 설 명 : 도구 실행 중 표시 문구가 종류 코드로 골라지고 대상이 붙는지, 모르는 코드면 일반 문구인지(이슈 #397).
 *********************************************************/

import { describe, expect, it } from 'vitest';
import { activityLabel } from './activity';

describe('activityLabel', () => {
  it('종류별 문구에 대상을 붙인다', () => {
    expect(activityLabel('WEB_SEARCH', '서울 날씨')).toBe(
      '웹 검색 중… 서울 날씨',
    );
    expect(activityLabel('WEB_FETCH', 'https://example.org')).toBe(
      '페이지 읽는 중… https://example.org',
    );
    expect(activityLabel('DOCUMENT_SEARCH', '연장근로')).toBe(
      '방 문서 찾는 중… 연장근로',
    );
    expect(activityLabel('DOCUMENT_REVISE', '규정.docx')).toBe(
      '개정본 만드는 중… 규정.docx',
    );
  });

  it('주소의 %인코딩은 풀어서 보이고, 풀 수 없으면 그대로 둔다', () => {
    expect(
      activityLabel(
        'WEB_FETCH',
        'https://ko.wikipedia.org/wiki/%EA%B5%AD%ED%9A%8C',
      ),
    ).toBe('페이지 읽는 중… https://ko.wikipedia.org/wiki/국회');
    expect(activityLabel('WEB_FETCH', 'https://x.org/%E0%A4%A')).toBe(
      '페이지 읽는 중… https://x.org/%E0%A4%A',
    );
  });

  it('풀면 제어·방향 바꾸기 문자가 드러나는 주소는 풀지 않는다', () => {
    expect(activityLabel('WEB_FETCH', 'https://x.org/a%0Ab')).toBe(
      '페이지 읽는 중… https://x.org/a%0Ab',
    );
    expect(activityLabel('WEB_FETCH', 'https://x.org/%E2%80%AEfdp.exe')).toBe(
      '페이지 읽는 중… https://x.org/%E2%80%AEfdp.exe',
    );
  });

  it('검색어·파일 이름의 방향 바꾸기 문자는 빼고 보인다', () => {
    expect(
      activityLabel(
        'DOCUMENT_REVISE',
        `규정${String.fromCodePoint(0x202e)}xcod.exe`,
      ),
    ).toBe('개정본 만드는 중… 규정xcod.exe');
  });

  it('대상이 없으면 문구만, 모르는 코드면 일반 문구', () => {
    expect(activityLabel('WEB_SEARCH', null)).toBe('웹 검색 중…');
    expect(activityLabel('DOCUMENT_GENERATE', '보고서')).toBe(
      '작업 중… 보고서',
    );
    expect(activityLabel('TOOL', null)).toBe('작업 중…');
  });
});
