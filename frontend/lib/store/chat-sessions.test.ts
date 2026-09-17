/********************************************************
 파일명 : chat-sessions.test.ts (lib/store)
 설 명 : 세션 스토어에서 조용히 깨지기 쉬운 두 가지를 고정한다.

 하나는 v0 → v1 마이그레이션이다. 기존 사용자의 localStorage에는 임시 id로 저장된 메시지
 사본이 남아 있고, 그대로 두면 코드를 고쳐도 화면의 중복이 사라지지 않는다. 마이그레이션이
 실패해도 화면은 일단 그려지므로 테스트 없이는 티가 나지 않는다.

 다른 하나는 제목 파생 시점이다. 서버도 첫 발화로 제목을 정하지만 사이드바는 마운트당 한 번만
 서버 목록을 읽어, 로컬에서 정해 두지 않으면 새 대화가 새로고침 전까지 "새 대화"로 남는다.
 *********************************************************/

import { describe, expect, it } from 'vitest';
import {
  TITLE_MAX_LENGTH,
  deriveTitle,
  migrateChatSessions,
} from './chat-sessions';

describe('deriveTitle — 첫 발화로 탭 제목을 만든다', () => {
  it('앞뒤 공백을 버리고 본문을 쓴다', () => {
    expect(deriveTitle('  계약서 검토 부탁해요  ')).toBe(
      '계약서 검토 부탁해요',
    );
  });

  it('공백뿐이면 기본 제목으로 둔다 — 빈 탭 이름은 클릭 대상을 잃는다', () => {
    expect(deriveTitle('   ')).toBe('새 대화');
  });

  it('너무 길면 말줄임표로 자른다', () => {
    const title = deriveTitle('가'.repeat(TITLE_MAX_LENGTH + 10));
    expect(title).toBe(`${'가'.repeat(TITLE_MAX_LENGTH)}…`);
  });
});

describe('persist v0 → v1 — 임시 id로 굳은 메시지 사본을 버린다', () => {
  it('세션마다 들고 있던 messages를 떼어낸다', () => {
    const migrated = migrateChatSessions(
      {
        sessions: [
          {
            id: 'a',
            title: '안녕',
            modelId: 'gemma',
            // useChat이 만들던 임시 id — 서버 msgId와 짝이 맞지 않는 사본이다.
            messages: [
              { id: 'v80rBrfO2bn6sfwh', role: 'user', content: '안녕' },
            ],
            createdAt: 1,
            failedMessageIds: [],
            titleCustomized: false,
          },
        ],
        currentSessionId: 'a',
      },
      0,
    );

    const sessions = (migrated as { sessions: Array<Record<string, unknown>> })
      .sessions;
    expect(sessions).toHaveLength(1);
    expect(sessions[0]).not.toHaveProperty('messages');
    // 나머지 필드는 그대로 살아남아야 한다 — 제목·모델을 잃으면 사이드바가 비어 보인다.
    expect(sessions[0].title).toBe('안녕');
    expect(sessions[0].modelId).toBe('gemma');
  });

  it('이미 v1이면 손대지 않는다', () => {
    const state = { sessions: [{ id: 'a', title: '안녕' }] };
    expect(migrateChatSessions(state, 1)).toBe(state);
  });

  it('sessions가 없는 저장본도 그대로 통과시킨다', () => {
    const state = { currentSessionId: null };
    expect(migrateChatSessions(state, 0)).toBe(state);
  });
});
