'use client';

import { Button } from '@/components/ui/button';
import {
  type AuditItem,
  type ManagedWorkspace,
  type Tenant,
  listManagedWorkspaces,
  listTenants,
  loadAudits,
  managementAccess,
} from '@/lib/api/rbac-management';
import { useEffect, useRef, useState } from 'react';
import { fieldClass } from './management-controls';

const FILTERS = [
  ['from', '시작 시각 (ISO-8601)'],
  ['to', '종료 시각 (ISO-8601)'],
  ['eventKind', '이벤트 종류'],
  ['targetKind', '대상 종류'],
  ['actorKind', '행위자 종류'],
  ['actorUserId', '행위자 UUID'],
  ['requestId', '요청 ID'],
] as const;

export function AuditManagement() {
  const [nodes, setNodes] = useState<ManagedWorkspace[]>([]);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [scope, setScope] = useState<'workspace' | 'tenant'>('workspace');
  const [id, setId] = useState('');
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [items, setItems] = useState<AuditItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const [revision, setRevision] = useState(0);
  const [contextRevision, setContextRevision] = useState(0);
  const sequence = useRef(0);
  const requestBusy = useRef(false);
  // biome-ignore lint/correctness/useExhaustiveDependencies: 최초 권한·목록 조회 실패를 사용자가 다시 시도한다.
  useEffect(() => {
    let alive = true;
    managementAccess()
      .then(async (access) => {
        const [workspaceList, tenantList] = await Promise.all([
          access.workspace ? listManagedWorkspaces() : Promise.resolve([]),
          access.platform ? listTenants() : Promise.resolve([]),
        ]);
        if (!alive) return;
        const active = workspaceList.filter((node) => node.status === 'ACTIVE');
        setNodes(active);
        setTenants(tenantList);
        setScope(active.length ? 'workspace' : 'tenant');
        setId(active[0]?.id ?? tenantList[0]?.id ?? '');
        setReady(true);
      })
      .catch((reason) => {
        if (alive)
          setError(reason instanceof Error ? reason.message : '조회 실패');
      });
    return () => {
      alive = false;
    };
  }, [contextRevision]);
  // biome-ignore lint/correctness/useExhaustiveDependencies: 필터가 같아도 명시 재조회는 첫 페이지를 갱신한다.
  useEffect(() => {
    const request = ++sequence.current;
    requestBusy.current = false;
    setItems([]);
    setCursor(null);
    setError('');
    if (!ready || !id) {
      setBusy(false);
      return;
    }
    requestBusy.current = true;
    setBusy(true);
    loadAudits(scope, id, filters)
      .then((page) => {
        if (request !== sequence.current) return;
        setItems(page.items);
        setCursor(page.nextCursor);
      })
      .catch((reason) => {
        if (request === sequence.current)
          setError(reason instanceof Error ? reason.message : '조회 실패');
      })
      .finally(() => {
        if (request === sequence.current) {
          requestBusy.current = false;
          setBusy(false);
        }
      });
    return () => {
      ++sequence.current;
    };
  }, [scope, id, filters, ready, revision]);
  async function nextPage() {
    if (!cursor || requestBusy.current) return;
    const request = sequence.current;
    requestBusy.current = true;
    setBusy(true);
    try {
      const page = await loadAudits(scope, id, filters, cursor);
      if (request !== sequence.current) return;
      setItems((current) => {
        const known = new Set(current.map((item) => item.id));
        return [
          ...current,
          ...page.items.filter((item) => !known.has(item.id)),
        ];
      });
      setCursor(page.nextCursor);
      setError('');
    } catch (reason) {
      if (request === sequence.current) {
        setError(reason instanceof Error ? reason.message : '조회 실패');
        setItems([]);
        setCursor(null);
      }
    } finally {
      if (request === sequence.current) {
        requestBusy.current = false;
        setBusy(false);
      }
    }
  }
  return (
    <>
      <div className="flex flex-wrap gap-3">
        <label>
          조회 범위{' '}
          <select
            aria-label="감사 범위"
            className={fieldClass}
            value={scope}
            onChange={(event) => {
              const value = event.target.value as 'workspace' | 'tenant';
              setScope(value);
              setId(
                value === 'workspace'
                  ? (nodes[0]?.id ?? '')
                  : (tenants[0]?.id ?? ''),
              );
            }}
          >
            <option value="workspace" disabled={!nodes.length}>
              Workspace 하나
            </option>
            <option value="tenant" disabled={!tenants.length}>
              Tenant 전체 (플랫폼)
            </option>
          </select>
        </label>
        <select
          aria-label="감사 대상"
          className={fieldClass}
          value={id}
          onChange={(event) => setId(event.target.value)}
        >
          <option value="">선택하세요</option>
          {(scope === 'workspace' ? nodes : tenants).map((value) => (
            <option key={value.id} value={value.id}>
              {value.name} ({value.status})
            </option>
          ))}
        </select>
      </div>
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          setFilters({ ...draft });
        }}
      >
        {FILTERS.map(([name, label]) => (
          <label key={name} className="flex flex-col gap-1 text-sm">
            {label}
            <input
              className={fieldClass}
              value={draft[name] ?? ''}
              onChange={(event) =>
                setDraft((current) => ({
                  ...current,
                  [name]: event.target.value,
                }))
              }
            />
          </label>
        ))}
        <Button type="submit">필터 적용</Button>
        <Button type="button" onClick={() => setRevision((value) => value + 1)}>
          처음부터 다시 조회
        </Button>
      </form>
      <p className="text-sm">
        Workspace 조회는 선택한 노드만 포함합니다. Tenant 전체는 비활성 노드와
        Workspace 없는 행도 포함합니다.
      </p>
      {error && (
        <p role="alert">
          {error}
          {!ready && (
            <Button onClick={() => setContextRevision((value) => value + 1)}>
              목록 다시 조회
            </Button>
          )}
        </p>
      )}
      {busy && <p>조회 중…</p>}
      {!busy && ready && !error && !items.length && (
        <p>조회 결과가 없습니다.</p>
      )}
      <div className="flex flex-col gap-2">
        {items.map((item) => (
          <details key={item.id} className="border-b pb-3">
            <summary className="cursor-pointer text-sm">
              {new Date(item.createdAt).toLocaleString()} · {item.actorKind} (
              {item.actorUserId ?? '사용자 없음'}) · {item.eventKind} ·{' '}
              {item.targetKind}
            </summary>
            <pre className="mt-2 overflow-x-auto rounded-md bg-muted p-3 text-xs">
              {JSON.stringify(item, null, 2)}
            </pre>
          </details>
        ))}
      </div>
      {cursor && (
        <Button disabled={busy} onClick={() => void nextPage()}>
          다음 페이지
        </Button>
      )}
    </>
  );
}
