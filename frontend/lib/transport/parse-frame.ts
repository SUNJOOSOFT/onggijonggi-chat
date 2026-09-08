/********************************************************
 파일명 : parse-frame.ts (lib/transport)
 설 명 : 서버가 보낸 원시 JSON(unknown)을 WsFrame으로 검증·변환한다. 미지 type이나 스키마가
 안 맞는 프레임은 예외를 던지지 않고 null로 조용히 흘려보낸다 —
 프레임 하나가 깨졌다고 어댑터 전체(스트림 소비)가 죽으면 안 되기 때문이다. 호출부(frame-router.ts
 또는 그 소비자)가 null을 로그로 남길지는 그쪽 책임으로 남긴다.
 *********************************************************/

import { z } from 'zod';
import type { WsFrame } from './frames';

const citationSchema = z.object({
  docId: z.string(),
  title: z.string(),
  snippet: z.string(),
  score: z.number(),
});

/** 이슈 #10 코멘트에서 확정된 통합 답변 패킷 — chat.token/chat.done/(제안했던)chat.citation을
 * 흡수한다. citations는 이 패킷에 아무것도 안 실려 있으면 빈 배열로 온다(옵셔널이 아니다 —
 * 서버가 항상 필드를 채워 보낸다는 게 확정 스펙의 전제). */
const chatAnswerFrameSchema = z.object({
  type: z.literal('chat.answer'),
  sessionId: z.string(),
  delta: z.string(),
  citations: z.array(citationSchema),
  restrictedResultsOmitted: z.boolean(),
  status: z.union([z.literal('streaming'), z.literal('done')]),
});

const chatMessageFrameSchema = z.object({
  type: z.literal('chat.message'),
  sessionId: z.string(),
  from: z.string(),
  content: z.string(),
});

const presenceJoinFrameSchema = z.object({
  type: z.literal('presence.join'),
  sessionId: z.string(),
  userId: z.string(),
});

const presenceLeaveFrameSchema = z.object({
  type: z.literal('presence.leave'),
  sessionId: z.string(),
  userId: z.string(),
});

const presenceSnapshotFrameSchema = z.object({
  type: z.literal('presence.snapshot'),
  sessionId: z.string(),
  participants: z.array(z.string()),
});

/** 연결 수립 자체가 실패하는 경우처럼 특정 세션에 속하지 않는 오류는 sessionId가 null일 수
 * 있다(ErrorFrame.java 주석과 동일 계약). */
const wsErrorFrameSchema = z.object({
  type: z.literal('error'),
  sessionId: z.string().nullable(),
  code: z.string(),
  message: z.string(),
  traceId: z.string(),
});

/** type 필드로 판별하는 유니온. 알려진 6개 타입 중 하나와 정확히 일치하지 않으면(미지 타입
 * 포함) 파싱이 실패한다 — parseFrame이 그 실패를 null로 흡수한다. */
const wsFrameSchema = z.discriminatedUnion('type', [
  chatAnswerFrameSchema,
  chatMessageFrameSchema,
  presenceJoinFrameSchema,
  presenceLeaveFrameSchema,
  presenceSnapshotFrameSchema,
  wsErrorFrameSchema,
]);

/** 이미 JSON.parse된 값을 검증한다. 객체가 아니거나, type이 없거나, 알려지지 않은 type이거나,
 * 필드 스키마가 안 맞으면 null. */
export function parseFrame(raw: unknown): WsFrame | null {
  const result = wsFrameSchema.safeParse(raw);
  return result.success ? result.data : null;
}

/** WS 메시지(event.data)나 NDJSON 한 줄처럼 아직 파싱 전인 원문 문자열을 받는 편의 함수.
 * JSON 자체가 깨져 있어도(전송 중 잘림 등) 예외 대신 null을 반환한다. */
export function parseFrameFromText(text: string): WsFrame | null {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return null;
  }
  return parseFrame(raw);
}
