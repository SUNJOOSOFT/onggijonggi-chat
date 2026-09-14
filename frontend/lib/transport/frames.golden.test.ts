/********************************************************
 파일명 : frames.golden.test.ts (lib/transport)
 설 명 : 서버가 만든 프레임 예시(contracts/ws-frames, 이슈 #160)로 frames.ts·parse-frame.ts와
 목업이 서버 계약을 그대로 따르는지 본다.

 서버 WsFrameGoldenTest가 같은 파일을 "서버가 실제로 이렇게 직렬화한다"로 지키고, 여기서는
 "프론트가 그걸 빠짐없이 받는다"를 지킨다. 둘 중 한쪽만 바뀌면 다른 쪽이 깨진다 — 각자
 자기 쪽만 보던 테스트로는 #129의 participant.changed 누락을 아무도 못 봤다.

 타입 목록은 아래 Record가 컴파일 타임에 frames.ts와 묶는다. 프론트에 타입을 더하거나 빼면
 tsc가, 서버에 타입을 더하거나 빼면 목록 비교가 깨진다.
 *********************************************************/

import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { aiTurnFrames, errorFrame, parseInboundMessage } from '@/mocks/rooms';
import type { ClientFrame, WsFrameType } from './frames';
import { parseFrame } from './parse-frame';

const GOLDEN_ROOT = path.resolve(
  import.meta.dirname,
  '../../../contracts/ws-frames',
);

function goldenFiles(direction: 'outbound' | 'inbound') {
  return readdirSync(path.join(GOLDEN_ROOT, direction))
    .filter((name) => name.endsWith('.json'))
    .map((name) => ({
      type: name.slice(0, -'.json'.length),
      json: JSON.parse(
        readFileSync(path.join(GOLDEN_ROOT, direction, name), 'utf-8'),
      ) as Record<string, unknown>,
    }));
}

/** frames.ts의 WsFrame 타입 전부. 키를 빠뜨리거나 없는 타입을 쓰면 tsc가 깨진다. */
const OUTBOUND_TYPES: Record<WsFrameType, true> = {
  'chat.answer': true,
  'chat.message': true,
  'presence.join': true,
  'presence.leave': true,
  'presence.snapshot': true,
  'participant.changed': true,
  'system.notice': true,
  error: true,
  'chat.queued': true,
  pong: true,
};

/**
 * frames.ts의 ClientFrame 타입마다 모든 필드를 채운 예시. Required라서 선택 필드까지 빠짐없이 적어야
 * 컴파일된다 — 여기 키 목록이 곧 프론트가 아는 필드 목록이다. 값은 비교하지 않는다.
 */
const INBOUND_SAMPLES: {
  [K in ClientFrame['type']]: Required<Extract<ClientFrame, { type: K }>>;
} = {
  'chat.message': {
    type: 'chat.message',
    threadId: '',
    content: '',
    model: '',
    clientMsgId: '',
    turnId: '',
  },
  'chat.cancel': { type: 'chat.cancel', threadId: '', turnId: '' },
  'room.subscribe': { type: 'room.subscribe', threadId: '' },
  'room.unsubscribe': { type: 'room.unsubscribe', threadId: '' },
  ping: { type: 'ping' },
};

describe('서버 → 클라이언트 골든 파일', () => {
  const files = goldenFiles('outbound');

  it('서버가 보내는 타입과 frames.ts가 아는 타입이 같다', () => {
    expect(files.map((file) => file.type).sort()).toEqual(
      Object.keys(OUTBOUND_TYPES).sort(),
    );
  });

  it.each(files)('$type 프레임을 필드 하나 잃지 않고 파싱한다', ({ json }) => {
    // 서버가 필드를 더했는데 스키마가 모르면 zod가 떨궈서 같지 않고, 이름을 바꿨으면 null이다.
    expect(parseFrame(json)).toEqual(json);
  });
});

describe('클라이언트 → 서버 골든 파일', () => {
  const files = goldenFiles('inbound');

  it('서버가 받는 타입과 frames.ts의 ClientFrame 타입이 같다', () => {
    expect(files.map((file) => file.type).sort()).toEqual(
      Object.keys(INBOUND_SAMPLES).sort(),
    );
  });

  it.each(files)('$type 프레임의 필드 이름이 서버와 같다', ({ type, json }) => {
    const sample = INBOUND_SAMPLES[type as ClientFrame['type']];
    expect(Object.keys(json).sort()).toEqual(Object.keys(sample).sort());
  });

  it.each(files)('목업 서버도 $type 프레임을 받아들인다', ({ json }) => {
    expect(parseInboundMessage(JSON.stringify(json)).kind).not.toBe(
      'malformed',
    );
  });
});

describe('목업이 만드는 서버 → 클라이언트 프레임', () => {
  const golden = new Map(
    goldenFiles('outbound').map((file) => [
      file.type,
      Object.keys(file.json).sort(),
    ]),
  );
  // 목업 ws-server.ts가 AI 턴과 오류를 만들 때 쓰는 함수들이다. 나머지 목업 프레임은 frames.ts
  // 타입으로 묶여 있어, 위 골든 비교가 frames.ts를 지키는 것으로 함께 지켜진다.
  const frames = [
    ...aiTurnFrames(
      't',
      'trace',
      'hi',
      'normal',
      {
        docId: 'd',
        title: 't',
        snippet: 's',
        score: 1,
      },
      'turn',
    ),
    errorFrame('t', 'MODEL_UNAVAILABLE', 'm', 'trace'),
  ];

  it.each(frames.map((frame) => ({ type: frame.type, frame })))(
    '$type 프레임의 필드 이름이 서버 골든 파일과 같다',
    ({ type, frame }) => {
      expect(Object.keys(frame).sort()).toEqual(golden.get(type));
    },
  );
});
