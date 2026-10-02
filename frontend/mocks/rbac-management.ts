import type { PermissionPerson } from '@/lib/api/permissions';
/********************************************************
 파일명 : rbac-management.ts (mocks)
 설 명 : 관리·감사 목업의 공유 상태. 서버 전용 persona로 권한을 나누며 실 BFF 설정 시 Route Handler가 차단한다.
 *********************************************************/
import type {
  AuditItem,
  ManagedWorkspace,
  PlatformOrganization,
  RankGrant,
  Role,
  TeamGrant,
} from '@/lib/api/rbac-management';

export type MockPersona = 'user' | 'workspace' | 'platform' | 'both';
const TENANT = '00000000-0000-4000-8000-000000000100';
const TEAM = '00000000-0000-4000-8000-000000000101';
const NODE = '00000000-0000-4000-8000-000000000102';
const ROOT = '00000000-0000-4000-8000-000000000103';
const COMMON = '00000000-0000-4000-8000-000000000104';
const ACTOR_USER = '00000000-0000-4000-8000-000000000108';
const ROLES: Role[] = ['VIEWER', 'CONTRIBUTOR', 'ADMIN'];
const RANKS = ['TL', 'B', 'C', 'K', 'D', 'S'];
const MAX_DEPTH = 11;
interface MockRoom {
  id: string;
  title: string;
  status: string;
  workspaceId: string;
}
export interface RbacMockState {
  nodes: ManagedWorkspace[];
  organizations: PlatformOrganization[];
  grants: StoredGrant[];
  ranks: StoredRank[];
  rooms: MockRoom[];
  audits: AuditItem[];
  people: PermissionPerson[];
}
export function createRbacMockState(): RbacMockState {
  return {
    nodes: [
      {
        id: ROOT,
        parentId: null,
        name: '기본',
        kind: 'ROOT',
        status: 'ACTIVE',
        declared: true,
        actions: [],
      },
      {
        id: COMMON,
        parentId: ROOT,
        name: '공용',
        kind: 'COMMON',
        status: 'ACTIVE',
        declared: true,
        actions: [],
      },
      {
        id: NODE,
        parentId: ROOT,
        name: '개발팀',
        kind: 'ORG',
        status: 'ACTIVE',
        declared: false,
        actions: [],
      },
    ],
    organizations: [
      {
        id: TEAM,
        key: 'development',
        name: '개발팀',
        status: 'ACTIVE',
        declared: false,
      },
    ],
    grants: [
      {
        id: '00000000-0000-4000-8000-000000000105',
        orgUnitId: TEAM,
        orgUnitName: '개발팀',
        orgUnitStatus: 'ACTIVE',
        role: 'ADMIN',
        declared: false,
        ...{ workspaceId: NODE },
      },
      {
        id: '00000000-0000-4000-8000-000000000106',
        orgUnitId: TEAM,
        orgUnitName: '개발팀',
        orgUnitStatus: 'ACTIVE',
        role: 'VIEWER',
        declared: true,
        ...{ workspaceId: COMMON },
      },
    ],
    ranks: [],
    rooms: [
      {
        id: '00000000-0000-4000-8000-000000000107',
        title: '개발 협업',
        status: 'ACTIVE',
        workspaceId: NODE,
      },
    ],
    audits: [],
    people: [
      {
        subject: 'mock-admin',
        username: 'admin@example.test',
        name: '목업 관리자',
        enabled: true,
        teamId: TEAM,
        rank: 'S',
        visible: [NODE, COMMON],
      },
    ],
  };
}
// 응답 DTO에는 workspaceId가 없지만 목업 저장 상태는 부여와 노드를 연결해야 한다.
type StoredGrant = TeamGrant & { workspaceId: string };
type StoredRank = RankGrant & { workspaceId: string };
const shared = globalThis as typeof globalThis & {
  rbacManagementMock?: RbacMockState;
};
export function sharedRbacMockState() {
  if (!shared.rbacManagementMock)
    shared.rbacManagementMock = createRbacMockState();
  return shared.rbacManagementMock;
}
function error(status: number, code: string) {
  return Response.json({ error: { code, message: code } }, { status });
}

