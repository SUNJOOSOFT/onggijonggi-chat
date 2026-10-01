// @vitest-environment jsdom

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { StrictMode } from 'react';
import type { ImportReport, PermissionsOverview } from '@/lib/api/permissions';
import { PermissionsAdmin } from './permissions-admin';

const mocks = vi.hoisted(() => ({
  overview: vi.fn(),
  importCsv: vi.fn(),
  toastError: vi.fn(),
  save: vi.fn(),
  toastWarning: vi.fn(),
}));

vi.mock('@/lib/api/permissions', () => ({
  fetchPermissionsOverview: mocks.overview,
  importMembersCsv: mocks.importCsv,
  saveAssignment: mocks.save,
}));

vi.mock('@/components/sidebar-toggle', () => ({ SidebarToggle: () => null }));
vi.mock('sonner', () => ({
  toast: {
    error: mocks.toastError,
    success: vi.fn(),
    warning: mocks.toastWarning,
  },
}));

const overview: PermissionsOverview = {
  teams: [],
  ranks: [],
  workspaces: [],
  people: [],
};

const preview: ImportReport = {
  applied: false,
  counts: { ASSIGNED: 1 },
  rows: [],
  problems: [],
};

function csvFile(name: string, text: string): File {
  const file = new File([text], name, { type: 'text/csv' });
  Object.defineProperty(file, 'text', { value: async () => text });
  return file;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((next, fail) => {
    resolve = next;
    reject = fail;
  });
  return { promise, resolve, reject };
}

const assignedOverview: PermissionsOverview = {
  ...overview,
  teams: [
    { id: 'team-a', key: 'a', name: '팀 A' },
    { id: 'team-b', key: 'b', name: '팀 B' },
  ],
  ranks: [{ code: 'S', label: '사원' }],
  people: ['A', 'B'].map((name) => ({
    subject: name,
    username: name,
    name,
    enabled: true,
    teamId: 'team-a',
    rank: 'S',
    visible: [],
  })),
};

it('사람 배정 중 다른 사람·CSV 저장을 잠그고 중복 요청을 보내지 않는다', async () => {
  const save = deferred<void>();
  mocks.overview.mockResolvedValue(assignedOverview);
  mocks.save.mockReturnValue(save.promise);
  const view = render(<PermissionsAdmin embedded />);
  const first = await screen.findByRole('combobox', { name: 'A 팀' });
  const second = screen.getByRole('combobox', { name: 'B 팀' });
  const input = view.container.querySelector(
    'input[type="file"]',
  ) as HTMLInputElement;
  fireEvent.change(first, { target: { value: 'team-b' } });
  expect(first.hasAttribute('disabled')).toBe(true);
  expect(second.hasAttribute('disabled')).toBe(true);
  expect(input.disabled).toBe(true);
  fireEvent.change(second, { target: { value: 'team-b' } });
  fireEvent.change(first, { target: { value: 'team-a' } });
  expect(mocks.save).toHaveBeenCalledTimes(1);
  await act(async () => {
    save.resolve();
    await save.promise;
  });
  await waitFor(() => expect(second.hasAttribute('disabled')).toBe(false));
});

it.each(['성공', '실패'])(
  '늦은 초기 조회 %s가 최신 배정과 오류 상태를 덮지 않는다',
  async (outcome) => {
    const old = deferred<PermissionsOverview>();
    mocks.overview
      .mockReturnValueOnce(old.promise)
      .mockResolvedValue(assignedOverview);
    render(
      <StrictMode>
        <PermissionsAdmin embedded />
      </StrictMode>,
    );
    const latest = await screen.findByRole('combobox', { name: 'A 팀' });
    await act(async () => {
      if (outcome === '성공')
        old.resolve({
          ...assignedOverview,
          people: assignedOverview.people.map((person) => ({
            ...person,
            teamId: 'team-b',
          })),
        });
      else old.reject(new Error('old request failed'));
      await old.promise.catch(() => undefined);
    });
    expect((latest as HTMLSelectElement).value).toBe('team-a');
    expect(screen.queryByRole('alert')).toBeNull();
  },
);

it('CSV 적용 중 사람 배정과 반복 CSV 적용을 차단한다', async () => {
  const apply = deferred<ImportReport>();
  mocks.overview.mockResolvedValue(assignedOverview);
  mocks.importCsv
    .mockResolvedValueOnce(preview)
    .mockReturnValueOnce(apply.promise);
  const view = render(<PermissionsAdmin embedded />);
  await screen.findByRole('combobox', { name: 'A 팀' });
  const input = view.container.querySelector(
    'input[type="file"]',
  ) as HTMLInputElement;
  fireEvent.change(input, { target: { files: [csvFile('a.csv', 'a')] } });
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '저장' }).hasAttribute('disabled'),
    ).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  expect(
    screen.getByRole('combobox', { name: 'A 팀' }).hasAttribute('disabled'),
  ).toBe(true);
  fireEvent.change(screen.getByRole('combobox', { name: 'A 팀' }), {
    target: { value: 'team-b' },
  });
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  expect(mocks.save).not.toHaveBeenCalled();
  expect(mocks.importCsv).toHaveBeenCalledTimes(2);
  await act(async () => {
    apply.resolve({ ...preview, applied: true });
    await apply.promise;
  });
  await waitFor(() =>
    expect(
      screen.getByRole('combobox', { name: 'A 팀' }).hasAttribute('disabled'),
    ).toBe(false),
  );
});

