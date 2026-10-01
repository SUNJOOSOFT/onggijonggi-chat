import { beforeEach, expect, it, vi } from 'vitest';
vi.mock('./http', () => ({ authFetch: vi.fn() }));
import { authFetch } from './http';
import { loadAudits, managementAccess, rbacRequest } from './rbac-management';
const fetcher = vi.mocked(authFetch);
beforeEach(() => fetcher.mockReset());
it('플랫폼만 있는 요청자를 Workspace 관리자로 표시하지 않는다', async () => {
  fetcher
    .mockResolvedValueOnce(Response.json({ workspaceManagement: false }))
    .mockResolvedValueOnce(Response.json({ enabled: true }));
  expect(await managementAccess()).toEqual({
    workspace: false,
    platform: true,
  });
});
it('403은 진입 불가지만 503은 기능 미기동으로 숨기지 않는다', async () => {
  fetcher
    .mockResolvedValueOnce(new Response(null, { status: 403 }))
    .mockResolvedValueOnce(new Response(null, { status: 404 }));
  expect(await managementAccess()).toEqual({
    workspace: false,
    platform: false,
  });
  fetcher.mockResolvedValue(new Response(null, { status: 503 }));
  await expect(managementAccess()).rejects.toThrow();
});
it('커서 조회에도 필터와 범위를 함께 전달한다', async () => {
  fetcher.mockResolvedValue(Response.json({ items: [], nextCursor: null }));
  await loadAudits(
    'tenant',
    'tenant-id',
    { requestId: 'request', eventKind: 'POLICY_ADDED', from: '' },
    'opaque',
  );
  expect(fetcher.mock.calls[0][0]).toBe(
    '/api/platform/rbac/tenants/tenant-id/authorization-audits?requestId=request&eventKind=POLICY_ADDED&cursor=opaque',
  );
});
it('오류는 code 문구만 표시하고 저장 요청을 다시 보내지 않는다', async () => {
  fetcher.mockResolvedValue(
    Response.json(
      { error: { code: 'FORBIDDEN', message: 'private', traceId: 'trace' } },
      { status: 403 },
    ),
  );
  await expect(rbacRequest('/api/rbac/grants/id', 'DELETE')).rejects.toThrow(
    '이 작업을 수행할 권한이 없어요. (추적 ID: trace)',
  );
  expect(fetcher).toHaveBeenCalledTimes(1);
});
