// @vitest-environment jsdom

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { PermissionsOverview } from '@/lib/api/permissions';
import { PermissionsAdmin } from './permissions-admin';

const mocks = vi.hoisted(() => ({
  overview: vi.fn(),
}));

vi.mock('@/lib/api/permissions', async (importOriginal) => ({
  PermissionsApiError: (
    await importOriginal<typeof import('@/lib/api/permissions')>()
  ).PermissionsApiError,
  fetchPermissionsOverview: mocks.overview,
}));

vi.mock('@/components/sidebar-toggle', () => ({ SidebarToggle: () => null }));

const assignedOverview: PermissionsOverview = {
  teams: [{ id: 'team-a', key: 'a', name: '팀 A' }],
  ranks: [{ code: 'S', label: '사원' }],
  workspaces: [],
  people: [
    {
      subject: 'A',
      username: 'a',
      name: '가',
      enabled: true,
      teamId: 'team-a',
      rank: 'S',
      visible: [],
    },
    {
      subject: 'B',
      username: 'b',
      name: '나',
      enabled: true,
      teamId: null,
      rank: null,
      visible: [],
    },
  ],
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it('팀·직급은 읽기만 하고 바꾸는 곳(속성 파일)을 안내한다', async () => {
  mocks.overview.mockResolvedValueOnce(assignedOverview);
  render(<PermissionsAdmin embedded />);

  const rows = await screen.findAllByRole('row');
  const assigned = rows.find((row) => row.textContent?.includes('가'));
  const unassigned = rows.find((row) => row.textContent?.includes('나'));
  expect(assigned?.textContent).toContain('팀 A');
  expect(assigned?.textContent).toContain('사원');
  expect(unassigned?.textContent).toContain('미배정');
  expect(screen.queryByRole('combobox')).toBeNull();
  expect(screen.queryByText('CSV로 넣기')).toBeNull();
  expect(screen.getByText(/members\.csv/)).toBeTruthy();
});

it('Keycloak 관리 연결 설정 문제에는 권한 기능·역할 확인 안내를 붙이지 않는다', async () => {
  const { PermissionsApiError } = await import('@/lib/api/permissions');
  mocks.overview.mockRejectedValueOnce(
    new PermissionsApiError(
      '관리 연결 설정 문구',
      'KEYCLOAK_ADMIN_UNAVAILABLE',
    ),
  );
  render(<PermissionsAdmin embedded />);
  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toContain('관리 연결 설정 문구');
  expect(alert.textContent).not.toContain('PLATFORM_ADMIN');

  cleanup();
  mocks.overview.mockRejectedValueOnce(
    new PermissionsApiError('권한 없음 문구', 'FORBIDDEN'),
  );
  render(<PermissionsAdmin embedded />);
  expect((await screen.findByRole('alert')).textContent).toContain(
    'PLATFORM_ADMIN',
  );
});
