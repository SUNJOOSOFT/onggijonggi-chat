/********************************************************
 파일명 : collab.ts (lib/api)
 설 명 : 협업 채널 목록 조회(이슈 #19). 401 재시도·429 백오프는 authFetch(http.ts)가 맡는다.

 1:1 채팅의 이력 조회(server-history.ts)와 달리 클라이언트에서 부른다. 목업 모드에서 이 경로를
 서버가 부르면 Next가 자기 자신에게 HTTP 요청을 보내는 꼴이기 때문이다 — config.ts의 폴백
 덕에 동작은 하지만 같은 프로세스를 한 바퀴 돌 이유가 없다. 처음에는 서버에서 불렀다가
 "Failed to parse URL from /api/collab/threads"로 죽는 것을 보고 옮겼다.
 *********************************************************/

import { COLLAB_THREADS_PATH, bffUrl } from './config';
import { authFetch } from './http';

/**
 * GET /api/collab/threads 응답 항목. 어떤 방을 내려줄지 고르는 것은 서버 몫이라 프론트는 걸러진
 * 목록을 표시만 한다. 참여자 이름을 함께 주는 것은 제목만으로 방을 가려내기 어렵기 때문이고,
 * 스키마 확정은 #14·#16이 가져간다.
 */
export interface CollabThreadSummary {
  id: string;
  title: string;
  participants: string[];
}

/** POST /api/collab/threads 성공 응답. 생성 직후 방으로 이동하는 데 id만 필요하다. */
export interface CreateCollabThreadResponse {
  id: string;
}

/** 참여 중인 협업 채널 목록. 실패하면 예외를 던져 호출부가 안내하게 한다. */
export async function fetchCollabThreads(): Promise<CollabThreadSummary[]> {
  const res = await authFetch(bffUrl(COLLAB_THREADS_PATH));
  if (!res.ok) {
    throw new Error(await res.text());
  }
  return res.json() as Promise<CollabThreadSummary[]>;
}

/**
 * 협업방을 만들고 새 방 UUID를 돌려준다. 제목 검증은 서버가 최종 책임진다.
 *
 * idempotencyKey를 넘기면(이슈 #149) 응답 유실 뒤 같은 키로 재시도해도 방이 두 번 만들어지지
 * 않는다 — 호출부가 재시도 사이에 같은 값을 재사용해야 의미가 있고, 이 함수는 값을 생성하지
 * 않는다(무엇이 "같은 시도"인지는 화면의 재시도 흐름이 정할 몫이다).
 */
export async function createCollabThread(
  title: string,
  idempotencyKey?: string,
): Promise<CreateCollabThreadResponse> {
  const res = await authFetch(bffUrl(COLLAB_THREADS_PATH), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
    },
    body: JSON.stringify({ title }),
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
  return res.json() as Promise<CreateCollabThreadResponse>;
}

/**
 * GET .../participants 응답 항목(이슈 #23). displayName은 서버가 Keycloak Admin API로 미리
 * 해석해 내려준다 — 클라이언트는 subject로 Keycloak을 다시 조회하지 않는다.
 */
export interface ThreadParticipant {
  subject: string;
  role: 'OWNER' | 'MEMBER';
  self: boolean;
  displayName: string;
}

/** 스레드의 ACTIVE 참여자 목록. 실패하면 예외를 던져 호출부(Sheet)가 안내하게 한다. */
export async function fetchThreadParticipants(
  threadId: string,
): Promise<ThreadParticipant[]> {
  const res = await authFetch(
    bffUrl(`${COLLAB_THREADS_PATH}/${threadId}/participants`),
  );
  if (!res.ok) {
    throw new Error(await res.text());
  }
  return res.json() as Promise<ThreadParticipant[]>;
}

/** 참가자 초대. OWNER만 호출할 수 있고, 이미 참가 중인 사람을 다시 초대해도 성공이다(멱등). */
export async function inviteParticipant(
  threadId: string,
  subject: string,
): Promise<void> {
  const res = await authFetch(
    bffUrl(`${COLLAB_THREADS_PATH}/${threadId}/participants`),
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ subject }),
    },
  );
  // 성공은 204라 읽을 바디가 없다 — res.ok만으로 판단한다.
  if (!res.ok) {
    throw new Error(await res.text());
  }
}

/** 참가자 제거. subject가 호출자 자신이면 자진탈퇴, 아니면 OWNER의 타인 제거로 서버가 가른다. */
export async function removeParticipant(
  threadId: string,
  subject: string,
): Promise<void> {
  const res = await authFetch(
    bffUrl(
      `${COLLAB_THREADS_PATH}/${threadId}/participants/${encodeURIComponent(subject)}`,
    ),
    { method: 'DELETE' },
  );
  if (!res.ok) {
    throw new Error(await res.text());
  }
}

/** 소유권 위임. OWNER만 호출할 수 있고, 대상은 그 스레드의 ACTIVE MEMBER여야 한다. */
export async function transferOwnership(
  threadId: string,
  subject: string,
): Promise<void> {
  const res = await authFetch(
    bffUrl(`${COLLAB_THREADS_PATH}/${threadId}/owner`),
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ subject }),
    },
  );
  if (!res.ok) {
    throw new Error(await res.text());
  }
}
