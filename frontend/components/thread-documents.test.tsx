// @vitest-environment jsdom
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  act,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThreadDocuments } from './thread-documents';
import * as api from '@/lib/api/thread-documents';

vi.mock('@/lib/api/thread-documents', () => ({
  listThreadDocuments: vi.fn(),
  uploadThreadDocument: vi.fn(),
  changeThreadDocument: vi.fn(),
  downloadThreadDocument: vi.fn(),
}));
const doc: api.ThreadDocument = {
  id: 'doc1',
  fileName: 'guide.txt',
  size: 100,
  status: 'PENDING',
  pinned: false,
  own: true,
  canPin: true,
  canUnpin: true,
  canDelete: true,
  canReadOriginal: true,
  createdAt: '2026-10-02T00:00:00Z',
};
const listing: api.ThreadDocumentsListing = {
  threadStatus: 'ACTIVE',
  canUpload: true,
  documents: [doc],
};
function denied() {
  return Object.assign(new Error('접근 불가'), { status: 404 });
}
function open() {
  fireEvent.click(screen.getByRole('button', { name: /방 문서/ }));
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.listThreadDocuments).mockResolvedValue(listing);
  vi.mocked(api.changeThreadDocument).mockResolvedValue();
});
afterEach(cleanup);

describe('패널 접기와 진행 중 업로드', () => {
  it('접힌 동안 성공한 업로드는 다시 펼쳐도 이전 파일을 재전송하지 않는다', async () => {
    let resolveUpload!: (value: api.ThreadDocument) => void;
    vi.mocked(api.uploadThreadDocument).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveUpload = resolve;
        }),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.change(screen.getByLabelText('등록할 방 문서'), {
      target: { files: [new File(['text'], 'new.txt')] },
    });
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    open();
    await act(async () => resolveUpload(doc));
    open();
    await screen.findByText('guide.txt');
    const submit = screen.getByRole('button', {
      name: '문서 등록',
    }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    fireEvent.click(submit);
    expect(api.uploadThreadDocument).toHaveBeenCalledTimes(1);
  });

  it('접었다 다시 펼친 동안 실패하면 입력과 재시도 UUID를 유지하고 오류를 표시한다', async () => {
    let rejectUpload!: (error: Error) => void;
    vi.mocked(api.uploadThreadDocument).mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectUpload = reject;
        }),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.change(screen.getByLabelText('등록할 방 문서'), {
      target: { files: [new File(['text'], 'new.txt')] },
    });
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    const id = vi.mocked(api.uploadThreadDocument).mock.calls[0][1];
    open();
    open();
    await screen.findByText('guide.txt');
    await act(async () => rejectUpload(new Error('업로드 실패')));
    expect(screen.getByRole('alert').textContent).toContain('업로드 실패');
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    await waitFor(() =>
      expect(api.uploadThreadDocument).toHaveBeenCalledTimes(2),
    );
    expect(vi.mocked(api.uploadThreadDocument).mock.calls[1][1]).toBe(id);
  });
});

