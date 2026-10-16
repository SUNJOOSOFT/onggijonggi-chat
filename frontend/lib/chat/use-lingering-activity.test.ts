// @vitest-environment jsdom
/********************************************************
 파일명 : use-lingering-activity.test.ts (lib/chat)
 설 명 : 도구 진행 표시가 지워질 때만 잠깐 남고, 그 사이 다음 도구가 시작하면 바로 바뀌는지(이슈 #397).
 *********************************************************/

import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { RoomActivity } from './room-state';
import {
  ACTIVITY_LINGER_MS,
  useLingeringActivity,
} from './use-lingering-activity';

const search: RoomActivity = {
  msgId: 'm1',
  kind: 'WEB_SEARCH',
  target: '서울 날씨',
  agent: null,
};
const fetchPage: RoomActivity = {
  msgId: 'm1',
  kind: 'WEB_FETCH',
  target: 'https://example.org',
  agent: null,
};

describe('useLingeringActivity', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('지워지면 잠깐 남았다가 사라진다', () => {
    const { result, rerender } = renderHook(
      ({ activity }) => useLingeringActivity(activity),
      { initialProps: { activity: search as RoomActivity | null } },
    );
    rerender({ activity: null });
    expect(result.current).toBe(search);
    act(() => vi.advanceTimersByTime(ACTIVITY_LINGER_MS));
    expect(result.current).toBeNull();
  });

  it('남아 있는 사이 다음 도구가 시작하면 바로 그 문구로 바뀐다', () => {
    const { result, rerender } = renderHook(
      ({ activity }) => useLingeringActivity(activity),
      { initialProps: { activity: search as RoomActivity | null } },
    );
    rerender({ activity: null });
    act(() => vi.advanceTimersByTime(ACTIVITY_LINGER_MS / 2));
    rerender({ activity: fetchPage });
    expect(result.current).toBe(fetchPage);
    act(() => vi.advanceTimersByTime(ACTIVITY_LINGER_MS));
    expect(result.current).toBe(fetchPage);
  });
});