export async function handleRbacMock(
  request: Request,
  state: RbacMockState,
  persona: MockPersona,
): Promise<Response> {
  const url = new URL(request.url);
  const parts = url.pathname.split('/').filter(Boolean);
  const method = request.method;
  const platform = persona === 'both' || persona === 'platform';
  const workspace = persona === 'both' || persona === 'workspace';
  let currentNode: ManagedWorkspace | undefined;
  const isPlatform = parts[1] === 'platform';
  const prefix = isPlatform ? 3 : 2;
  const resource = parts.slice(prefix);
  const nodeId = resource[0] === 'workspaces' ? resource[1] : undefined;
  const grantAt = (id: string) =>
    state.grants.find((value) => value.id === id) as StoredGrant | undefined;
  const rankAt = (id: string) =>
    state.ranks.find((value) => value.id === id) as StoredRank | undefined;
  const actor = state.people.find((person) => person.subject === 'mock-admin');
  // 실 서버와 같은 ACTIVE 형제 이름 유일성과 ROOT 포함 깊이 한계를 적용한다.
  const duplicateSibling = (
    parentId: string | null,
    name: string,
    except?: string,
  ) =>
    state.nodes.some(
      (node) =>
        node.parentId === parentId &&
        node.id !== except &&
        node.status === 'ACTIVE' &&
        node.name.toLowerCase() === name.toLowerCase(),
    );
  const depth = (node: ManagedWorkspace) => {
    let value: ManagedWorkspace | undefined = node;
    const visited = new Set<string>();
    while (value) {
      if (visited.has(value.id)) return MAX_DEPTH;
      visited.add(value.id);
      value = state.nodes.find((candidate) => candidate.id === value?.parentId);
    }
    return visited.size;
  };
  const isManager = (id: string) =>
    workspace &&
    actor?.enabled &&
    actor.teamId !== null &&
    state.organizations.find((unit) => unit.id === actor.teamId)?.status ===
      'ACTIVE' &&
    (state.grants.some(
      (value) =>
        (value as StoredGrant).workspaceId === id &&
        value.orgUnitId === actor.teamId &&
        value.role === 'ADMIN',
    ) ||
      state.ranks.some(
        (value) =>
          (value as StoredRank).workspaceId === id &&
          (value.orgUnitId === null || value.orgUnitId === actor.teamId) &&
          value.role === 'ADMIN' &&
          actor.rank !== null &&
          RANKS.indexOf(actor.rank) >= 0 &&
          RANKS.indexOf(actor.rank) <= RANKS.indexOf(value.rank),
      ));
  const visible = () =>
    state.nodes
      .filter((node) => isManager(node.id))
      .map((node) => ({
        ...node,
        parentId:
          node.parentId && isManager(node.parentId) ? node.parentId : null,
        actions:
          node.status === 'INACTIVE'
            ? !node.declared &&
              state.nodes.find((value) => value.id === node.parentId)
                ?.status === 'ACTIVE'
              ? ['REACTIVATE']
              : []
            : [
                'AUDIT',
                ...(node.kind === 'ROOT' ? [] : ['GRANTS']),
                ...(node.kind === 'COMMON' || depth(node) >= MAX_DEPTH
                  ? []
                  : ['CREATE']),
                ...(!node.declared &&
                node.kind !== 'ROOT' &&
                node.kind !== 'COMMON'
                  ? ['RENAME', 'REPARENT', 'DEACTIVATE']
                  : []),
              ],
      }));
  if (isPlatform && !platform) return error(403, 'FORBIDDEN');
  if (url.pathname === '/api/rbac/admin/context')
    return Response.json({ workspaceManagement: visible().length > 0 });
  if (isPlatform && resource.join('/') === 'admin/status')
    return Response.json({ enabled: true });
  if (
    isPlatform &&
    method === 'GET' &&
    resource.join('/') === 'admin/overview'
  ) {
    const displayNodes = state.nodes.filter(
      (node) =>
        node.status === 'ACTIVE' &&
        node.kind !== 'ROOT' &&
        node.kind !== 'COMMON',
    );
    const canView = (person: PermissionPerson, id: string) =>
      person.enabled &&
      person.teamId !== null &&
      state.organizations.some(
        (unit) => unit.id === person.teamId && unit.status === 'ACTIVE',
      ) &&
      (state.grants.some(
        (grant) =>
          grant.workspaceId === id &&
          grant.orgUnitId === person.teamId &&
          ROLES.includes(grant.role),
      ) ||
        state.ranks.some(
          (grant) =>
            grant.workspaceId === id &&
            (grant.orgUnitId === null || grant.orgUnitId === person.teamId) &&
            ROLES.includes(grant.role) &&
            person.rank !== null &&
            RANKS.indexOf(person.rank) >= 0 &&
            RANKS.indexOf(person.rank) <= RANKS.indexOf(grant.rank),
        ));
    return Response.json({
      teams: state.organizations
        .filter((unit) => unit.status === 'ACTIVE')
        .map(({ id, key, name }) => ({ id, key, name })),
      ranks: RANKS.map((code) => ({ code, label: code })),
      workspaces: displayNodes.map((node) => ({
        id: node.id,
        key: node.id,
        name: node.name,
        depth: 1,
      })),
      people: state.people.map((person) => ({
        ...person,
        visible: displayNodes
          .filter((node) => canView(person, node.id))
          .map((node) => node.id),
      })),
    });
  }
  if (method === 'GET' && url.pathname === '/api/rbac/workspaces')
    return Response.json(visible());
  if (nodeId) {
    currentNode = state.nodes.find((value) => value.id === nodeId);
    if (!currentNode) return error(404, 'NOT_FOUND');
    if (!isManager(nodeId)) return error(403, 'FORBIDDEN');
    if (method === 'GET' && currentNode.status !== 'ACTIVE')
      return error(403, 'FORBIDDEN');
  }
  if (url.pathname.includes('authorization-audits')) {
    const id = parts[isPlatform ? 4 : 2];
    if (!isPlatform) {
      currentNode = state.nodes.find((value) => value.id === id);
      if (!currentNode) return error(404, 'NOT_FOUND');
      if (!isManager(id) || currentNode.status !== 'ACTIVE')
        return error(403, 'FORBIDDEN');
    } else if (id !== TENANT) return error(404, 'NOT_FOUND');
    const from = url.searchParams.get('from');
    const to = url.searchParams.get('to');
    if (
      (from && Number.isNaN(Date.parse(from))) ||
      (to && Number.isNaN(Date.parse(to))) ||
      (from && to && Date.parse(from) >= Date.parse(to))
    )
      return error(400, 'MALFORMED_REQUEST');
    const limit = Number(url.searchParams.get('limit') ?? 50);
    if (!Number.isInteger(limit) || limit < 1 || limit > 100)
      return error(400, 'MALFORMED_REQUEST');
    const sorted = state.audits
      .filter((item) => isPlatform || item.workspaceNodeId === id)
      .filter(
        (item) =>
          (!from || Date.parse(item.createdAt) >= Date.parse(from)) &&
          (!to || Date.parse(item.createdAt) < Date.parse(to)),
      )
      .filter((item) =>
        [
          'eventKind',
          'targetKind',
          'actorKind',
          'actorUserId',
          'requestId',
        ].every(
          (key) =>
            !url.searchParams.get(key) ||
            String(item[key as keyof AuditItem]) === url.searchParams.get(key),
        ),
      )
      .sort(
        (a, b) =>
          b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id),
      );
    const cursor = url.searchParams.get('cursor');
    let start = 0;
    if (cursor) {
      const position = state.audits.find((item) => item.id === cursor);
      if (!position) return error(400, 'MALFORMED_REQUEST');
      start = sorted.findIndex(
        (item) =>
          item.createdAt < position.createdAt ||
          (item.createdAt === position.createdAt && item.id < position.id),
      );
      if (start === -1) start = sorted.length;
    }
    const items = sorted.slice(start, start + limit);
    return Response.json({
      items,
      nextCursor:
        start + limit < sorted.length ? (items.at(-1)?.id ?? null) : null,
    });
  }
  if (isPlatform && resource[0] === 'tenants') {
    if (resource.length === 1)
      return Response.json([
        { id: TENANT, key: 'default', name: '기본', status: 'ACTIVE' },
      ]);
    if (resource[1] !== 'default') return error(404, 'NOT_FOUND');
    if (method === 'GET' && resource[2] === 'org-units')
      return Response.json(state.organizations);
  }
  if (method === 'GET' && currentNode) {
    if (resource[2] === 'org-units')
      return Response.json(
        state.organizations.map(({ id, name, status }) => ({
          id,
          name,
          status,
        })),
      );
    if (resource[2] === 'grants')
      return Response.json(
        state.grants
          .filter((value) => (value as StoredGrant).workspaceId === nodeId)
          .map(
            ({
              id,
              orgUnitId,
              orgUnitName,
              orgUnitStatus,
              role,
              declared,
            }) => ({
              id,
              orgUnitId,
              orgUnitName,
              orgUnitStatus,
              role,
              declared,
            }),
          ),
      );
    if (resource[2] === 'rank-grants')
      return Response.json(
        state.ranks
          .filter((value) => (value as StoredRank).workspaceId === nodeId)
          .map(({ id, orgUnitId, rank, role, declared }) => ({
            id,
            orgUnitId,
            rank,
            role,
            declared,
          })),
      );
    if (resource[2] === 'threads')
      return Response.json(
        state.rooms
          .filter((value) => value.workspaceId === nodeId)
          .map(({ id, title, status }) => ({ id, title, status })),
      );
  }
  if (method === 'GET') return error(404, 'NOT_FOUND');
  const deactivated: { node: ManagedWorkspace; before: ManagedWorkspace }[] =
    [];
  let body: Record<string, unknown> = {};
  try {
    if (method !== 'DELETE' && !url.pathname.endsWith('activate'))
      body = await request.json();
  } catch {
    return error(400, 'MALFORMED_REQUEST');
  }
  if (body === null || typeof body !== 'object' || Array.isArray(body))
    return error(400, 'MALFORMED_REQUEST');
  if (
    body.name !== undefined &&
    (typeof body.name !== 'string' ||
      !body.name.trim() ||
      body.name !== body.name.trim() ||
      body.name.length > 255)
  )
    return error(400, 'MALFORMED_REQUEST');
  if (body.role !== undefined && !ROLES.includes(body.role as Role))
    return error(400, 'MALFORMED_REQUEST');
  if (body.rank !== undefined && !RANKS.includes(String(body.rank)))
    return error(400, 'MALFORMED_REQUEST');
  const snapshot = () =>
    currentNode
      ? structuredClone(currentNode)
      : resource[0] === 'grants'
        ? structuredClone(grantAt(resource[1]) ?? null)
        : resource[0] === 'rank-grants'
          ? structuredClone(rankAt(resource[1]) ?? null)
          : isPlatform && resource[2] === 'org-units'
            ? structuredClone(
                state.organizations.find((value) => value.id === resource[3]) ??
                  null,
              )
            : parts[1] === 'collab'
              ? structuredClone(
                  state.rooms.find((value) => value.id === parts[3]) ?? null,
                )
              : isPlatform && resource[0] === 'admin'
                ? structuredClone(
                    state.people.find(
                      (value) => value.subject === resource[2],
                    ) ?? null,
                  )
                : null;
  const before = snapshot();
  let eventKind = 'POLICY_REPLACED';
  let workspaceNodeId: string | null = nodeId ?? null;
  let created: string | undefined;
  if (url.pathname === '/api/rbac/workspaces' && method === 'POST') {
    const parent = state.nodes.find((node) => node.id === body.parentId);
    if (!parent || !isManager(parent.id)) return error(403, 'FORBIDDEN');
    const actorTeam = state.organizations.find(
      (unit) => unit.id === actor?.teamId,
    );
    if (!actorTeam || actorTeam.status !== 'ACTIVE')
      return error(403, 'FORBIDDEN');
    if (parent.status !== 'ACTIVE' || parent.kind === 'COMMON')
      return error(409, 'RBAC_STATE_CONFLICT');
    if (!body.name || !['ORG', 'WORK'].includes(String(body.kind)))
      return error(400, 'MALFORMED_REQUEST');
    if (
      depth(parent) >= MAX_DEPTH ||
      duplicateSibling(parent.id, String(body.name))
    )
      return error(409, 'RBAC_STATE_CONFLICT');
    created = crypto.randomUUID();
    workspaceNodeId = created;
    eventKind = 'NODE_CREATED';
    state.nodes.push({
      id: created,
      parentId: parent.id,
      name: String(body.name),
      kind: body.kind as 'ORG' | 'WORK',
      status: 'ACTIVE',
      declared: false,
      actions: [],
    });
    state.grants.push({
      id: crypto.randomUUID(),
      orgUnitId: actorTeam.id,
      orgUnitName: actorTeam.name,
      orgUnitStatus: 'ACTIVE',
      role: 'ADMIN',
      declared: false,
      ...{ workspaceId: created },
    });
  } else if (currentNode && resource.length === 3) {
    const action = resource[2];
    if (currentNode.status !== 'ACTIVE' && action !== 'reactivate')
      return error(403, 'FORBIDDEN');
    if (action === 'grants' || action === 'rank-grants') {
      if (currentNode.kind === 'ROOT') return error(400, 'MALFORMED_REQUEST');
      if (!body.role || (action === 'rank-grants' && !body.rank))
        return error(400, 'MALFORMED_REQUEST');
      const organization = state.organizations.find(
        (unit) => unit.id === body.orgUnitId,
      );
      if (
        !(action === 'rank-grants' && body.orgUnitId === null) &&
        organization?.status !== 'ACTIVE'
      )
        return error(409, 'RBAC_STATE_CONFLICT');
      created = crypto.randomUUID();
      eventKind = 'POLICY_ADDED';
      if (action === 'grants') {
        if (
          state.grants.some(
            (value) =>
              (value as StoredGrant).workspaceId === nodeId &&
              value.orgUnitId === body.orgUnitId &&
              value.role === body.role,
          )
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        state.grants.push({
          id: created,
          orgUnitId: String(body.orgUnitId),
          orgUnitName: organization?.name ?? '',
          orgUnitStatus: 'ACTIVE',
          role: body.role as Role,
          declared: false,
          workspaceId: currentNode.id,
        });
      } else {
        if (
          state.ranks.some(
            (value) =>
              (value as StoredRank).workspaceId === nodeId &&
              value.orgUnitId === body.orgUnitId &&
              value.rank === body.rank &&
              value.role === body.role,
          )
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        state.ranks.push({
          id: created,
          orgUnitId: body.orgUnitId as string | null,
          rank: String(body.rank),
          role: body.role as Role,
          declared: false,
          workspaceId: currentNode.id,
        });
      }
    } else {
      if (currentNode.declared || ['ROOT', 'COMMON'].includes(currentNode.kind))
        return error(409, 'RBAC_STATE_CONFLICT');
      if (action === 'name') {
        if (!body.name) return error(400, 'MALFORMED_REQUEST');
        if (
          duplicateSibling(
            currentNode.parentId,
            String(body.name),
            currentNode.id,
          )
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        currentNode.name = String(body.name);
        eventKind = 'NODE_RENAMED';
      } else if (action === 'parent') {
        const target = state.nodes.find((value) => value.id === body.parentId);
        const oldParent = state.nodes.find(
          (value) => value.id === currentNode.parentId,
        );
        if (
          !target ||
          !isManager(target.id) ||
          !currentNode.parentId ||
          !isManager(currentNode.parentId)
        )
          return error(403, 'FORBIDDEN');
        if (
          target.id === currentNode.id ||
          target.id === currentNode.parentId ||
          target.kind === 'ROOT' ||
          oldParent?.kind === 'ROOT' ||
          target.kind === 'COMMON' ||
          target.status !== 'ACTIVE' ||
          state.nodes.some((value) => value.parentId === nodeId) ||
          state.rooms.some((value) => value.workspaceId === nodeId)
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        if (
          depth(target) >= MAX_DEPTH ||
          duplicateSibling(target.id, currentNode.name, currentNode.id)
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        const previous = state.grants.filter(
          (grant) => grant.workspaceId === nodeId,
        );
        if (
          !previous.some((grant) => grant.role === 'ADMIN') ||
          previous.some(
            (grant) =>
              grant.declared ||
              state.organizations.find((unit) => unit.id === grant.orgUnitId)
                ?.status !== 'ACTIVE',
          )
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        currentNode.parentId = target.id;
        eventKind = 'NODE_REPARENTED';
      } else if (action === 'deactivate') {
        const subtree = new Set([currentNode.id]);
        let changed = true;
        while (changed) {
          changed = false;
          for (const node of state.nodes)
            if (
              node.parentId &&
              subtree.has(node.parentId) &&
              !subtree.has(node.id)
            ) {
              subtree.add(node.id);
              changed = true;
            }
        }
        if (
          state.nodes.some(
            (value) =>
              subtree.has(value.id) &&
              value.status === 'ACTIVE' &&
              !isManager(value.id),
          )
        )
          return error(403, 'FORBIDDEN');
        if (
          state.rooms.some((value) => subtree.has(value.workspaceId)) ||
          state.nodes.some((value) => subtree.has(value.id) && value.declared)
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        for (const node of state.nodes)
          if (subtree.has(node.id) && node.status === 'ACTIVE') {
            deactivated.push({ node, before: structuredClone(node) });
            node.status = 'INACTIVE';
          }
        eventKind = 'NODE_DEACTIVATED';
      } else if (action === 'reactivate') {
        if (
          currentNode.status !== 'INACTIVE' ||
          state.nodes.find((value) => value.id === currentNode.parentId)
            ?.status !== 'ACTIVE'
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        if (
          duplicateSibling(
            currentNode.parentId,
            currentNode.name,
            currentNode.id,
          )
        )
          return error(409, 'RBAC_STATE_CONFLICT');
        currentNode.status = 'ACTIVE';
        eventKind = 'NODE_REACTIVATED';
      } else return error(404, 'NOT_FOUND');
    }
  } else if (resource[0] === 'grants' || resource[0] === 'rank-grants') {
    const grant =
      resource[0] === 'grants' ? grantAt(resource[1]) : rankAt(resource[1]);
    if (!grant) return error(404, 'NOT_FOUND');
    if (!grant.workspaceId || !isManager(grant.workspaceId))
      return error(403, 'FORBIDDEN');
    if (
      state.nodes.find((node) => node.id === grant.workspaceId)?.status !==
      'ACTIVE'
    )
      return error(403, 'FORBIDDEN');
    workspaceNodeId = grant.workspaceId;
    if (grant.declared) return error(409, 'RBAC_STATE_CONFLICT');
    if (
      resource[0] === 'grants' &&
      grant.role === 'VIEWER' &&
      state.nodes.find((node) => node.id === grant.workspaceId)?.kind ===
        'COMMON'
    )
      return error(409, 'RBAC_STATE_CONFLICT');
    if (method !== 'DELETE') {
      const nextRole = resource[2] === 'role' ? body.role : grant.role;
      const nextRank =
        'rank' in grant
          ? resource[2] === 'rank'
            ? body.rank
            : grant.rank
          : undefined;
      if (!nextRole || ('rank' in grant && !nextRank))
        return error(400, 'MALFORMED_REQUEST');
      if (
        nextRole === grant.role &&
        (!('rank' in grant) || nextRank === grant.rank)
      )
        return new Response(null, { status: 204 });
      const policies = resource[0] === 'grants' ? state.grants : state.ranks;
      if (
        policies.some(
          (other) =>
            other.id !== grant.id &&
            other.workspaceId === grant.workspaceId &&
            other.orgUnitId === grant.orgUnitId &&
            other.role === nextRole &&
            (!('rank' in other) || other.rank === nextRank),
        )
      )
        return error(409, 'RBAC_STATE_CONFLICT');
    }
    if (
      resource[0] === 'grants' &&
      grant.role === 'ADMIN' &&
      (method === 'DELETE' || body.role !== 'ADMIN') &&
      !state.grants.some(
        (value) =>
          value.id !== grant.id &&
          (value as StoredGrant).workspaceId === grant.workspaceId &&
          value.role === 'ADMIN' &&
          state.organizations.some(
            (unit) => unit.id === value.orgUnitId && unit.status === 'ACTIVE',
          ),
      )
    )
      return error(409, 'RBAC_STATE_CONFLICT');
    if (method === 'DELETE') {
      state.grants = state.grants.filter((value) => value.id !== grant.id);
      state.ranks = state.ranks.filter((value) => value.id !== grant.id);
      eventKind = 'POLICY_REMOVED';
    } else if (resource[2] === 'role' && body.role)
      grant.role = body.role as Role;
    else if (resource[2] === 'rank' && body.rank && 'rank' in grant)
      grant.rank = String(body.rank);
    else return error(400, 'MALFORMED_REQUEST');
  } else if (
    isPlatform &&
    resource[0] === 'tenants' &&
    resource[2] === 'org-units'
  ) {
    const organization = state.organizations.find(
      (value) => value.id === resource[3],
    );
    if (resource.length === 3 && method === 'POST') {
      if (
        !body.name ||
        typeof body.key !== 'string' ||
        !/^[a-z][a-z0-9-]{0,62}$/.test(body.key)
      )
        return error(400, 'MALFORMED_REQUEST');
      if (state.organizations.some((value) => value.key === body.key))
        return error(409, 'RBAC_STATE_CONFLICT');
      created = crypto.randomUUID();
      state.organizations.push({
        id: created,
        key: body.key,
        name: String(body.name),
        status: 'ACTIVE',
        declared: false,
      });
      state.grants.push({
        id: crypto.randomUUID(),
        orgUnitId: created,
        orgUnitName: String(body.name),
        orgUnitStatus: 'ACTIVE',
        role: 'VIEWER',
        declared: true,
        ...{ workspaceId: COMMON },
      });
      eventKind = 'ORG_UNIT_CREATED';
    } else {
      if (!organization) return error(404, 'NOT_FOUND');
      if (organization.declared) return error(409, 'RBAC_STATE_CONFLICT');
      if (
        resource[4] === 'deactivate' &&
        (state.people.some(
          (person) => person.enabled && person.teamId === organization.id,
        ) ||
          state.grants.some(
            (grant) =>
              grant.orgUnitId === organization.id &&
              grant.role === 'ADMIN' &&
              !state.grants.some(
                (other) =>
                  other.workspaceId === grant.workspaceId &&
                  other.orgUnitId !== organization.id &&
                  other.role === 'ADMIN' &&
                  state.organizations.some(
                    (unit) =>
                      unit.id === other.orgUnitId && unit.status === 'ACTIVE',
                  ),
              ),
          ))
      )
        return error(409, 'RBAC_STATE_CONFLICT');
      if (
        (resource[4] === 'deactivate' && organization.status === 'INACTIVE') ||
        (resource[4] === 'reactivate' && organization.status === 'ACTIVE')
      )
        return error(409, 'RBAC_STATE_CONFLICT');
      if (resource[4] === 'name' && body.name)
        organization.name = String(body.name);
      else if (resource[4] === 'deactivate') organization.status = 'INACTIVE';
      else if (resource[4] === 'reactivate') organization.status = 'ACTIVE';
      else return error(400, 'MALFORMED_REQUEST');
      for (const grant of state.grants)
        if (grant.orgUnitId === organization.id) {
          grant.orgUnitName = organization.name;
          grant.orgUnitStatus = organization.status;
        }
      eventKind =
        resource[4] === 'name'
          ? 'ORG_UNIT_RENAMED'
          : resource[4] === 'deactivate'
            ? 'ORG_UNIT_DEACTIVATED'
            : 'ORG_UNIT_REACTIVATED';
    }
  } else if (
    parts[1] === 'collab' &&
    parts[2] === 'threads' &&
    parts[4] === 'workspace'
  ) {
    const room = state.rooms.find((value) => value.id === parts[3]);
    if (!room) return error(404, 'NOT_FOUND');
    const target = state.nodes.find((value) => value.id === body.workspaceId);
    if (
      state.nodes.find((node) => node.id === room.workspaceId)?.status !==
      'ACTIVE'
    )
      return error(403, 'FORBIDDEN');
    if (!isManager(room.workspaceId) || !target || !isManager(target.id))
      return error(403, 'FORBIDDEN');
    if (
      target.status !== 'ACTIVE' ||
      target.kind === 'ROOT' ||
      target.id === room.workspaceId
    )
      return error(409, 'RBAC_STATE_CONFLICT');
    room.workspaceId = target.id;
    workspaceNodeId = target.id;
    eventKind = 'THREAD_MOVED';
  } else return error(404, 'NOT_FOUND');
  const targetKind = isPlatform
    ? 'ORG_UNIT'
    : eventKind.startsWith('NODE')
      ? 'WORKSPACE'
      : eventKind === 'THREAD_MOVED'
        ? 'THREAD'
        : 'POLICY';
  const after = created
    ? structuredClone(
        state.nodes.find((value) => value.id === created) ??
          state.grants.find((value) => value.id === created) ??
          state.ranks.find((value) => value.id === created) ??
          state.organizations.find((value) => value.id === created) ??
          null,
      )
    : snapshot();
  const requestId = crypto.randomUUID();
  const policy =
    state.grants.find((value) => value.id === created) ??
    state.ranks.find((value) => value.id === created) ??
    (resource[0] === 'grants'
      ? (grantAt(resource[1]) ?? before)
      : resource[0] === 'rank-grants'
        ? (rankAt(resource[1]) ?? before)
        : null);
  const organization =
    isPlatform && resource[2] === 'org-units'
      ? state.organizations.find(
          (value) => value.id === (created ?? resource[3]),
        )
      : null;
  const targetRef =
    eventKind === 'THREAD_MOVED'
      ? { thread_id: parts[3] }
      : organization
        ? { org_unit_key: organization.key }
        : targetKind === 'POLICY' && policy && 'orgUnitId' in policy
          ? {
              ...('rank' in policy
                ? { rank_grn_id: policy.id, rank: policy.rank }
                : { wrk_grn_id: policy.id }),
              org_unit_key:
                state.organizations.find((unit) => unit.id === policy.orgUnitId)
                  ?.key ?? null,
              role: policy.role,
              wrk_node_id: workspaceNodeId,
            }
          : { id: created ?? nodeId ?? resource[1] };
  if (deactivated.length) {
    for (const { node, before: previous } of deactivated) {
      appendAudit(
        state,
        eventKind,
        targetKind,
        node.id,
        { wrk_node_id: node.id },
        previous,
        structuredClone(node),
        requestId,
      );
    }
  } else
    appendAudit(
      state,
      eventKind,
      targetKind,
      workspaceNodeId,
      targetRef,
      created ? null : before,
      after,
      requestId,
    );
  if (eventKind === 'ORG_UNIT_CREATED' || eventKind === 'NODE_CREATED') {
    const requiredGrant = state.grants.find((grant) =>
      eventKind === 'ORG_UNIT_CREATED'
        ? grant.orgUnitId === created &&
          grant.workspaceId === COMMON &&
          grant.role === 'VIEWER'
        : grant.workspaceId === created && grant.role === 'ADMIN',
    );
    if (requiredGrant)
      appendAudit(
        state,
        'POLICY_ADDED',
        'POLICY',
        requiredGrant.workspaceId,
        {
          wrk_grn_id: requiredGrant.id,
          wrk_node_id: requiredGrant.workspaceId,
          org_unit_key: state.organizations.find(
            (unit) => unit.id === requiredGrant.orgUnitId,
          )?.key,
          role: requiredGrant.role,
        },
        null,
        structuredClone(requiredGrant),
        requestId,
      );
  }
  if (
    eventKind === 'THREAD_MOVED' &&
    before &&
    'workspaceId' in before &&
    typeof before.workspaceId === 'string'
  ) {
    appendAudit(
      state,
      eventKind,
      targetKind,
      before.workspaceId,
      targetRef,
      before,
      after,
      requestId,
    );
  }
  return created
    ? Response.json({ id: created }, { status: 201 })
    : new Response(null, { status: 204 });
}

function appendAudit(
  state: RbacMockState,
  eventKind: string,
  targetKind: string,
  workspaceNodeId: string | null,
  targetRef: unknown,
  beforeJson: unknown,
  afterJson: unknown,
  requestId = crypto.randomUUID(),
) {
  state.audits.push({
    id: crypto.randomUUID(),
    tenantId: TENANT,
    actorKind: 'USER',
    actorUserId: ACTOR_USER,
    actorRoleJson: [],
    eventKind,
    targetKind,
    targetRef,
    workspaceNodeId,
    beforeJson,
    afterJson,
    requestId,
    traceId: null,
    deploymentId: null,
    configurationFingerprint: null,
    createdAt: new Date().toISOString(),
  });
}
