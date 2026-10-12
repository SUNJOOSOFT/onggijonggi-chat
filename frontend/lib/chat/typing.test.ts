/********************************************************
 파일명 : typing.test.ts (lib/chat)
 설 명 : 입력 중 목록을 프레임으로 접고 만료시키는 규칙과 표시 문구를 본다.
 *********************************************************/

import type { WsFrame } from '@/lib/transport/frames';
import { describe, expect, it } from 'vitest';
import {
  TYPING_TTL_MS,
  applyTypingFrame,
  pruneTyping,
  typingLabel,
} from './typing';

function typing(subject: string, active: boolean): WsFrame {
  return {
    type: 'chat.typing',
    threadId: 't',
    subject,
    displayName: `${subject} 팀장`,
    active,
  };
}

describe('applyTypingFrame', () => {
  it('입력 중 알림은 사람을 넣고, 다시 오면 자리는 두고 만료만 늦춘다', () => {
    const first = applyTypingFrame([], typing('a', true), 0);
    const both = applyTypingFrame(first, typing('b', true), 100);
    const refreshed = applyTypingFrame(both, typing('a', true), 2_000);

    expect(refreshed.map((peer) => peer.subject)).toEqual(['a', 'b']);
    expect(refreshed[0].until).toBe(2_000 + TYPING_TTL_MS);
  });

  it('멈춤 알림·그 사람의 메시지·퇴장은 그 사람을 뺀다', () => {
    const peers = applyTypingFrame(
      applyTypingFrame([], typing('a', true), 0),
      typing('b', true),
      0,
    );

    expect(applyTypingFrame(peers, typing('a', false), 0)).toEqual([peers[1]]);
    expect(
      applyTypingFrame(
        peers,
        {
          type: 'chat.message',
          threadId: 't',
          msgId: 'm',
          clientMsgId: null,
          turnId: null,
          seq: 1,
          from: 'b',
          fromDisplayName: 'b 팀장',
          content: '안녕',
          attachments: [],
        },
        0,
      ),
    ).toEqual([peers[0]]);
    expect(
      applyTypingFrame(
        peers,
        {
          type: 'presence.leave',
          threadId: 't',
          subject: 'a',
          displayName: 'a 팀장',
        },
        0,
      ),
    ).toEqual([peers[1]]);
  });

  it('상관없는 프레임은 같은 배열을 돌려준다', () => {
    const peers = applyTypingFrame([], typing('a', true), 0);
    expect(applyTypingFrame(peers, { type: 'pong' }, 0)).toBe(peers);
    expect(applyTypingFrame(peers, typing('z', false), 0)).toBe(peers);
  });
});

describe('pruneTyping', () => {
  it('만료된 사람만 빼고, 그대로면 같은 배열을 돌려준다', () => {
    const peers = applyTypingFrame(
      applyTypingFrame([], typing('a', true), 0),
      typing('b', true),
      1_000,
    );

    expect(pruneTyping(peers, TYPING_TTL_MS - 1)).toBe(peers);
    expect(
      pruneTyping(peers, TYPING_TTL_MS).map((peer) => peer.subject),
    ).toEqual(['b']);
  });
});

describe('typingLabel', () => {
  const peer = (name: string) => ({
    subject: name,
    displayName: name,
    until: 0,
  });

  it('인원수에 따라 문구를 바꾼다', () => {
    expect(typingLabel([])).toBeNull();
    expect(typingLabel([peer('송강호')])).toBe('송강호 님이 입력 중…');
    expect(typingLabel([peer('송강호'), peer('이한결')])).toBe(
      '송강호, 이한결 님이 입력 중…',
    );
    expect(typingLabel([peer('a'), peer('b'), peer('c')])).toBe(
      '여러 명이 입력 중…',
    );
  });
});
