// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import type { Citation } from '@/lib/api/chat';
import { CitationsPanel } from './citations-panel';

afterEach(cleanup);

describe('CitationsPanel', () => {
  it('hides the loading indicator after an empty citation result', () => {
    const { rerender } = render(
      <CitationsPanel state={{ status: 'loading' }} />,
    );
    expect(screen.getByText('근거 검색 중...')).toBeTruthy();

    rerender(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
        }}
      />,
    );

    expect(screen.queryByText('근거 검색 중...')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows a human-readable location next to the title when loc is present', () => {
    render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [
            {
              docId: 'doc-1',
              title: '규정.pdf',
              snippet: '연차는 사흘 전에 신청한다.',
              score: 0.9,
              loc: 'page=3',
            },
          ],
          restrictedResultsOmitted: false,
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText(/3쪽/)).toBeTruthy();
  });

  it('omits the location when loc is missing (past messages) or has an unknown shape', () => {
    render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [
            {
              docId: 'doc-1',
              title: '규정.pdf',
              snippet: '연차는 사흘 전에 신청한다.',
              score: 0.9,
            },
            {
              docId: 'doc-2',
              title: '취업규칙.docx',
              snippet: '제3조 근로시간',
              score: 0.8,
              loc: 'line=5',
            },
          ],
          restrictedResultsOmitted: false,
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText(/쪽$/)).toBeNull();
    expect(screen.queryByText(/번째 문단$/)).toBeNull();
  });

  it('shows a notice when no evidence was found even with no citations', () => {
    render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
          groundingNotice: 'no_evidence',
        }}
      />,
    );

    expect(screen.getByText(/근거를 찾지 못했습니다/)).toBeTruthy();
    expect(screen.queryByText(/일반 지식/)).toBeNull();
  });

  it('shows a different notice when the document search itself failed', () => {
    render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
          groundingNotice: 'unavailable',
        }}
      />,
    );

    expect(screen.getByText(/검색을 하지 못했습니다/)).toBeTruthy();
    expect(screen.queryByText(/일반 지식/)).toBeNull();
  });

  describe('web sources (#401)', () => {
    const document: Citation = {
      kind: 'DOCUMENT',
      docId: 'doc-1',
      title: '규정.pdf',
      snippet: '연차는 사흘 전에 신청한다.',
      score: 0.9,
      chunkId: 'doc-1:1:0',
    };
    const searched: Citation = {
      kind: 'WEB',
      docId: null,
      title: 'Spring Guides',
      snippet: '가이드 모음',
      score: 0,
      url: 'https://www.spring.io/guides',
      query: '스프링 가이드',
      read: true,
    };
    const unsafe: Citation = {
      kind: 'WEB',
      docId: null,
      title: '수상한 링크',
      snippet: '',
      score: 0,
      url: 'javascript:alert(1)',
      query: '스프링 가이드',
      read: false,
    };

    it('shows only the web button when the answer used only the web', () => {
      render(
        <CitationsPanel state={{ status: 'success', citations: [searched] }} />,
      );

      expect(screen.getAllByRole('button')).toHaveLength(1);
      expect(screen.getByRole('button').textContent).toContain(
        '🌐 웹 출처 (1건)',
      );
      expect(screen.queryByText(/근거 검색됨/)).toBeNull();
    });

    it('keeps document citations and web sources under separate buttons', () => {
      render(
        <CitationsPanel
          state={{
            status: 'success',
            citations: [document, searched, unsafe],
            groundingNotice: null,
          }}
        />,
      );

      const buttons = screen.getAllByRole('button');
      expect(buttons.map((button) => button.textContent)).toEqual([
        expect.stringContaining('📚 근거 검색됨 (1건)'),
        expect.stringContaining('🌐 웹 출처 (2건)'),
      ]);

      fireEvent.click(buttons[1]);

      expect(screen.getByText('검색어: “스프링 가이드”')).toBeTruthy();
      const link = screen.getByRole('link', {
        name: /^Spring Guides\s*\(새 탭에서 열림\)$/,
      });
      expect(link.getAttribute('href')).toBe('https://www.spring.io/guides');
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      expect(link.getAttribute('title')).toBe('https://www.spring.io/guides');
      expect(screen.getByText(/spring\.io$/)).toBeTruthy();
      expect(screen.getAllByText('읽음')).toHaveLength(1);
      expect(screen.getByText('수상한 링크').closest('a')).toBeNull();
      expect(screen.queryByText('규정.pdf')).toBeNull();
    });

    it('shows the web button next to the no-evidence notice', () => {
      render(
        <CitationsPanel
          state={{
            status: 'success',
            citations: [searched],
            groundingNotice: 'no_evidence',
          }}
        />,
      );

      expect(screen.getByText(/근거를 찾지 못했습니다/)).toBeTruthy();
      expect(screen.getByRole('button').textContent).toContain('🌐 웹 출처');
    });

    it('ignores citation kinds it does not know', () => {
      const { container } = render(
        <CitationsPanel
          state={{
            status: 'success',
            citations: [{ ...document, kind: 'GENERATED' }],
          }}
        />,
      );

      expect(container.textContent).toBe('');
    });

    it('strips direction overrides from page text and falls back to the host', () => {
      // 방향 바꾸기 문자는 소스에 그대로 두면 보이지 않으니 코드 값으로 만든다.
      const RLO = String.fromCharCode(0x202e);
      const LRI = String.fromCharCode(0x2066);
      render(
        <CitationsPanel
          state={{
            status: 'success',
            citations: [
              {
                ...searched,
                title: `gpj.${RLO}exe`,
                snippet: `요약${RLO}끝`,
                query: `검색${LRI}어`,
              },
              {
                ...searched,
                title: '',
                url: 'https://b.com/x',
                query: null,
                read: false,
              },
            ],
          }}
        />,
      );

      fireEvent.click(screen.getByRole('button'));

      expect(screen.getByText('gpj.exe')).toBeTruthy();
      expect(screen.getByText('요약끝')).toBeTruthy();
      expect(screen.getByText('검색어: “검색어”')).toBeTruthy();
      const hostLink = screen.getByRole('link', {
        name: /^b\.com\s*\(새 탭에서 열림\)$/,
      });
      // 제목 자리에 도메인을 썼으면 " · 도메인"을 또 붙이지 않는다.
      expect(hostLink.parentElement?.textContent).not.toContain('· b.com');
    });

    it('keeps the domain when the title only looks like it', () => {
      render(
        <CitationsPanel
          state={{
            status: 'success',
            citations: [
              { ...searched, title: 'WWW.b.com', url: 'https://b.com/' },
              { ...searched, title: 'wwwxb.com', url: 'https://b.com/x' },
            ],
          }}
        />,
      );

      fireEvent.click(screen.getByRole('button'));

      // "www." 뒤가 같으면 같은 도메인이고, "www" 뒤에 점이 없으면 다른 이름이다.
      expect(screen.getAllByText(/· b\.com$/)).toHaveLength(1);
    });

    it('points aria-controls at the list only while it is open', () => {
      render(
        <CitationsPanel state={{ status: 'success', citations: [searched] }} />,
      );
      const toggle = screen.getByRole('button');

      expect(toggle.getAttribute('aria-controls')).toBeNull();
      fireEvent.click(toggle);
      const listId = toggle.getAttribute('aria-controls');
      expect(listId && window.document.getElementById(listId)).toBeTruthy();
      expect(screen.getByText('읽음').getAttribute('title')).toBe(
        'AI가 이 페이지 본문을 읽었습니다',
      );
    });
  });

  it('renders nothing when there is no citation, no restriction, and no notice', () => {
    const { container } = render(
      <CitationsPanel
        state={{
          status: 'success',
          citations: [],
          restrictedResultsOmitted: false,
          groundingNotice: null,
        }}
      />,
    );

    expect(container.textContent).toBe('');
  });
});
