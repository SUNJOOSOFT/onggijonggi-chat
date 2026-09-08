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

/** 협업방을 만들고 새 방 UUID를 돌려준다. 제목 검증은 서버가 최종 책임진다. */
export async function createCollabThread(
  title: string,
): Promise<CreateCollabThreadResponse> {
  const res = await authFetch(bffUrl(COLLAB_THREADS_PATH), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  });
  if (!res.ok) {
    throw new Error(await res.text());
  }
  return res.json() as Promise<CreateCollabThreadResponse>;
}
