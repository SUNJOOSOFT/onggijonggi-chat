'use client';

import { SidebarToggle } from '@/components/sidebar-toggle';
import { Button } from '@/components/ui/button';
import { managementAccess } from '@/lib/api/rbac-management';
import Link from 'next/link';
import { type ReactNode, useEffect, useState } from 'react';

/** 관리 영역 진입점. 플랫폼 역할을 Workspace 관리 권한의 대체 조건으로 쓰지 않는다. */
export function ManagementShell({
  title,
  area,
  children,
}: {
  title: string;
  area?: 'workspace' | 'platform' | 'audit';
  children?: ReactNode;
}) {
  const [access, setAccess] = useState<{
    workspace: boolean;
    platform: boolean;
  } | null>(null);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  // biome-ignore lint/correctness/useExhaustiveDependencies: 재시도 버튼이 권한 조회를 명시적으로 다시 실행한다.
  useEffect(() => {
    let alive = true;
    setAccess(null);
    setError('');
    managementAccess()
      .then((value) => {
        if (alive) setAccess(value);
      })
      .catch((reason) => {
        if (alive)
          setError(
            reason instanceof Error ? reason.message : '조회하지 못했습니다.',
          );
      });
    return () => {
      alive = false;
    };
  }, [retry]);
  const allowed =
    access &&
    (area === 'workspace'
      ? access.workspace
      : area === 'platform'
        ? access.platform
        : access.workspace || access.platform);
  return (
    <div className="flex h-dvh flex-col">
      <header className="flex items-center gap-2 border-b px-3 py-2">
        <SidebarToggle />
        <h1 className="text-sm font-semibold">{title}</h1>
      </header>
      <div className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-5 overflow-y-auto p-4 md:p-6">
        <nav
          aria-label="권한 관리"
          className="flex flex-wrap gap-4 border-b pb-3 text-sm"
        >
          <Link href="/admin/permissions">권한 관리 홈</Link>
          {access?.workspace && (
            <Link href="/admin/permissions/workspaces">Workspace 관리</Link>
          )}
          {(access?.workspace || access?.platform) && (
            <Link href="/admin/permissions/audits">감사 조회</Link>
          )}
          {access?.platform && (
            <Link href="/admin/permissions/platform">플랫폼 관리</Link>
          )}
        </nav>
        {error ? (
          <div role="alert">
            {error}{' '}
            <Button onClick={() => setRetry((value) => value + 1)}>
              다시 조회
            </Button>
          </div>
        ) : access === null ? (
          <p>권한 확인 중…</p>
        ) : !allowed ? (
          <p role="alert">
            이 화면을 사용할 권한이 없거나 권한 관리 기능이 꺼져 있습니다.
          </p>
        ) : area ? (
          children
        ) : (
          <p>
            관리할 Workspace 또는 플랫폼 기능을 위 메뉴에서 선택하세요.
            Workspace 변경에는 해당 노드의 직접 관리 권한이 필요합니다.
          </p>
        )}
      </div>
    </div>
  );
}
