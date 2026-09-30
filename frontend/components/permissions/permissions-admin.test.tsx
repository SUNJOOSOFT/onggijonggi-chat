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
import type { ImportReport, PermissionsOverview } from '@/lib/api/permissions';
import { PermissionsAdmin } from './permissions-admin';

const mocks = vi.hoisted(() => ({
  overview: vi.fn(),
  importCsv: vi.fn(),
  toastError: vi.fn(),
}));

vi.mock('@/lib/api/permissions', () => ({
  fetchPermissionsOverview: mocks.overview,
  importMembersCsv: mocks.importCsv,
  saveAssignment: vi.fn(),
}));

vi.mock('@/components/sidebar-toggle', () => ({ SidebarToggle: () => null }));
vi.mock('sonner', () => ({
  toast: { error: mocks.toastError, success: vi.fn() },
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
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

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
