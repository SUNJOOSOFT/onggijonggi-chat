/********************************************************
 파일명 : use-collab-room.ts (lib/collab)
 설 명 : 협업방이 쓰는 방 훅(이슈 #19). 실제 동작은 1:1과 공유하는 useRoom(lib/chat)에 있고,
 여기서는 협업방 쪽 선택만 채운다 — 이력을 협업 엔드포인트에서 읽는 것이 전부다.

 구독은 기본값(startPromoted)을 그대로 쓴다. 협업방은 만들어진 뒤에만 들어갈 수 있어 서버가
 언제나 먼저 아는 방이기 때문이다 — 1:1의 bootstrap(아직 없는 방에 첫 발화로 들어가는 길)이
 여기에는 없다.
 *********************************************************/

import { fetchCollabMessages } from '@/lib/api/collab';
import { type Room, type UseRoomOptions, useRoom } from '@/lib/chat/use-room';

export type { RoomConnection } from '@/lib/chat/use-room';
/** 협업방 화면이 쓰던 이름. 내용은 공용 Room과 같다. */
export type CollabRoom = Room;

/** 매 렌더 새 객체를 넘기지 않도록 모듈 상수로 둔다 — 훅 안에서 ref로 받긴 하지만, 옵션이
 * 고정이라는 사실을 호출부에서도 드러내는 편이 읽기 쉽다. */
const COLLAB_OPTIONS: UseRoomOptions = { fetchHistory: fetchCollabMessages };

export function useCollabRoom(threadId: string): CollabRoom {
  return useRoom(threadId, COLLAB_OPTIONS);
}
