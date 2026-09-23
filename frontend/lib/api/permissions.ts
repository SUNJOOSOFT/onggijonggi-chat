/********************************************************
 파일명 : permissions.ts (lib/api)
 설 명 : 권한 관리 화면(/admin/permissions) BFF 호출. 팀·직급 배정과 "누가 무엇을 보나" 표를 받아오고, 배정을 바꾸고,
 CSV를 넣는다. 이 API는 bff의 casbin 프로필에서만 있다 — 꺼져 있으면 404이고 화면은 메뉴를 숨긴다.
 판정 결과는 서버의 실제 판정(Casbin)이 만든 것이다. 화면은 흉내 내지 않고 표시만 한다.
 *********************************************************/

import { MEMBERS_IMPORT_PATH, PERMISSIONS_ADMIN_PATH, bffUrl } from './config';
import { authFetch } from './http';

export interface PermissionTeam {
  id: string;
  key: string;
  name: string;
}

export interface PermissionRank {
  code: string;
  label: string;
}

/** depth는 ROOT 아래 몇 단인지(1이 최상위). 표 머리의 들여쓰기에 쓴다. */
export interface PermissionWorkspace {
  id: string;
  key: string;
  name: string;
  depth: number;
}

/** teamId·rank가 null이면 미배정이다. visible은 볼 수 있는 workspace id 목록. */
export interface PermissionPerson {
  subject: string;
  username: string;
  name: string;
  enabled: boolean;
  teamId: string | null;
  rank: string | null;
  visible: string[];
}

export interface PermissionsOverview {
  teams: PermissionTeam[];
  ranks: PermissionRank[];
  workspaces: PermissionWorkspace[];
  people: PermissionPerson[];
}

export interface ImportReport {
  applied: boolean;
  counts: Record<string, number>;
  rows: {
    line: number;
    email: string;
    team: string;
    rank: string;
    outcome: string;
  }[];
  problems: { line: number; message: string }[];
}

async function failWith(res: Response): Promise<never> {
  throw new Error((await res.text()) || `HTTP ${res.status}`);
}

/** 권한 기능(casbin 프로필)이 켜져 있는지. 404면 꺼진 것이고, 그 밖의 실패도 메뉴를 숨기는 쪽으로 본다. */
export async function fetchPermissionsEnabled(): Promise<boolean> {
  try {
    const res = await authFetch(bffUrl(`${PERMISSIONS_ADMIN_PATH}/status`));
    return res.ok;
  } catch {
    return false;
  }
}

export async function fetchPermissionsOverview(): Promise<PermissionsOverview> {
  const res = await authFetch(bffUrl(`${PERMISSIONS_ADMIN_PATH}/overview`));
  if (!res.ok) return failWith(res);
  return res.json() as Promise<PermissionsOverview>;
}

/** teamId가 null이면 배정을 해제한다. */
export async function saveAssignment(
  subject: string,
  teamId: string | null,
  rank: string | null,
): Promise<void> {
  const path = `${PERMISSIONS_ADMIN_PATH}/people/${encodeURIComponent(subject)}/assignment`;
  const res =
    teamId === null
      ? await authFetch(bffUrl(path), { method: 'DELETE' })
      : await authFetch(bffUrl(path), {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ teamId, rank }),
        });
  if (!res.ok) await failWith(res);
}

/** CSV(email,team,rank)를 넣는다. apply가 false면 미리보기만 한다. */
export async function importMembersCsv(
  csv: string,
  apply: boolean,
): Promise<ImportReport> {
  const res = await authFetch(bffUrl(`${MEMBERS_IMPORT_PATH}?apply=${apply}`), {
    method: 'POST',
    headers: { 'Content-Type': 'text/csv; charset=utf-8' },
    body: csv,
  });
  if (!res.ok) return failWith(res);
  return res.json() as Promise<ImportReport>;
}
