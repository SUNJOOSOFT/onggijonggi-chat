/********************************************************
 파일명 : typing.ts (lib/chat)
 설 명 : 협업방의 "입력 중" 표시. 받은 chat.typing으로 지금 입력 중인 사람 목록을 접고, 보내는 쪽이
 얼마나 자주 다시 알릴지를 정한다.

 순수 상태(room-state.ts)와 따로 두는 이유는 만료가 있어서다. 멈췄다는 알림(active=false)은 탭을
 닫거나 끊기면 오지 않으므로, 받는 쪽은 갱신이 TTL만큼 끊기면 스스로 지운다. 보내는 쪽은 입력하는
 동안 RESEND 간격으로 다시 알려 그 TTL을 이어 간다.
 *********************************************************/

import type { WsFrame } from '@/lib/transport/frames';

/** 입력하는 동안 다시 알리는 간격. */
export const TYPING_RESEND_MS = 3_000;

/** 마지막 알림 뒤 이만큼 소식이 없으면 멈춘 것으로 본다. 다시 알리는 간격보다 넉넉히 길어야 한 번
 * 늦게 도착해도 깜빡이지 않는다. */
export const TYPING_TTL_MS = 6_000;

export interface TypingPeer {
  subject: string;
  displayName: string;
  /** 이 시각(ms)이 지나면 지운다. */
  until: number;
}

/**
 * 프레임 하나를 입력 중 목록에 접는다. 입력 중 알림은 그 사람을 넣거나 만료를 늦추고, 멈춤 알림·
 * 그 사람의 메시지 도착·퇴장은 그 사람을 뺀다. 메시지가 오면 빼는 이유는, 보낸 직후의 멈춤 알림을
 * 기다리지 않아도 "입력 중"이 말풍선과 함께 사라지게 하려는 것이다.
 */
export function applyTypingFrame(
  peers: TypingPeer[],
  frame: WsFrame,
  now: number,
): TypingPeer[] {
  let subject: string;
  switch (frame.type) {
    case 'chat.typing':
      subject = frame.subject;
      break;
    case 'chat.message':
      subject = frame.from;
      break;
    case 'presence.leave':
      subject = frame.subject;
      break;
    default:
      return peers;
  }
  const others = peers.filter((peer) => peer.subject !== subject);
  if (frame.type === 'chat.typing' && frame.active) {
    const existing = peers.find((peer) => peer.subject === subject);
    const peer = {
      subject,
      displayName: frame.displayName,
      until: now + TYPING_TTL_MS,
    };
    // 이미 있던 사람은 자리를 지킨다 — 갱신마다 순서가 바뀌면 문구가 흔들린다.
    return existing
      ? peers.map((current) => (current.subject === subject ? peer : current))
      : [...peers, peer];
  }
  return others.length === peers.length ? peers : others;
}

/** 만료된 사람을 뺀다. 바뀐 것이 없으면 같은 배열을 돌려준다(불필요한 렌더를 막는다). */
export function pruneTyping(peers: TypingPeer[], now: number): TypingPeer[] {
  const alive = peers.filter((peer) => peer.until > now);
  return alive.length === peers.length ? peers : alive;
}

/** 입력창 위에 띄울 문구. 아무도 없으면 null. */
export function typingLabel(peers: TypingPeer[]): string | null {
  if (peers.length === 0) return null;
  if (peers.length === 1) return `${peers[0].displayName} 님이 입력 중…`;
  if (peers.length === 2) {
    return `${peers[0].displayName}, ${peers[1].displayName} 님이 입력 중…`;
  }
  return '여러 명이 입력 중…';
}