it('배정 저장 뒤 조회 실패는 저장 실패로 바꾸지 않고 이전 편집을 숨긴다', async () => {
  mocks.overview
    .mockResolvedValueOnce(assignedOverview)
    .mockRejectedValueOnce(new Error('refresh failed'))
    .mockResolvedValue(assignedOverview);
  mocks.save.mockResolvedValue(undefined);
  render(<PermissionsAdmin embedded />);
  fireEvent.change(await screen.findByRole('combobox', { name: 'A 팀' }), {
    target: { value: 'team-b' },
  });
  await waitFor(() =>
    expect(mocks.toastWarning).toHaveBeenCalledWith(
      '변경 완료, 최신 정보 조회 실패',
    ),
  );
  expect(mocks.toastError).not.toHaveBeenCalled();
  expect(screen.queryByRole('combobox', { name: 'A 팀' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
  await screen.findByRole('combobox', { name: 'A 팀' });
  expect(mocks.save).toHaveBeenCalledTimes(1);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it('새 CSV 미리보기가 실패하면 이전 파일의 승인 결과로 적용할 수 없다', async () => {
  mocks.overview.mockResolvedValue(overview);
  mocks.importCsv
    .mockResolvedValueOnce(preview)
    .mockRejectedValueOnce(new Error('preview failed'));
  const view = render(<PermissionsAdmin />);

  await screen.findByText('CSV로 넣기');
  const input = view.container.querySelector('input[type="file"]');
  if (!(input instanceof HTMLInputElement))
    throw new Error('CSV input missing');
  fireEvent.change(input, { target: { files: [csvFile('a.csv', 'a')] } });
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '저장' }).hasAttribute('disabled'),
    ).toBe(false),
  );

  fireEvent.change(input, { target: { files: [csvFile('b.csv', 'b')] } });
  await waitFor(() => expect(mocks.importCsv).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(mocks.toastError).toHaveBeenCalledTimes(1));
  expect(
    screen.getByRole('button', { name: '저장' }).hasAttribute('disabled'),
  ).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  expect(mocks.importCsv).not.toHaveBeenCalledWith('b', true);
});

it('늦게 도착한 이전 CSV 미리보기로 최신 파일의 승인 상태를 덮지 않는다', async () => {
  const first = deferred<ImportReport>();
  const latest = { ...preview, counts: { CHANGED: 1 } };
  mocks.overview.mockResolvedValue(overview);
  mocks.importCsv
    .mockReturnValueOnce(first.promise)
    .mockResolvedValueOnce(latest)
    .mockResolvedValueOnce({ ...latest, applied: true });
  const view = render(<PermissionsAdmin />);
  await screen.findByText('CSV로 넣기');
  const input = view.container.querySelector('input[type="file"]');
  if (!(input instanceof HTMLInputElement))
    throw new Error('CSV input missing');

  fireEvent.change(input, { target: { files: [csvFile('a.csv', 'a')] } });
  await waitFor(() => expect(mocks.importCsv).toHaveBeenCalledTimes(1));
  fireEvent.change(input, { target: { files: [csvFile('b.csv', 'b')] } });
  await screen.findByText(/변경 1 · 미리보기입니다/);

  await act(async () => {
    first.resolve(preview);
    await first.promise;
  });
  expect(screen.queryByText(/새로 배정 1 · 미리보기입니다/)).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  await waitFor(() => expect(mocks.importCsv).toHaveBeenCalledWith('b', true));
});

it('늦은 파일 읽기가 최신 CSV 본문을 바꾸지 않는다', async () => {
  const firstRead = deferred<string>();
  mocks.overview.mockResolvedValue(overview);
  mocks.importCsv.mockResolvedValue(preview);
  const view = render(<PermissionsAdmin />);
  await screen.findByText('CSV로 넣기');
  const input = view.container.querySelector('input[type="file"]');
  if (!(input instanceof HTMLInputElement))
    throw new Error('CSV input missing');

  const firstFile = new File(['a'], 'a.csv', { type: 'text/csv' });
  Object.defineProperty(firstFile, 'text', { value: () => firstRead.promise });
  fireEvent.change(input, { target: { files: [firstFile] } });
  fireEvent.change(input, { target: { files: [csvFile('b.csv', 'b')] } });
  await waitFor(() => expect(mocks.importCsv).toHaveBeenCalledWith('b', false));
  await act(async () => {
    firstRead.resolve('a');
    await firstRead.promise;
  });

  expect(mocks.importCsv).not.toHaveBeenCalledWith('a', false);
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  await waitFor(() => expect(mocks.importCsv).toHaveBeenCalledWith('b', true));
});
