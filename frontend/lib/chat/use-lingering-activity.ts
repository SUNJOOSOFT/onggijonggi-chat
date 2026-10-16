/********************************************************
 파일명 : use-lingering-activity.ts (lib/chat)
 설 명 : 도구 진행 표시를 잠깐 남겨 두는 훅(이슈 #397). 도구가 연달아 돌면(검색 → 페이지 읽기 → …) "끝"과 다음
 "시작" 사이에 표시가 비었다가 다시 채워져, 문구가 깜박이고 줄이 사라졌다 생기며 화면이 흔들린다. 지워질 때만
 잠깐 기다렸다가 지우고, 그 사이 다음 도구가 시작하면 바로 그 문구로 바꾼다. 방 상태(room-state.ts)는 그대로
 두고 보이는 것만 늦춘다 — 상태는 사실을, 화면은 보기 좋게.
 *********************************************************/

import { useEffect, useState } from 'react';

import type { RoomActivity } from './room-state';

/** 도구 사이 빈틈을 덮을 만큼만 — 길면 끝난 작업이 남아 보인다. */
export const ACTIVITY_LINGER_MS = 300;

export function useLingeringActivity(
  activity: RoomActivity | null,
  lingerMs: number = ACTIVITY_LINGER_MS,
): RoomActivity | null {
  const [shown, setShown] = useState<RoomActivity | null>(activity);

  useEffect(() => {
    if (activity) {
      setShown(activity);
      return;
    }
    const timer = setTimeout(() => setShown(null), lingerMs);
    return () => clearTimeout(timer);
  }, [activity, lingerMs]);

  return activity ?? shown;
}
