/********************************************************
 파일명 : chat.ts (lib/api)
 설 명 : 채팅 BFF 호출. 엔드포인트 URL·요청 바디 변환을 한 곳에 캡슐화한다. 인용은 이슈 #163부터
 REST가 아니라 chat.answer WS 프레임으로 오므로, 여기 CitationsResponse 타입만 그 모양을
 공유하는 용도로 남는다.
 *********************************************************/

import { CHAT_SESSIONS_PATH, bffUrl, chatSessionPath } from './config';
import { authFetch } from './http';

export interface ChatSessionSummary {
  id: string;
  title: string;
  createdAt: string;
}

/** 1:1 목록은 SSR이 아닌 클라이언트에서 갱신한다(#162). */
export async function fetchChatSessions(): Promise<ChatSessionSummary[]> {
  const response = await authFetch(bffUrl(CHAT_SESSIONS_PATH));
  if (!response.ok) throw new Error(await response.text());
  return response.json() as Promise<ChatSessionSummary[]>;
}

/** 와이어 메시지 모양. */
export interface WireMessage {
  role: string;
  content: string;
}

/** AI SDK UI 메시지를 와이어 모양 {role, content}으로 트리밍한다(id·parts 등 잉여 필드 제거). */
export function toWireMessages(
  messages: ReadonlyArray<{ role: string; content: string }>,
): WireMessage[] {
  return messages.map(({ role, content }) => ({ role, content }));
}

/** 채팅 요청 바디. */
export function buildChatRequestBody(params: {
  sessionId: string;
  modelId: string;
  messages: ReadonlyArray<{ role: string; content: string }>;
}) {
  return {
    sessionId: params.sessionId,
    modelId: params.modelId,
    messages: toWireMessages(params.messages),
  };
}

/** 인용 응답. */
export interface Citation {
  docId: string;
  title: string;
  snippet: string;
  score: number;
}

export interface CitationsResponse {
  citations: Citation[];
  restrictedResultsOmitted: boolean;
}

/** 서버에 저장된 세션을 삭제한다. 스토어의 deleteSession(zustand 로컬 액션)과 이름이 겹치지
 * 않도록 접미사를 둔다. 실패하면 예외를 던져 호출부가 toast로 안내하게 한다. */
export async function deleteSessionOnServer(sessionId: string): Promise<void> {
  const res = await authFetch(bffUrl(chatSessionPath(sessionId)), {
    method: 'DELETE',
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
}

/** 서버에 저장된 세션 제목을 바꾼다. 스토어의 renameSession(zustand 로컬 액션)과 이름이
 * 겹치지 않도록 접미사를 둔다. 실패하면 예외를 던져 호출부가 toast로 안내하게 한다. */
export async function renameSessionOnServer(
  sessionId: string,
  title: string,
): Promise<void> {
  const res = await authFetch(bffUrl(chatSessionPath(sessionId)), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
}
