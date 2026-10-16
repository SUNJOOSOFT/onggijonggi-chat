/********************************************************
 파일명 : thread-history.ts (lib/api)
 설 명 : DIRECT·COLLAB 공용 메시지 이력을 클라이언트에서 조회한다. #162는 SSR
        prefetch가 아닌 이 경로와 afterSeq catch-up을 정본으로 사용한다.
 *********************************************************/

import type { MessageAttachment } from '@/lib/transport/frames';
import { bffUrl, threadMessagesPath } from './config';
import { authFetch } from './http';

/**
 * 메시지 이력 응답 항목(백엔드 MsgItem과 짝, 이슈 #190). DIRECT·COLLAB이 같은 엔드포인트 계약을
 * 쓰므로 타입도 하나다.
 *
 * seq는 방 안의 순서이자 따라잡기 커서다. 다만 서버가 seq를 블록으로 예약해 쓰지 않은 번호가
 * 구멍으로 남으므로 연속성은 가정하지 않는다 — 빠진 번호를 기다리면 안 된다.
 */
export interface ThreadMessageItem {
  id: string;
  seq: number;
  athKind: 'HUMAN' | 'AGENT' | 'SYSTEM';
  status: 'PENDING' | 'COMPLETE' | 'DENIED' | 'FAILED' | 'CANCELLED';
  content: string;
  /** HUMAN 작성자의 Keycloak subject. WS 프레임의 from과 같은 값이다. AGENT·SYSTEM은 null. */
  authorSubject: string | null;
  /** HUMAN 메시지 작성자의 표시 이름. AGENT·SYSTEM은 작성자가 없어 null이다. */
  authorDisplayName: string | null;
  createdAt: string;
  completedAt: string | null;
  /** HUMAN 메시지에 실린 첨부. 없으면 빈 배열이다. WS chat.message의 같은 필드와 짝이다. */
  attachments: MessageAttachment[];
  /** 협업방에서 `@SOL`·`@FIN`·`@LAW`로 멘션해 역할별 페르소나로 답했을 때만 채워진다(이슈
   * #357). `@AI`로 답했거나 HUMAN·SYSTEM이면 null이다 — WS chat.answer의 같은 필드와 짝이다.
   * 이 필드가 없던 과거 응답의 역직렬화 호환을 위해 optional이다. */
  agent?: 'sol' | 'fin' | 'law' | null;
  /** SYSTEM 메시지가 협업방 입퇴장 줄이면 그 내용. 그 밖의 메시지는 null이다 — WS
   * participant.changed의 msgId·seq와 짝이다. 이 필드가 없던 과거 응답과 맞물리도록 optional이다. */
  membership?: {
    event: 'JOINED' | 'LEFT';
    subject: string;
    displayName: string;
  } | null;
  /** SYSTEM 메시지가 협업방 승인 요청·처리 줄이면 그 내용(이슈 #407). 그 밖의 메시지는 null이다 — WS
   * approval.changed의 msgId·seq와 짝이다. 이 필드가 없던 과거 응답과 맞물리도록 optional이다. event는
   * 서버가 사건을 더해도 깨지지 않게 string으로 받는다. */
  approval?: {
    event: string;
    approvalId: string;
    requesterSubject: string;
    requesterDisplayName: string;
    targetSubject: string;
    targetDisplayName: string;
  } | null;
}

export async function fetchThreadMessages(
  threadId: string,
  afterSeq?: number,
): Promise<{ status: number; messages: ThreadMessageItem[] }> {
  const response = await authFetch(
    bffUrl(threadMessagesPath(threadId, afterSeq)),
  );
  if (!response.ok) return { status: response.status, messages: [] };
  return {
    status: response.status,
    messages: (await response.json()) as ThreadMessageItem[],
  };
}
