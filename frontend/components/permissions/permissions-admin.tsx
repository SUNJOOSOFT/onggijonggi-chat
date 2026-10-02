'use client';

/********************************************************
 파일명 : permissions-admin.tsx (components/permissions)
 설 명 : 권한 관리 화면(/admin/permissions). 사람마다 팀·직급(Casbin에 적재된 속성)과 "누가 무엇을 보나" 표를
 보여 준다. 팀·직급은 여기서 바꾸지 않는다 — 속성 파일(members.csv)을 고치고 casbin을 재시작하면 반영된다.
 판정은 흉내 내지 않는다 — 표의 O/-는 bff가 Casbin에 물어본 결과 그대로다.
 bff의 casbin 프로필에서만 API가 있다. 꺼져 있으면 404, PLATFORM_ADMIN이 아니면 403을 반환한다.
 *********************************************************/

import { useCallback, useEffect, useRef, useState } from 'react';

import { SidebarToggle } from '@/components/sidebar-toggle';
import { Button } from '@/components/ui/button';
import {
  type PermissionsOverview,
  PermissionsApiError,
  fetchPermissionsOverview,
} from '@/lib/api/permissions';

export function PermissionsAdmin({ embedded = false }: { embedded?: boolean }) {
  const [overview, setOverview] = useState<PermissionsOverview | null>(null);
  // code: Keycloak 관리 연결 설정 문제(#326)는 권한 기능·역할과 무관하니 그 안내를 붙이지 않는다.
  const [failed, setFailed] = useState<{
    message: string;
    code?: string;
  } | null>(null);
  const sequence = useRef(0);

  const reload = useCallback(async () => {
    const request = ++sequence.current;
    try {
      const result = await fetchPermissionsOverview();
      if (request !== sequence.current) return;
      setOverview(result);
      setFailed(null);
    } catch (error) {
      if (request === sequence.current) {
        setOverview(null);
        setFailed({
          message: error instanceof Error ? error.message : '',
          code: error instanceof PermissionsApiError ? error.code : undefined,
        });
        throw error;
      }
    }
  }, []);

  useEffect(() => {
    void reload().catch(() => undefined);
    return () => {
      ++sequence.current;
    };
  }, [reload]);

  return (
    <div className={embedded ? 'flex flex-col' : 'flex h-dvh flex-col'}>
      {!embedded && (
        <header className="flex sticky top-0 items-center gap-2 border-b bg-background px-2 py-1.5">
          <SidebarToggle />
          <h1 className="text-sm font-semibold">권한 관리</h1>
        </header>
      )}

      <div
        className={
          embedded
            ? 'flex flex-col gap-8'
            : 'mx-auto flex w-full max-w-6xl flex-1 flex-col gap-8 overflow-y-auto p-6'
        }
      >
        <p className="rounded-lg bg-muted px-4 py-3 text-sm">
          팀·직급은 members.csv에서 옵니다. 바꾸려면 파일을 고친 뒤 casbin을
          재시작하세요. 아래 표는 실제 권한 판정 결과입니다.
        </p>

        {failed !== null && (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm" role="alert">
            불러오지 못했습니다.{' '}
            {failed.code !== 'KEYCLOAK_ADMIN_UNAVAILABLE' &&
              '권한 기능이 켜져 있고 PLATFORM_ADMIN 권한이 있는지 확인해 주세요. '}
            {failed.message}
          </p>
        )}
        {failed === null && overview === null && (
          <p className="text-sm text-muted-foreground">불러오는 중…</p>
        )}

        {failed !== null && (
          <Button onClick={() => void reload().catch(() => undefined)}>
            목록 다시 조회
          </Button>
        )}

        {overview && (
          <>
            <AssignmentTable overview={overview} />
            <VisibilityTable overview={overview} />
          </>
        )}
      </div>
    </div>
  );
}

function AssignmentTable({ overview }: { overview: PermissionsOverview }) {
  const teamNames = new Map(overview.teams.map((team) => [team.id, team.name]));
  const rankLabels = new Map(
    overview.ranks.map((rank) => [rank.code, rank.label]),
  );
  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-base font-semibold">배정</h2>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="py-2 font-medium">사람</th>
            <th className="py-2 font-medium">팀</th>
            <th className="py-2 font-medium">직급</th>
          </tr>
        </thead>
        <tbody>
          {overview.people.map((person) => {
            return (
              <tr key={person.subject} className="border-b">
                <td className="py-2">
                  <span className="font-medium">{person.name}</span>{' '}
                  <span className="text-muted-foreground">
                    {person.username}
                  </span>
                  {!person.enabled && (
                    <span className="ml-2 text-xs text-muted-foreground">
                      (비활성)
                    </span>
                  )}
                </td>
                <td className="py-2">
                  {person.teamId === null
                    ? '미배정'
                    : (teamNames.get(person.teamId) ?? '알 수 없는 팀')}
                </td>
                <td className="py-2">
                  {person.rank === null
                    ? '-'
                    : (rankLabels.get(person.rank) ?? person.rank)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}

function VisibilityTable({ overview }: { overview: PermissionsOverview }) {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-base font-semibold">누가 무엇을 보나</h2>
      <p className="text-xs text-muted-foreground">
        1:1 채팅 공간(common)은 소속·정책과 방 소유권으로 따로 판정하므로 표에서
        제외했습니다.
      </p>
      <div className="overflow-x-auto">
        <table className="text-sm">
          <thead>
            <tr className="border-b text-muted-foreground">
              <th className="py-2 pr-4 text-left font-medium">사람</th>
              {overview.workspaces.map((workspace) => (
                <th
                  key={workspace.id}
                  className="px-2 py-2 text-center font-medium whitespace-nowrap"
                >
                  {workspace.depth > 1 ? `└ ${workspace.name}` : workspace.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {overview.people.map((person) => (
              <tr key={person.subject} className="border-b">
                <td className="py-2 pr-4 whitespace-nowrap">
                  {person.name}{' '}
                  <span className="text-muted-foreground">
                    {person.username}
                  </span>
                </td>
                {overview.workspaces.map((workspace) => {
                  const visible = person.visible.includes(workspace.id);
                  return (
                    <td
                      key={workspace.id}
                      className={`px-2 py-2 text-center ${visible ? 'font-semibold' : 'text-muted-foreground'}`}
                      aria-label={`${person.name} ${workspace.name} ${visible ? '볼 수 있음' : '볼 수 없음'}`}
                    >
                      {visible ? 'O' : '-'}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