describe('방 문서 실제 UI', () => {
  it.each(['pin', 'unpin', 'delete'] as const)(
    '%s 응답 유실 뒤 같은 의도의 재시도는 요청 UUID를 재사용한다',
    async (action) => {
      vi.mocked(api.listThreadDocuments).mockResolvedValue({
        ...listing,
        documents: [{ ...doc, pinned: action === 'unpin' }],
      });
      vi.mocked(api.changeThreadDocument)
        .mockRejectedValueOnce(new Error('응답 유실'))
        .mockResolvedValue();
      render(<ThreadDocuments threadId="room" />);
      open();
      await screen.findByText('guide.txt');
      const label =
        action === 'pin'
          ? '고정'
          : action === 'unpin'
            ? '고정 해제'
            : '삭제 확인';
      if (action === 'delete')
        fireEvent.click(screen.getByRole('button', { name: '삭제' }));
      fireEvent.click(screen.getByRole('button', { name: label }));
      await screen.findByText('응답 유실');
      fireEvent.click(screen.getByRole('button', { name: label }));
      await waitFor(() =>
        expect(api.changeThreadDocument).toHaveBeenCalledTimes(2),
      );
      const calls = vi.mocked(api.changeThreadDocument).mock.calls;
      expect(calls[1][3]).toBe(calls[0][3]);
    },
  );
  it('성공이 확인된 뒤 새 변경은 새 요청 UUID를 사용한다', async () => {
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(2),
    );
    await waitFor(() =>
      expect(
        (
          screen.getByRole('button', {
            name: '고정',
          }) as HTMLButtonElement
        ).disabled,
      ).toBe(false),
    );
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await waitFor(() =>
      expect(api.changeThreadDocument).toHaveBeenCalledTimes(2),
    );
    const calls = vi.mocked(api.changeThreadDocument).mock.calls;
    expect(calls[1][3]).not.toBe(calls[0][3]);
  });
  it('실패 뒤 반대 변경을 선택하면 이전 요청 UUID를 재사용하지 않는다', async () => {
    vi.mocked(api.changeThreadDocument).mockRejectedValueOnce(
      new Error('응답 유실'),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await screen.findByText('응답 유실');
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      documents: [{ ...doc, pinned: true }],
    });
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
    fireEvent.click(await screen.findByRole('button', { name: '고정 해제' }));
    await waitFor(() =>
      expect(api.changeThreadDocument).toHaveBeenCalledTimes(2),
    );
    const calls = vi.mocked(api.changeThreadDocument).mock.calls;
    expect(calls[1][2]).toBe('unpin');
    expect(calls[1][3]).not.toBe(calls[0][3]);
  });
  it('실패 뒤 방을 바꾸면 같은 문서의 변경에도 새 요청 UUID를 사용한다', async () => {
    vi.mocked(api.changeThreadDocument).mockRejectedValueOnce(
      new Error('응답 유실'),
    );
    const { rerender } = render(<ThreadDocuments threadId="old" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await screen.findByText('응답 유실');
    rerender(<ThreadDocuments threadId="new" />);
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await waitFor(() =>
      expect(api.changeThreadDocument).toHaveBeenCalledTimes(2),
    );
    const calls = vi.mocked(api.changeThreadDocument).mock.calls;
    expect(calls[1][0]).toBe('new');
    expect(calls[1][3]).not.toBe(calls[0][3]);
  });
  it.each(['locked', 'missing', 'denied'] as const)(
    '삭제 확인 중 최신 조회가 %s이면 확인창을 닫는다',
    async (state) => {
      render(<ThreadDocuments threadId="room" />);
      open();
      await screen.findByText('guide.txt');
      fireEvent.click(screen.getByRole('button', { name: '삭제' }));
      expect(screen.getByRole('alertdialog')).toBeTruthy();
      if (state === 'denied')
        vi.mocked(api.listThreadDocuments).mockRejectedValue(denied());
      else
        vi.mocked(api.listThreadDocuments).mockResolvedValue({
          ...listing,
          documents: state === 'missing' ? [] : [{ ...doc, canDelete: false }],
        });
      // 확인창이 뒤 화면을 가리므로 다음 폴링 대신 숨은 버튼을 직접 누른다.
      fireEvent.click(
        screen.getByRole('button', { name: '목록 다시 조회', hidden: true }),
      );
      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
      expect(api.changeThreadDocument).not.toHaveBeenCalled();
    },
  );
  it.each([
    ['PENDING', '처리 대기'],
    ['PROCESSING', '처리 중'],
    ['READY', '검색 준비 완료'],
    ['FAILED', '처리 실패'],
  ] as const)('처리 상태 %s를 %s로 구분해 표시한다', async (status, label) => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      documents: [{ ...doc, status }],
    });
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText(new RegExp(label));
  });
  it('draft는 서버 조회·업로드 없이 기존 첨부 안내만 표시한다', () => {
    render(<ThreadDocuments threadId="draft" available={false} />);
    open();
    expect(screen.getByText(/첫 메시지를 보내/)).toBeTruthy();
    expect(api.listThreadDocuments).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('등록할 방 문서')).toBeNull();
  });
  it('열린 실제 방에서 처리 상태를 표시하고 등록은 고정과 분리한다', async () => {
    vi.mocked(api.uploadThreadDocument).mockResolvedValue(doc);
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    const file = new File(['text'], 'new.txt', { type: 'text/plain' });
    fireEvent.change(screen.getByLabelText('등록할 방 문서'), {
      target: { files: [file] },
    });
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    await waitFor(() =>
      expect(api.uploadThreadDocument).toHaveBeenCalledWith(
        'room',
        expect.any(String),
        file,
      ),
    );
    expect(api.changeThreadDocument).not.toHaveBeenCalled();
    expect(screen.getByText(/처리 대기/)).toBeTruthy();
  });
  it('등록 실패 뒤 파일과 동일 등록 UUID를 유지한다', async () => {
    vi.mocked(api.uploadThreadDocument).mockRejectedValue(
      new Error('저장 실패'),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    const file = new File(['text'], 'new.txt');
    fireEvent.change(screen.getByLabelText('등록할 방 문서'), {
      target: { files: [file] },
    });
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    await screen.findByText('저장 실패');
    fireEvent.click(screen.getByRole('button', { name: '문서 등록' }));
    await waitFor(() =>
      expect(api.uploadThreadDocument).toHaveBeenCalledTimes(2),
    );
    expect(vi.mocked(api.uploadThreadDocument).mock.calls[0][1]).toEqual(
      vi.mocked(api.uploadThreadDocument).mock.calls[1][1],
    );
  });
  it('삭제는 확인 후 실행하고 성공 뒤 조회 실패를 저장 실패로 처리하지 않는다', async () => {
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '삭제' }));
    expect(api.changeThreadDocument).not.toHaveBeenCalled();
    vi.mocked(api.listThreadDocuments).mockRejectedValue(
      new Error('조회 실패'),
    );
    fireEvent.click(screen.getByRole('button', { name: '삭제 확인' }));
    await screen.findByText(/변경은 저장됐지만/);
    expect(api.changeThreadDocument).toHaveBeenCalledTimes(1);
    expect(api.changeThreadDocument).toHaveBeenCalledWith(
      'room',
      'doc1',
      'delete',
      expect.any(String),
    );
  });
  it('읽기 전용 방에서는 원본만 제공하고 등록·고정·삭제를 숨긴다', async () => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      canUpload: false,
      documents: [{ ...doc, canPin: false, canUnpin: false, canDelete: false }],
    });
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    expect(screen.getByText('이 방은 읽기 전용입니다.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '고정' })).toBeNull();
    expect(screen.queryByRole('button', { name: '삭제' })).toBeNull();
    expect(screen.queryByLabelText('등록할 방 문서')).toBeNull();
    expect(screen.getByRole('button', { name: '원본' })).toBeTruthy();
  });
  it('늦은 이전 방 조회가 현재 방 목록을 덮지 않는다', async () => {
    let resolveOld!: (value: api.ThreadDocumentsListing) => void;
    vi.mocked(api.listThreadDocuments).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    const { rerender } = render(<ThreadDocuments threadId="old" />);
    open();
    rerender(<ThreadDocuments threadId="new" />);
    await screen.findByText('guide.txt');
    await act(async () =>
      resolveOld({ ...listing, documents: [{ ...doc, fileName: 'old.txt' }] }),
    );
    expect(screen.queryByText('old.txt')).toBeNull();
  });
  it('현재 권한을 서버가 회수하면 기존 목록과 작업 버튼을 제거한다', async () => {
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    vi.mocked(api.listThreadDocuments).mockRejectedValue(denied());
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
    await screen.findByText('접근 불가');
    expect(screen.queryByText('guide.txt')).toBeNull();
  });
  it('일시적인 조회 실패는 마지막 목록과 확인창을 유지한다', async () => {
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '삭제' }));
    vi.mocked(api.listThreadDocuments).mockRejectedValue(
      Object.assign(new Error('저장소 장애'), { status: 503 }),
    );
    fireEvent.click(
      screen.getByRole('button', { name: '목록 다시 조회', hidden: true }),
    );
    await screen.findByText('저장소 장애');
    expect(screen.getByText('guide.txt')).toBeTruthy();
    expect(screen.getByRole('alertdialog')).toBeTruthy();
  });
  it('조회 성공은 변경 실패 문구를 지우지 않는다', async () => {
    vi.mocked(api.changeThreadDocument).mockRejectedValueOnce(
      new Error('상태가 바뀜'),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await screen.findByText('상태가 바뀜');
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(2),
    );
    expect(screen.getByText('상태가 바뀜')).toBeTruthy();
  });
  it('실패 뒤 최신 목록을 받으면 다음 변경은 새 요청 UUID를 쓴다', async () => {
    vi.mocked(api.changeThreadDocument).mockRejectedValueOnce(
      new Error('응답 유실'),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await screen.findByText('응답 유실');
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(2),
    );
    fireEvent.click(await screen.findByRole('button', { name: '고정' }));
    await waitFor(() =>
      expect(api.changeThreadDocument).toHaveBeenCalledTimes(2),
    );
    const calls = vi.mocked(api.changeThreadDocument).mock.calls;
    expect(calls[1][3]).not.toBe(calls[0][3]);
  });
  it('탭이 가려진 동안은 폴링하지 않고 돌아오면 바로 갱신한다', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const visibility = vi.spyOn(document, 'visibilityState', 'get');
    try {
      render(<ThreadDocuments threadId="room" />);
      open();
      await screen.findByText('guide.txt');
      visibility.mockReturnValue('hidden');
      await act(async () => vi.advanceTimersByTime(30_000));
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(1);
      visibility.mockReturnValue('visible');
      await act(async () =>
        document.dispatchEvent(new Event('visibilitychange')),
      );
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(2);
    } finally {
      visibility.mockRestore();
      vi.useRealTimers();
    }
  });
  it('작업 버튼은 어느 문서의 것인지 설명으로 알린다', async () => {
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    for (const name of ['원본', '고정', '삭제'])
      expect(
        screen.getByRole('button', { name, description: 'guide.txt' }),
      ).toBeTruthy();
  });
  it('중복 제출을 같은 이벤트 회차에서도 차단한다', async () => {
    vi.mocked(api.changeThreadDocument).mockImplementation(
      () => new Promise(() => {}),
    );
    render(<ThreadDocuments threadId="room" />);
    open();
    await screen.findByText('guide.txt');
    const pin = screen.getByRole('button', { name: '고정' });
    fireEvent.click(pin);
    fireEvent.click(pin);
    expect(api.changeThreadDocument).toHaveBeenCalledTimes(1);
  });

  it('이전 방 변경 후 조회 실패를 새 방 오류로 표시하지 않는다', async () => {
    let rejectOld!: (error: Error) => void;
    vi.mocked(api.listThreadDocuments)
      .mockResolvedValueOnce(listing)
      .mockImplementationOnce(
        () =>
          new Promise((_, reject) => {
            rejectOld = reject;
          }),
      )
      .mockResolvedValue(listing);
    const { rerender } = render(<ThreadDocuments threadId="old" />);
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(2),
    );
    rerender(<ThreadDocuments threadId="new" />);
    await screen.findByText('guide.txt');
    await act(async () => rejectOld(new Error('이전 방 조회 중단')));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(
      (screen.getByRole('button', { name: '고정' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
  });

  it.each([false, true])(
    'FAILED 원본 열람 capability %s를 적용한다',
    async (canReadOriginal) => {
      vi.mocked(api.listThreadDocuments).mockResolvedValue({
        ...listing,
        documents: [{ ...doc, status: 'FAILED', canReadOriginal }],
      });
      render(<ThreadDocuments threadId="room" />);
      open();
      await screen.findByText('guide.txt');
      expect(
        (screen.getByRole('button', { name: '원본' }) as HTMLButtonElement)
          .disabled,
      ).toBe(!canReadOriginal);
    },
  );
});
