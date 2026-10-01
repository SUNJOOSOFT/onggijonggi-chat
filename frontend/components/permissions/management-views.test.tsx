import type {
  AuditItem,
  AuditPage,
  ManagedWorkspace,
} from '@/lib/api/rbac-management';
// @vitest-environment jsdom
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AuditManagement } from './audit-management';
import { WorkspaceManagement } from './workspace-management';

const api = vi.hoisted(() => ({
  managementAccess: vi.fn(),
  listManagedWorkspaces: vi.fn(),
  listTenants: vi.fn(),
  loadAudits: vi.fn(),
  listOrganizations: vi.fn(),
  listTeamGrants: vi.fn(),
  listRankGrants: vi.fn(),
  listManagedThreads: vi.fn(),
  rbacRequest: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('sonner', () => ({
  toast: { success: api.success, warning: api.warning },
}));
vi.mock('@/lib/api/rbac-management', () => ({
  ...api,
  workspacePath: (id: string) => `/api/rbac/workspaces/${id}`,
}));
afterEach(cleanup);
const node: ManagedWorkspace = {
  id: 'workspace',
  parentId: null,
  name: '개발팀',
  kind: 'ORG',
  status: 'ACTIVE',
  declared: false,
  actions: ['RENAME', 'GRANTS'],
};
it('비활성화 성공 뒤 이전 ACTIVE 상세를 재조회하거나 실패 경고를 띄우지 않는다', async () => {
  api.listManagedWorkspaces.mockResolvedValue([
    { ...node, actions: ['DEACTIVATE'] },
  ]);
  render(<WorkspaceManagement />);
  const deactivate = await screen.findByRole('button', {
    name: '하위 트리 비활성화',
  });
  await waitFor(() =>
    expect((deactivate as HTMLButtonElement).disabled).toBe(false),
  );
  for (const fn of [
    api.listOrganizations,
    api.listTeamGrants,
    api.listRankGrants,
    api.listManagedThreads,
  ]) {
    fn.mockRejectedValue(new Error('비활성 노드 상세 조회 거부'));
  }
  api.listManagedWorkspaces.mockResolvedValue([
    { ...node, status: 'INACTIVE', actions: ['REACTIVATE'] },
  ]);
  fireEvent.click(deactivate);
  fireEvent.click(await screen.findByRole('button', { name: '확인하고 저장' }));
  await screen.findByRole('button', { name: '대상 재활성화' });
  await waitFor(() =>
    expect(api.success).toHaveBeenCalledWith('하위 트리 비활성화: 변경 완료'),
  );
  expect(api.rbacRequest).toHaveBeenCalledWith(
    '/api/rbac/workspaces/workspace/deactivate',
    'POST',
  );
  for (const fn of [
    api.listOrganizations,
    api.listTeamGrants,
    api.listRankGrants,
    api.listManagedThreads,
  ]) {
    expect(fn).toHaveBeenCalledTimes(1);
  }
  expect(api.warning).not.toHaveBeenCalled();
  expect(screen.queryByRole('alert')).toBeNull();
});
const audit = (id: string): AuditItem => ({
  id,
  tenantId: 'tenant',
  actorKind: 'USER',
  actorUserId: 'actor',
  actorRoleJson: ['USER'],
  eventKind: id,
  targetKind: 'WORKSPACE',
  targetRef: {},
  workspaceNodeId: node.id,
  beforeJson: {},
  afterJson: { name: '변경' },
  requestId: null,
  traceId: null,
  deploymentId: null,
  configurationFingerprint: null,
  createdAt: '2026-09-30T00:00:00Z',
});
beforeEach(() => {
  vi.resetAllMocks();
  api.managementAccess.mockResolvedValue({ workspace: true, platform: false });
  api.listManagedWorkspaces.mockResolvedValue([node]);
  api.listTenants.mockResolvedValue([]);
  for (const fn of [
    api.listOrganizations,
    api.listTeamGrants,
    api.listRankGrants,
    api.listManagedThreads,
  ])
    fn.mockResolvedValue([]);
  api.rbacRequest.mockResolvedValue(undefined);
});

it('비활성 Workspace는 재활성화만 표시하고 상세를 요청하지 않는다', async () => {
  api.listManagedWorkspaces.mockResolvedValue([
    { ...node, status: 'INACTIVE', actions: ['REACTIVATE'] },
  ]);
  render(<WorkspaceManagement />);
  await screen.findByRole('button', { name: '대상 재활성화' });
  expect(api.listOrganizations).not.toHaveBeenCalled();
  expect(api.listTeamGrants).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: '팀 부여 추가' })).toBeNull();
});

