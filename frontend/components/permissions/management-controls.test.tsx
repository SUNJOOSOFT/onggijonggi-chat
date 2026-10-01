// @vitest-environment jsdom
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ChangeButton } from './management-controls';
const notifications = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('sonner', () => ({ toast: notifications }));
afterEach(cleanup);
it('실제 확인 후 저장 성공·조회 실패를 구분하고 변경을 재전송하지 않는다', async () => {
  const change = vi.fn().mockResolvedValue(undefined);
  const refresh = vi.fn().mockRejectedValue(new Error('offline'));
  render(
    <ChangeButton
      label="권한 저장"
      description="권한 변경"
      change={change}
      refresh={refresh}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '권한 저장' }));
  fireEvent.click(await screen.findByRole('button', { name: '확인하고 저장' }));
  await screen.findByText(/변경 완료, 최신 정보 조회 실패/);
  expect(change).toHaveBeenCalledTimes(1);
  expect(refresh).toHaveBeenCalledTimes(1);
});
it('저장 실패는 재조회하지 않고 입력 확인창을 유지한다', async () => {
  const change = vi.fn().mockRejectedValue(new Error('거부'));
  const refresh = vi.fn();
  render(
    <ChangeButton
      label="권한 저장"
      description="권한 변경"
      change={change}
      refresh={refresh}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '권한 저장' }));
  fireEvent.click(await screen.findByRole('button', { name: '확인하고 저장' }));
  await waitFor(() =>
    expect(screen.getByRole('status').textContent).toBe('거부'),
  );
  expect(refresh).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '확인하고 저장' })).toBeTruthy();
});
it('저장 중 다시 확인해도 변경 요청은 하나만 실행한다', async () => {
  let resolve!: () => void;
  const change = vi.fn(
    () =>
      new Promise<void>((next) => {
        resolve = next;
      }),
  );
  const refresh = vi.fn().mockResolvedValue(undefined);
  render(
    <ChangeButton
      label="권한 저장"
      description="권한 변경"
      change={change}
      refresh={refresh}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '권한 저장' }));
  const confirm = await screen.findByRole('button', { name: '확인하고 저장' });
  fireEvent.click(confirm);
  fireEvent.click(confirm);
  expect(change).toHaveBeenCalledTimes(1);
  resolve();
  await screen.findByText('변경 완료');
});

it('저장한 행이 재조회로 사라져도 성공과 조회 실패를 별도로 알린다', async () => {
  const change = vi.fn().mockResolvedValue(undefined);
  const refresh = vi.fn(async () => {
    unmountRow();
    throw new Error('offline');
  });
  const view = render(
    <ChangeButton
      label="행 삭제"
      description="삭제"
      change={change}
      refresh={refresh}
    />,
  );
  const unmountRow = view.unmount;
  fireEvent.click(screen.getByRole('button', { name: '행 삭제' }));
  fireEvent.click(await screen.findByRole('button', { name: '확인하고 저장' }));
  await waitFor(() =>
    expect(notifications.success).toHaveBeenCalledWith('행 삭제: 변경 완료'),
  );
  expect(notifications.warning).toHaveBeenCalledWith(
    expect.stringContaining('최신 정보 조회 실패'),
  );
  expect(change).toHaveBeenCalledTimes(1);
});
