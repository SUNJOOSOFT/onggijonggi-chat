/********************************************************
 파일명 : rbac-management.ts
 설 명 : 권한 관리·감사 화면의 최소 조회와 기존 변경 API. 인가는 서버가 매 요청 다시 검사한다.
 *********************************************************/
import { bffUrl } from './config';
import { friendlyMessageForCode, parseErrorEnvelope } from './errors';
import { authFetch } from './http';

export type Role = 'VIEWER' | 'CONTRIBUTOR' | 'ADMIN';
export type Status = 'ACTIVE' | 'INACTIVE';
export interface ManagedWorkspace {
  id: string;
  parentId: string | null;
  name: string;
  kind: 'ROOT' | 'COMMON' | 'ORG' | 'WORK';
  status: Status;
  declared: boolean;
  actions: string[];
}
export interface Organization {
  id: string;
  name: string;
  status: Status;
}
export interface PlatformOrganization extends Organization {
  key: string;
  declared: boolean;
}
export interface Tenant {
  id: string;
  key: string;
  name: string;
  status: Status;
}
export interface TeamGrant {
  id: string;
  orgUnitId: string;
  orgUnitName: string;
  orgUnitStatus: Status;
  role: Role;
  declared: boolean;
}
export interface RankGrant {
  id: string;
  orgUnitId: string | null;
  rank: string;
  role: Role;
  declared: boolean;
}
export interface ManagedThread {
  id: string;
  title: string;
  status: string;
}
export interface AuditItem {
  id: string;
  tenantId: string;
  actorKind: string;
  actorUserId: string | null;
  actorRoleJson: unknown;
  eventKind: string;
  targetKind: string;
  targetRef: unknown;
  workspaceNodeId: string | null;
  beforeJson: unknown;
  afterJson: unknown;
  requestId: string | null;
  traceId: string | null;
  deploymentId: string | null;
  configurationFingerprint: string | null;
  createdAt: string;
}
export interface AuditPage {
  items: AuditItem[];
  nextCursor: string | null;
}
export class RbacApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

export async function rbacRequest<T>(
  path: string,
  method = 'GET',
  body?: unknown,
): Promise<T> {
  const response = await authFetch(bffUrl(path), {
    method,
    ...(body === undefined
      ? {}
      : {
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }),
  });
  if (!response.ok) {
    const error = parseErrorEnvelope(await response.text());
    throw new RbacApiError(
      response.status,
      `${friendlyMessageForCode(error?.code)}${error?.traceId ? ` (추적 ID: ${error.traceId})` : ''}`,
    );
  }
  return response.status === 204
    ? (undefined as T)
    : (response.json() as Promise<T>);
}
const key = encodeURIComponent;
export const workspacePath = (id: string) => `/api/rbac/workspaces/${key(id)}`;
export const platformOrgPath = (tenantKey: string) =>
  `/api/platform/rbac/tenants/${key(tenantKey)}/org-units`;
export const listManagedWorkspaces = () =>
  rbacRequest<ManagedWorkspace[]>('/api/rbac/workspaces');
export const listTenants = () =>
  rbacRequest<Tenant[]>('/api/platform/rbac/tenants');
export const listOrganizations = (id: string) =>
  rbacRequest<Organization[]>(`${workspacePath(id)}/org-units`);
export const listTeamGrants = (id: string) =>
  rbacRequest<TeamGrant[]>(`${workspacePath(id)}/grants`);
export const listRankGrants = (id: string) =>
  rbacRequest<RankGrant[]>(`${workspacePath(id)}/rank-grants`);
export const listManagedThreads = (id: string) =>
  rbacRequest<ManagedThread[]>(`${workspacePath(id)}/threads`);

/** 각 경계의 403/404만 진입 불가로 처리하고, 통신·서버 장애는 호출자에게 알린다. */
export async function managementAccess() {
  async function optional<T>(path: string, fallback: T): Promise<T> {
    try {
      return await rbacRequest<T>(path);
    } catch (error) {
      if (
        error instanceof RbacApiError &&
        (error.status === 403 || error.status === 404)
      )
        return fallback;
      throw error;
    }
  }
  const [workspace, platform] = await Promise.all([
    optional('/api/rbac/admin/context', { workspaceManagement: false }),
    optional('/api/platform/rbac/admin/status', { enabled: false }),
  ]);
  return {
    workspace: workspace.workspaceManagement,
    platform: platform.enabled,
  };
}

export function loadAudits(
  scope: 'workspace' | 'tenant',
  id: string,
  filters: Record<string, string>,
  cursor?: string,
) {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(filters))
    if (value) query.set(name, value);
  if (cursor) query.set('cursor', cursor);
  const path =
    scope === 'workspace'
      ? `/api/workspaces/${key(id)}/authorization-audits`
      : `/api/platform/rbac/tenants/${key(id)}/authorization-audits`;
  return rbacRequest<AuditPage>(`${path}?${query}`);
}