it('ROOT의 직접 관리자는 조직을 선택해도 팀 부여를 추가할 수 없다', async () => {
  api.listManagedWorkspaces.mockResolvedValue([
    { ...node, kind: 'ROOT', actions: ['CREATE'] },
  ]);
  api.listOrganizations.mockResolvedValue([
    { id: 'org', name: '개발 조직', status: 'ACTIVE' },
  ]);
  render(<WorkspaceManagement />);
  await screen.findAllByRole('option', { name: '개발 조직' });
  fireEvent.change(screen.getByLabelText('부여 조직'), {
    target: { value: 'org' },
  });
  expect(
    (screen.getByRole('button', { name: '팀 부여 추가' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(api.rbacRequest).not.toHaveBeenCalled();
});

it('자기 MANAGE 회수로 목록에서 사라진 노드는 상세 재조회와 장애 경고를 생략한다', async () => {
  api.listTeamGrants.mockResolvedValue([
    {
      id: 'own-grant',
      orgUnitId: 'org',
      orgUnitName: '내 조직',
      orgUnitStatus: 'ACTIVE',
      role: 'ADMIN',
      declared: false,
    },
  ]);
  render(<WorkspaceManagement />);
  await screen.findByText('내 조직 (ACTIVE)');
  api.listManagedWorkspaces.mockResolvedValue([]);
  for (const fn of [
    api.listOrganizations,
    api.listTeamGrants,
    api.listRankGrants,
    api.listManagedThreads,
  ]) {
    fn.mockRejectedValue(new Error('회수된 노드 상세 조회 거부'));
  }
  fireEvent.click(screen.getByRole('button', { name: '팀 부여 삭제' }));
  fireEvent.click(await screen.findByRole('button', { name: '확인하고 저장' }));
  await screen.findByText('현재 직접 관리 권한이 있는 Workspace가 없습니다.');
  await waitFor(() =>
    expect(api.success).toHaveBeenCalledWith('팀 부여 삭제: 변경 완료'),
  );
  expect(api.rbacRequest).toHaveBeenCalledWith(
    '/api/rbac/grants/own-grant',
    'DELETE',
  );
  for (const fn of [
    api.listOrganizations,
    api.listTeamGrants,
    api.listRankGrants,
    api.listManagedThreads,
  ]) {
    expect(fn).toHaveBeenCalledTimes(1);
  }
  expect(api.warning).not.toHaveBeenCalled();
});

it('이름 저장 실패에는 입력이 남고 성공 뒤 권한 회수에는 상세가 사라진다', async () => {
  api.rbacRequest
    .mockRejectedValueOnce(new Error('권한 거부'))
    .mockResolvedValueOnce(undefined);
  render(<WorkspaceManagement />);
  await waitFor(() =>
    expect(
      (screen.getByRole('button', { name: '이름 저장' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false),
  );
  fireEvent.change(screen.getByLabelText('Workspace 이름'), {
    target: { value: '변경 이름' },
  });
  fireEvent.click(screen.getByRole('button', { name: '이름 저장' }));
  fireEvent.click(await screen.findByRole('button', { name: '확인하고 저장' }));
  await screen.findByText('권한 거부');
  expect(
    (
      screen.getByLabelText('Workspace 이름', {
        selector: 'input',
      }) as HTMLInputElement
    ).value,
  ).toBe('변경 이름');
  expect(api.listManagedWorkspaces).toHaveBeenCalledTimes(1);
  api.listManagedWorkspaces.mockResolvedValue([]);
  fireEvent.click(screen.getByRole('button', { name: '확인하고 저장' }));
  await screen.findByText('현재 직접 관리 권한이 있는 Workspace가 없습니다.');
  expect(api.rbacRequest).toHaveBeenLastCalledWith(
    '/api/rbac/workspaces/workspace/name',
    'PATCH',
    { name: '변경 이름' },
  );
  expect(screen.queryByLabelText('Workspace 이름')).toBeNull();
  expect(api.success).toHaveBeenCalledWith('이름 저장: 변경 완료');
});

it('목록을 다시 조회하면 같은 Workspace의 부여와 방 상세도 갱신한다', async () => {
  render(<WorkspaceManagement />);
  await waitFor(() => expect(api.listTeamGrants).toHaveBeenCalledTimes(1));
  api.listManagedThreads.mockResolvedValue([
    { id: 'room', title: '다른 관리자가 옮긴 방', status: 'ACTIVE' },
  ]);
  fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
  await screen.findByText('다른 관리자가 옮긴 방 (ACTIVE)');
  expect(api.listTeamGrants).toHaveBeenCalledTimes(2);
});

it('수동 재조회는 수정하지 않은 이름만 최신화하고 작성 중인 입력은 보존한다', async () => {
  render(<WorkspaceManagement />);
  await screen.findByLabelText('Workspace 이름');
  api.listManagedWorkspaces.mockResolvedValue([{ ...node, name: '최신 이름' }]);
  fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
  await waitFor(() =>
    expect(
      (screen.getByLabelText('Workspace 이름') as HTMLInputElement).value,
    ).toBe('최신 이름'),
  );
  fireEvent.change(screen.getByLabelText('Workspace 이름'), {
    target: { value: '아직 저장하지 않은 입력' },
  });
  api.listManagedWorkspaces.mockResolvedValue([{ ...node, name: '다음 이름' }]);
  fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
  await screen.findByText('다음 이름 (ACTIVE)', { selector: 'option' });
  expect(
    (screen.getByLabelText('Workspace 이름') as HTMLInputElement).value,
  ).toBe('아직 저장하지 않은 입력');
});

it('저장 성공 뒤 목록 조회가 실패해 상세가 사라져도 성공·조회 실패를 알린다', async () => {
  render(<WorkspaceManagement />);
  await waitFor(() =>
    expect(
      (screen.getByRole('button', { name: '이름 저장' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false),
  );
  api.listManagedWorkspaces.mockRejectedValue(new Error('조회 연결 실패'));
  fireEvent.click(screen.getByRole('button', { name: '이름 저장' }));
  fireEvent.click(await screen.findByRole('button', { name: '확인하고 저장' }));
  await screen.findByText('조회 연결 실패');
  await waitFor(() =>
    expect(api.warning).toHaveBeenCalledWith(
      expect.stringContaining('변경 완료, 최신 정보 조회 실패'),
    ),
  );
  expect(api.success).toHaveBeenCalledWith('이름 저장: 변경 완료');
  expect(api.rbacRequest).toHaveBeenCalledTimes(1);
  expect(screen.queryByLabelText('Workspace 이름')).toBeNull();
});

it('감사 필터는 커서를 초기화하고 늦은 이전 페이지를 버린다', async () => {
  let finish!: (page: AuditPage) => void;
  api.loadAudits
    .mockResolvedValueOnce({ items: [audit('처음')], nextCursor: 'cursor' })
    .mockImplementationOnce(
      () =>
        new Promise<AuditPage>((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValueOnce({ items: [audit('필터 결과')], nextCursor: null });
  render(<AuditManagement />);
  await screen.findByText(/처음/, { selector: 'summary' });
  fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
  fireEvent.change(screen.getByLabelText('이벤트 종류'), {
    target: { value: 'NODE_CREATED' },
  });
  fireEvent.click(screen.getByRole('button', { name: '필터 적용' }));
  await screen.findByText(/필터 결과/, { selector: 'summary' });
  await act(async () =>
    finish({ items: [audit('늦은 페이지')], nextCursor: null }),
  );
  expect(screen.queryByText(/늦은 페이지/)).toBeNull();
  expect(api.loadAudits).toHaveBeenLastCalledWith('workspace', node.id, {
    eventKind: 'NODE_CREATED',
  });
});

it('감사 다음 페이지에서 권한이 회수되면 기존 결과도 숨긴다', async () => {
  api.loadAudits
    .mockResolvedValueOnce({
      items: [audit('민감한 기록')],
      nextCursor: 'next',
    })
    .mockRejectedValueOnce(new Error('권한 회수'));
  render(<AuditManagement />);
  await screen.findByText(/민감한 기록/, { selector: 'summary' });
  fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
  await screen.findByText('권한 회수');
  expect(screen.queryByText(/민감한 기록/)).toBeNull();
});

it('플랫폼 감사는 비활성 Tenant와 null Workspace 행도 표시한다', async () => {
  api.managementAccess.mockResolvedValue({ workspace: false, platform: true });
  api.listTenants.mockResolvedValue([
    { id: 'tenant', key: 'tenant', name: '비활성 회사', status: 'INACTIVE' },
  ]);
  api.loadAudits.mockResolvedValue({
    items: [{ ...audit('플랫폼 기록'), workspaceNodeId: null }],
    nextCursor: null,
  });
  render(<AuditManagement />);
  await screen.findByText(/플랫폼 기록/, { selector: 'summary' });
  expect(api.listManagedWorkspaces).not.toHaveBeenCalled();
  expect(api.loadAudits).toHaveBeenCalledWith('tenant', 'tenant', {});
});
