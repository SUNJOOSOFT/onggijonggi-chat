// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

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
});
