/********************************************************
 파일명 : workspaces.ts (mocks)
 설 명 : [MOCK] 목업 워크스페이스. 모든 방은 워크스페이스에 놓이므로(절체 뒤 실 BFF 계약) 목업도 고객사 ROOT와 그 아래
 COMMON 하나를 둔다. 협업방 목록·생성 목업이 이 COMMON에 방을 놓는다.
 *********************************************************/

import type { Workspace } from '@/lib/api/collab';

export const MOCK_ROOT_ID = '00000000-0000-4000-8000-0000000000a0';
export const MOCK_COMMON_ID = '00000000-0000-4000-8000-0000000000a1';
export const MOCK_COMMON_NAME = '공용';

export const MOCK_WORKSPACES: Workspace[] = [
  { id: MOCK_ROOT_ID, parentId: null, name: '기본', kind: 'ROOT', depth: 0 },
  {
    id: MOCK_COMMON_ID,
    parentId: MOCK_ROOT_ID,
    name: MOCK_COMMON_NAME,
    kind: 'COMMON',
    depth: 1,
  },
];
