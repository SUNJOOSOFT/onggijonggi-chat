'use client';

import { Button } from '@/components/ui/button';
import {
  type ManagedThread,
  type ManagedWorkspace,
  type Organization,
  type RankGrant,
  type Role,
  type TeamGrant,
  listManagedThreads,
  listManagedWorkspaces,
  listOrganizations,
  listRankGrants,
  listTeamGrants,
  rbacRequest,
  workspacePath,
} from '@/lib/api/rbac-management';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ChangeButton, Section, fieldClass } from './management-controls';

const ROLES: Role[] = ['VIEWER', 'CONTRIBUTOR', 'ADMIN'];
export const RANKS = ['TL', 'B', 'C', 'K', 'D', 'S'];

export function WorkspaceManagement() {
  const [nodes, setNodes] = useState<ManagedWorkspace[]>([]);
  const [selected, setSelected] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshSignal, setRefreshSignal] = useState(0);
  const sequence = useRef(0);
  const reload = useCallback(async () => {
    const request = ++sequence.current;
    try {
      const result = await listManagedWorkspaces();
      if (request !== sequence.current) return;
      setNodes(result);
      setError('');
      setSelected((value) =>
        result.some((node) => node.id === value)
          ? value
          : (result[0]?.id ?? ''),
      );
      return result;
    } catch (reason) {
      if (request === sequence.current) {
        setError(String(reason instanceof Error ? reason.message : reason));
        setNodes([]);
      }
      throw reason;
    } finally {
      if (request === sequence.current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    void reload().catch(() => undefined);
    return () => {
      ++sequence.current;
    };
  }, [reload]);
  const node = nodes.find((value) => value.id === selected);
  return (
    <>
      <div className="flex flex-wrap items-center gap-3">
        <label>
          Workspace{' '}
          <select
            aria-label="관리 Workspace"
            className={fieldClass}
            value={selected}
            onChange={(event) => setSelected(event.target.value)}
          >
            <option value="">선택하세요</option>
            {nodes.map((value) => (
              <option key={value.id} value={value.id}>
                {value.name} ({value.status})
              </option>
            ))}
          </select>
        </label>
        <Button
          onClick={() =>
            void reload()
              .then(() => setRefreshSignal((value) => value + 1))
              .catch(() => undefined)
          }
        >
          목록 다시 조회
        </Button>
      </div>
      {loading && <p>목록 조회 중…</p>}
      {error && <p role="alert">{error}</p>}
      {!loading && !error && nodes.length === 0 && (
        <p>현재 직접 관리 권한이 있는 Workspace가 없습니다.</p>
      )}
      {node && (
        <WorkspaceDetails
          key={node.id}
          node={node}
          nodes={nodes}
          refreshNodes={reload}
          refreshSignal={refreshSignal}
        />
      )}
    </>
  );
}

function WorkspaceDetails({
  node,
  nodes,
  refreshNodes,
  refreshSignal,
}: {
  node: ManagedWorkspace;
  nodes: ManagedWorkspace[];
  refreshNodes: () => Promise<ManagedWorkspace[] | undefined>;
  refreshSignal: number;
}) {
  const [name, setName] = useState(node.name);
  const serverName = useRef(node.name);
  useEffect(() => {
    const previous = serverName.current;
    serverName.current = node.name;
    setName((draft) => (draft === previous ? node.name : draft));
  }, [node.name]);
  const [childName, setChildName] = useState('');
  const [kind, setKind] = useState('WORK');
  const [parent, setParent] = useState('');
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [grants, setGrants] = useState<TeamGrant[]>([]);
  const [ranks, setRanks] = useState<RankGrant[]>([]);
  const [threads, setThreads] = useState<ManagedThread[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  const [org, setOrg] = useState('');
  const [rankOrg, setRankOrg] = useState('');
  const [role, setRole] = useState<Role>('VIEWER');
  const [rankRole, setRankRole] = useState<Role>('VIEWER');
  const [rank, setRank] = useState('S');
  const sequence = useRef(0);
  const reload = useCallback(async () => {
    const request = ++sequence.current;
    setLoaded(false);
    if (node.status !== 'ACTIVE') {
      setLoaded(true);
      return;
    }
    try {
      const [units, team, rules, rooms] = await Promise.all([
        listOrganizations(node.id),
        listTeamGrants(node.id),
        listRankGrants(node.id),
        listManagedThreads(node.id),
      ]);
      if (request !== sequence.current) return;
      setOrganizations(units);
      setGrants(team);
      setRanks(rules);
      setThreads(rooms);
      setError('');
      setLoaded(true);
    } catch (reason) {
      if (request === sequence.current) {
        setError(reason instanceof Error ? reason.message : '조회 실패');
        setLoaded(false);
      }
      throw reason;
    }
  }, [node.id, node.status]);
  // biome-ignore lint/correctness/useExhaustiveDependencies: 동일 노드의 수동 목록 갱신도 상세 재조회가 필요하다.
  useEffect(() => {
    void reload().catch(() => undefined);
    return () => {
      ++sequence.current;
    };
  }, [reload, refreshSignal]);
  async function refresh() {
    const current = (await refreshNodes())?.find(
      (value) => value.id === node.id,
    );
    if (!current || current.status !== 'ACTIVE') return;
    await reload();
  }
  const active = organizations.filter((value) => value.status === 'ACTIVE');
  const writable = node.status === 'ACTIVE' && loaded && !error;
  return (
    <>
      <p className="text-sm">
        {node.name} · {node.kind} · {node.status}
        {node.declared ? ' · 배포 설정 선언 리소스' : ''}
      </p>
      <p className="text-sm text-muted-foreground">
        표시된 작업도 저장 시 서버가 다시 검사합니다. 상위 Workspace의 관리
        권한은 하위로 상속되지 않습니다.
      </p>
      {error && (
        <p role="alert">
          {error}{' '}
          <Button onClick={() => void reload().catch(() => undefined)}>
            상세 다시 조회
          </Button>
        </p>
      )}
      <Section title="노드 관리">
        {node.actions.includes('RENAME') && (
          <div className="flex flex-wrap gap-2">
            <input
              aria-label="Workspace 이름"
              className={fieldClass}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
            <ChangeButton
              label="이름 저장"
              description={`${node.name}의 이름을 변경합니다.`}
              disabled={!writable || !name.trim()}
              change={() =>
                rbacRequest(`${workspacePath(node.id)}/name`, 'PATCH', { name })
              }
              refresh={refresh}
            />
          </div>
        )}
        {node.actions.includes('CREATE') && (
          <div className="flex flex-wrap gap-2">
            <input
              aria-label="새 Workspace 이름"
              className={fieldClass}
              value={childName}
              onChange={(event) => setChildName(event.target.value)}
            />
            <select
              aria-label="새 노드 종류"
              className={fieldClass}
              value={kind}
              onChange={(event) => setKind(event.target.value)}
            >
              <option>WORK</option>
              <option>ORG</option>
            </select>
            <ChangeButton
              label="하위 Workspace 만들기"
              description={`${node.name} 아래에 노드를 만들고 내 팀에 초기 ADMIN을 부여합니다.`}
              disabled={!writable || !childName.trim()}
              change={() =>
                rbacRequest('/api/rbac/workspaces', 'POST', {
                  parentId: node.id,
                  kind,
                  name: childName,
                })
              }
              refresh={refresh}
            />
          </div>
        )}
        {node.actions.includes('REPARENT') && (
          <div className="flex flex-wrap gap-2">
            <select
              aria-label="새 부모 Workspace"
              className={fieldClass}
              value={parent}
              onChange={(event) => setParent(event.target.value)}
            >
              <option value="">새 부모 선택</option>
              {nodes
                .filter(
                  (value) =>
                    value.id !== node.id &&
                    value.status === 'ACTIVE' &&
                    value.kind !== 'ROOT' &&
                    value.kind !== 'COMMON',
                )
                .map((value) => (
                  <option key={value.id} value={value.id}>
                    {value.name}
                  </option>
                ))}
            </select>
            <ChangeButton
              label="leaf 이동"
              description="자식·방·문서가 없는 leaf만 이동합니다. 기존 부모·새 부모·leaf의 직접 MANAGE가 모두 필요하며 기존 부여는 유지합니다."
              disabled={!writable || !parent}
              change={() =>
                rbacRequest(`${workspacePath(node.id)}/parent`, 'PATCH', {
                  parentId: parent,
                })
              }
              refresh={refresh}
            />
          </div>
        )}
        {node.actions.includes('DEACTIVATE') && (
          <ChangeButton
            label="하위 트리 비활성화"
            description="모든 활성 하위 노드의 직접 MANAGE가 필요합니다. 남은 협업방은 먼저 이동해야 하며 데이터·부여는 보존됩니다."
            disabled={!writable}
            change={() =>
              rbacRequest(`${workspacePath(node.id)}/deactivate`, 'POST')
            }
            refresh={refreshNodes}
          />
        )}
        {node.actions.includes('REACTIVATE') && (
          <ChangeButton
            label="대상 재활성화"
            description="이 노드만 활성화합니다. 비활성 하위 노드는 자동 복구하지 않습니다."
            change={() =>
              rbacRequest(`${workspacePath(node.id)}/reactivate`, 'POST')
            }
            refresh={refreshNodes}
          />
        )}
      </Section>
      {node.status !== 'ACTIVE' ? (
        <p>
          비활성 노드는 복구 작업만 제공합니다. 부여·직급·방 관리는 재활성화 뒤
          조회합니다.
        </p>
      ) : (
        <>
          {!loaded && !error && <p>상세 조회 중…</p>}
          {loaded && (
            <>
              <Section title="팀 부여">
                <div className="flex flex-wrap gap-2">
                  <OrgSelect
                    label="부여 조직"
                    value={org}
                    onChange={setOrg}
                    organizations={active}
                  />
                  <RoleSelect
                    label="부여 역할"
                    value={role}
                    onChange={setRole}
                  />
                  <ChangeButton
                    label="팀 부여 추가"
                    description="선택한 조직에 이 Workspace 권한을 부여합니다."
                    disabled={!org || !node.actions.includes('GRANTS')}
                    change={() =>
                      rbacRequest(`${workspacePath(node.id)}/grants`, 'POST', {
                        orgUnitId: org,
                        role,
                      })
                    }
                    refresh={refresh}
                  />
                </div>
                {grants.map((grant) => (
                  <GrantRow
                    key={`${grant.id}-${grant.role}`}
                    grant={grant}
                    common={node.kind === 'COMMON'}
                    refresh={refresh}
                  />
                ))}
              </Section>
              <Section title="직급 규칙">
                <p className="text-sm">
                  기준 직급 이상에 적용합니다. 선언 규칙 축소는 설정 선언
                  제거·적용 확인 후 변경하세요. 범위 변경은 삭제 후 재추가하며
                  사이의 권한 공백이 발생합니다.
                </p>
                <div className="flex flex-wrap gap-2">
                  <OrgSelect
                    label="직급 조직"
                    value={rankOrg}
                    onChange={setRankOrg}
                    organizations={active}
                    all
                  />
                  <RankSelect
                    label="기준 직급"
                    value={rank}
                    onChange={setRank}
                  />
                  <RoleSelect
                    label="직급 역할"
                    value={rankRole}
                    onChange={setRankRole}
                  />
                  <ChangeButton
                    label="직급 규칙 추가"
                    description="기준 직급 이상에 역할을 적용합니다."
                    disabled={!node.actions.includes('GRANTS')}
                    change={() =>
                      rbacRequest(
                        `${workspacePath(node.id)}/rank-grants`,
                        'POST',
                        { orgUnitId: rankOrg || null, rank, role: rankRole },
                      )
                    }
                    refresh={refresh}
                  />
                </div>
                {ranks.map((rule) => (
                  <RankRow
                    key={`${rule.id}-${rule.rank}-${rule.role}`}
                    rule={rule}
                    organizations={organizations}
                    refresh={refresh}
                  />
                ))}
              </Section>
              <Section title="협업방 이동">
                <p className="text-sm">
                  참여하지 않은 방도 관리 목록에 표시됩니다. 방 내용·참여자
                  조회나 입장 권한은 주지 않습니다.
                </p>
                {threads.length === 0 && <p>남아 있는 협업방이 없습니다.</p>}
                {threads.map((thread) => (
                  <ThreadRow
                    key={thread.id}
                    thread={thread}
                    node={node}
                    nodes={nodes}
                    refresh={refresh}
                  />
                ))}
              </Section>
            </>
          )}
        </>
      )}
    </>
  );
}

function GrantRow({
  grant,
  common,
  refresh,
}: { grant: TeamGrant; common: boolean; refresh: () => Promise<void> }) {
  const [role, setRole] = useState<Role>(grant.role);
  const protectedRow = grant.declared || (common && grant.role === 'VIEWER');
  return (
    <div className="flex flex-wrap items-center gap-2 border-b py-2">
      <span>
        {grant.orgUnitName} ({grant.orgUnitStatus})
        {protectedRow ? ' · 보호된 부여' : ''}
      </span>
      <RoleSelect
        label={`${grant.orgUnitName} 역할`}
        value={role}
        onChange={setRole}
        disabled={protectedRow}
      />
      <ChangeButton
        label="팀 역할 저장"
        description="현재 부여 역할을 변경합니다. 내 관리 권한이 사라질 수도 있습니다."
        disabled={protectedRow || role === grant.role}
        change={() =>
          rbacRequest(`/api/rbac/grants/${grant.id}/role`, 'PATCH', { role })
        }
        refresh={refresh}
      />
      <ChangeButton
        label="팀 부여 삭제"
        description="이 부여를 회수합니다. 마지막 활성 팀 ADMIN은 삭제할 수 없습니다."
        disabled={protectedRow}
        change={() => rbacRequest(`/api/rbac/grants/${grant.id}`, 'DELETE')}
        refresh={refresh}
      />
    </div>
  );
}
function RankRow({
  rule,
  organizations,
  refresh,
}: {
  rule: RankGrant;
  organizations: Organization[];
  refresh: () => Promise<void>;
}) {
  const [role, setRole] = useState<Role>(rule.role);
  const [rank, setRank] = useState(rule.rank);
  return (
    <div className="flex flex-wrap items-center gap-2 border-b py-2">
      <span>
        {rule.orgUnitId
          ? (organizations.find((value) => value.id === rule.orgUnitId)?.name ??
            rule.orgUnitId)
          : '전체 조직'}
        {rule.declared ? ' · 선언 규칙' : ''}
      </span>
      <RankSelect
        label={`${rule.id} 직급`}
        value={rank}
        onChange={setRank}
        disabled={rule.declared}
      />
      <RoleSelect
        label={`${rule.id} 역할`}
        value={role}
        onChange={setRole}
        disabled={rule.declared}
      />
      <ChangeButton
        label="기준 직급 저장"
        description="적용 직급을 변경합니다. 축소 시 협업방을 다시 구독해야 할 수 있습니다."
        disabled={rule.declared || rank === rule.rank}
        change={() =>
          rbacRequest(`/api/rbac/rank-grants/${rule.id}/rank`, 'PATCH', {
            rank,
          })
        }
        refresh={refresh}
      />
      <ChangeButton
        label="직급 역할 저장"
        description="역할을 변경합니다. 내 유일한 관리 권한을 회수할 수도 있습니다."
        disabled={rule.declared || role === rule.role}
        change={() =>
          rbacRequest(`/api/rbac/rank-grants/${rule.id}/role`, 'PATCH', {
            role,
          })
        }
        refresh={refresh}
      />
      <ChangeButton
        label="직급 규칙 삭제"
        description="이 규칙을 회수합니다. 직급 ADMIN은 마지막 팀 ADMIN 보호를 대체하지 않습니다."
        disabled={rule.declared}
        change={() => rbacRequest(`/api/rbac/rank-grants/${rule.id}`, 'DELETE')}
        refresh={refresh}
      />
    </div>
  );
}
function ThreadRow({
  thread,
  node,
  nodes,
  refresh,
}: {
  thread: ManagedThread;
  node: ManagedWorkspace;
  nodes: ManagedWorkspace[];
  refresh: () => Promise<void>;
}) {
  const [target, setTarget] = useState('');
  return (
    <div className="flex flex-wrap items-center gap-2 border-b py-2">
      <span>
        {thread.title} ({thread.status})
      </span>
      <select
        aria-label={`${thread.title} 이동 대상`}
        value={target}
        onChange={(event) => setTarget(event.target.value)}
        className={fieldClass}
      >
        <option value="">대상 선택</option>
        {nodes
          .filter(
            (value) =>
              value.id !== node.id &&
              value.kind !== 'ROOT' &&
              value.status === 'ACTIVE',
          )
          .map((value) => (
            <option key={value.id} value={value.id}>
              {value.name}
            </option>
          ))}
      </select>
      <ChangeButton
        label="협업방 이동"
        description="참여자는 유지하며 이후 접근은 대상 Workspace 권한으로 판정합니다."
        disabled={!target}
        change={() =>
          rbacRequest(`/api/collab/threads/${thread.id}/workspace`, 'PATCH', {
            workspaceId: target,
          })
        }
        refresh={refresh}
      />
    </div>
  );
}
export function RoleSelect({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string;
  value: Role;
  onChange: (value: Role) => void;
  disabled?: boolean;
}) {
  return (
    <select
      aria-label={label}
      className={fieldClass}
      value={value}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value as Role)}
    >
      {ROLES.map((role) => (
        <option key={role}>{role}</option>
      ))}
    </select>
  );
}
export function RankSelect({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  return (
    <select
      aria-label={label}
      className={fieldClass}
      value={value}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
    >
      {RANKS.map((rank) => (
        <option key={rank}>{rank}</option>
      ))}
    </select>
  );
}
function OrgSelect({
  label,
  value,
  onChange,
  organizations,
  all,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  organizations: Organization[];
  all?: boolean;
}) {
  return (
    <select
      aria-label={label}
      className={fieldClass}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    >
      <option value="">{all ? '전체 조직' : '조직 선택'}</option>
      {organizations.map((org) => (
        <option key={org.id} value={org.id}>
          {org.name}
        </option>
      ))}
    </select>
  );
}
