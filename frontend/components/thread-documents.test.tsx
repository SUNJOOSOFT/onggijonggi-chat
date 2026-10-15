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
import {
  ThreadDocumentsButton,
  ThreadDocumentsProvider,
  ThreadDocumentsPanel,
} from './thread-documents';
import * as api from '@/lib/api/thread-documents';

vi.mock('@/lib/api/thread-documents', () => ({
  listThreadDocuments: vi.fn(),
  uploadThreadDocument: vi.fn(),
  changeThreadDocument: vi.fn(),
  downloadThreadDocument: vi.fn(),
  downloadThreadAttachment: vi.fn(),
  registerThreadAttachment: vi.fn(),
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
  canReprocess: false,
  createdAt: '2026-10-02T00:00:00Z',
};
const listing: api.ThreadDocumentsListing = {
  threadStatus: 'ACTIVE',
  canUpload: true,
  documents: [doc],
  attachments: [],
};
const attachment: api.ThreadAttachment = {
  id: 'file1',
  fileName: '근로기준법.pdf',
  size: 2048,
  senderSubject: 'sub-kim',
  own: false,
  canReadOriginal: true,
  canRegister: true,
  registered: false,
  createdAt: '2026-10-14T00:00:00Z',
};
/** 좁은 화면 구성: 헤더 버튼으로 여는 시트. */
function room(threadId: string, available = true) {
  return (
    <ThreadDocumentsProvider threadId={threadId} available={available}>
      <ThreadDocumentsButton />
    </ThreadDocumentsProvider>
  );
}
function denied() {
  return Object.assign(new Error('접근 불가'), { status: 404 });
}
function open() {
  fireEvent.click(screen.getByRole('button', { name: /방 문서/ }));
}
function close() {
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));
}
/** 문서 행의 ⋯ 메뉴를 키보드로 연다(jsdom에는 Radix 메뉴가 쓰는 포인터 이벤트가 없다). */
async function menu(item: string) {
  fireEvent.keyDown(screen.getByRole('button', { name: '문서 작업' }), {
    key: 'Enter',
  });
  return screen.findByRole('menuitem', { name: item });
}
async function choose(item: string) {
  fireEvent.click(await menu(item));
}
function select(file: File) {
  fireEvent.change(screen.getByLabelText('등록할 방 문서'), {
    target: { files: [file] },
  });
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.listThreadDocuments).mockResolvedValue(listing);
  vi.mocked(api.changeThreadDocument).mockResolvedValue();
});
afterEach(cleanup);

describe('패널 접기와 진행 중 업로드', () => {
  it('닫힌 동안 성공한 업로드는 다시 열어도 이전 파일을 재전송하지 않는다', async () => {
    let resolveUpload!: (value: api.ThreadDocument) => void;
    vi.mocked(api.uploadThreadDocument).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveUpload = resolve;
        }),
    );
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    select(new File(['text'], 'new.txt'));
    close();
    await act(async () => resolveUpload(doc));
    open();
    await screen.findByText('guide.txt');
    expect(screen.queryByText('new.txt')).toBeNull();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(api.uploadThreadDocument).toHaveBeenCalledTimes(1);
  });

  it('닫았다 다시 연 동안 실패하면 파일과 재시도 UUID를 유지하고 오류를 표시한다', async () => {
    let rejectUpload!: (error: Error) => void;
    vi.mocked(api.uploadThreadDocument).mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectUpload = reject;
        }),
    );
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    select(new File(['text'], 'new.txt'));
    const id = vi.mocked(api.uploadThreadDocument).mock.calls[0][1];
    close();
    open();
    await screen.findByText('guide.txt');
    await act(async () => rejectUpload(new Error('업로드 실패')));
    expect(screen.getByRole('alert').textContent).toContain('업로드 실패');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
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
      render(room('room'));
      open();
      await screen.findByText('guide.txt');
      const label =
        action === 'pin'
          ? '고정'
          : action === 'unpin'
            ? '고정 해제'
            : '삭제 확인';
      if (action === 'delete') await choose('삭제');
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
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(3),
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
    render(room('room'));
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
    const { rerender } = render(room('old'));
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await screen.findByText('응답 유실');
    rerender(room('new'));
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
      render(room('room'));
      open();
      await screen.findByText('guide.txt');
      await choose('삭제');
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
    render(room('room'));
    open();
    await screen.findByText(new RegExp(label));
  });
  it('draft는 서버 조회·업로드 없이 기존 첨부 안내만 표시한다', () => {
    render(room('draft', false));
    open();
    expect(screen.getByText(/첫 메시지를 보내/)).toBeTruthy();
    expect(api.listThreadDocuments).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('등록할 방 문서')).toBeNull();
  });
  it('열린 실제 방에서 처리 상태를 표시하고 등록은 고정과 분리한다', async () => {
    vi.mocked(api.uploadThreadDocument).mockResolvedValue(doc);
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    const file = new File(['text'], 'new.txt', { type: 'text/plain' });
    select(file);
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
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    select(new File(['text'], 'new.txt'));
    await screen.findByText('저장 실패');
    expect(screen.getByText('new.txt')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() =>
      expect(api.uploadThreadDocument).toHaveBeenCalledTimes(2),
    );
    expect(vi.mocked(api.uploadThreadDocument).mock.calls[0][1]).toEqual(
      vi.mocked(api.uploadThreadDocument).mock.calls[1][1],
    );
  });
  it('삭제는 확인 후 실행하고 성공 뒤 조회 실패를 저장 실패로 처리하지 않는다', async () => {
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    await choose('삭제');
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
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    expect(screen.getByText('이 방은 읽기 전용입니다.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '고정' })).toBeNull();
    expect(screen.queryByLabelText('등록할 방 문서')).toBeNull();
    expect(await menu('원본 내려받기')).toBeTruthy();
    expect(screen.queryByRole('menuitem', { name: '삭제' })).toBeNull();
  });
  it('늦은 이전 방 조회가 현재 방 목록을 덮지 않는다', async () => {
    let resolveOld!: (value: api.ThreadDocumentsListing) => void;
    vi.mocked(api.listThreadDocuments).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    const { rerender } = render(room('old'));
    open();
    rerender(room('new'));
    await screen.findByText('guide.txt');
    await act(async () =>
      resolveOld({ ...listing, documents: [{ ...doc, fileName: 'old.txt' }] }),
    );
    expect(screen.queryByText('old.txt')).toBeNull();
  });
  it('현재 권한을 서버가 회수하면 기존 목록과 작업 버튼을 제거한다', async () => {
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    vi.mocked(api.listThreadDocuments).mockRejectedValue(denied());
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
    await screen.findByText('접근 불가');
    expect(screen.queryByText('guide.txt')).toBeNull();
  });
  it('일시적인 조회 실패는 마지막 목록과 확인창을 유지한다', async () => {
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    await choose('삭제');
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
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await screen.findByText('상태가 바뀜');
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(3),
    );
    expect(screen.getByText('상태가 바뀜')).toBeTruthy();
  });
  it('실패 뒤 최신 목록을 받으면 다음 변경은 새 요청 UUID를 쓴다', async () => {
    vi.mocked(api.changeThreadDocument).mockRejectedValueOnce(
      new Error('응답 유실'),
    );
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await screen.findByText('응답 유실');
    fireEvent.click(screen.getByRole('button', { name: '목록 다시 조회' }));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(3),
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
      render(room('room'));
      open();
      await screen.findByText('guide.txt');
      visibility.mockReturnValue('hidden');
      // 닫힌 동안 개수용 조회 1회 + 열 때 1회
      await act(async () => vi.advanceTimersByTime(30_000));
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(2);
      visibility.mockReturnValue('visible');
      await act(async () =>
        document.dispatchEvent(new Event('visibilitychange')),
      );
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(3);
    } finally {
      visibility.mockRestore();
      vi.useRealTimers();
    }
  });
  it('처리 실패 문서만 다시 처리하고 응답 유실 뒤 재시도는 같은 요청 UUID를 쓴다', async () => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      documents: [{ ...doc, status: 'FAILED', canReprocess: true }],
    });
    vi.mocked(api.changeThreadDocument)
      .mockRejectedValueOnce(new Error('응답 유실'))
      .mockResolvedValue();
    render(room('room'));
    open();
    await screen.findByText(/처리 실패/);
    fireEvent.click(screen.getByRole('button', { name: '다시 처리' }));
    await screen.findByText('응답 유실');
    fireEvent.click(screen.getByRole('button', { name: '다시 처리' }));
    await waitFor(() =>
      expect(api.changeThreadDocument).toHaveBeenCalledTimes(2),
    );
    const calls = vi.mocked(api.changeThreadDocument).mock.calls;
    expect(calls[0][2]).toBe('reprocess');
    expect(calls[1][3]).toBe(calls[0][3]);
  });
  it.each([
    [true, '답변에 쓰이지 않습니다. 다시 처리해 주세요.'],
    [
      false,
      '답변에 쓰이지 않습니다. 올린 사람이나 방장이 다시 처리할 수 있어요.',
    ],
  ])(
    '고정된 처리 실패 문서는 답변에서 빠진다고 알린다(다시 처리 가능 %s)',
    async (canReprocess, notice) => {
      vi.mocked(api.listThreadDocuments).mockResolvedValue({
        ...listing,
        documents: [{ ...doc, status: 'FAILED', pinned: true, canReprocess }],
      });
      render(room('room'));
      open();
      expect(await screen.findByText(notice)).toBeTruthy();
    },
  );

  it('고정하지 않은 처리 실패 문서에는 답변 안내를 붙이지 않는다', async () => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      documents: [{ ...doc, status: 'FAILED', pinned: false }],
    });
    render(room('room'));
    open();
    await screen.findByText(/처리 실패/);
    expect(screen.queryByText(/답변에 쓰이지 않습니다/)).toBeNull();
  });

  it('처리 대기 문서에는 다시 처리 버튼이 없다', async () => {
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    expect(screen.queryByRole('button', { name: '다시 처리' })).toBeNull();
  });
  it('작업 버튼은 어느 문서의 것인지 설명으로 알린다', async () => {
    render(room('room'));
    open();
    await screen.findByText('guide.txt');
    for (const name of ['고정', '문서 작업'])
      expect(
        screen.getByRole('button', { name, description: 'guide.txt' }),
      ).toBeTruthy();
  });
  it('중복 제출을 같은 이벤트 회차에서도 차단한다', async () => {
    vi.mocked(api.changeThreadDocument).mockImplementation(
      () => new Promise(() => {}),
    );
    render(room('room'));
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
      .mockResolvedValueOnce(listing)
      .mockImplementationOnce(
        () =>
          new Promise((_, reject) => {
            rejectOld = reject;
          }),
      )
      .mockResolvedValue(listing);
    const { rerender } = render(room('old'));
    open();
    await screen.findByText('guide.txt');
    fireEvent.click(screen.getByRole('button', { name: '고정' }));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(3),
    );
    rerender(room('new'));
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
      render(room('room'));
      open();
      await screen.findByText('guide.txt');
      const item = await menu('원본 내려받기');
      expect(item.getAttribute('aria-disabled') === 'true').toBe(
        !canReadOriginal,
      );
    },
  );
});

describe('채팅 첨부', () => {
  it('다른 사람이 보낸 첨부에는 화면이 아는 보낸 사람 이름을 붙인다', async () => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      attachments: [
        attachment,
        { ...attachment, id: 'file2', senderSubject: 'sub-unknown' },
      ],
    });
    render(
      <ThreadDocumentsProvider
        threadId="room"
        senderNames={new Map([['sub-kim', '김부장']])}
      >
        <ThreadDocumentsButton />
      </ThreadDocumentsProvider>,
    );
    open();
    expect(await screen.findByText('2KB · 김부장')).toBeTruthy();
    // 이름을 모르면 지어내지 않고 비워 둔다.
    expect(screen.getAllByText('2KB')).toHaveLength(1);
  });

  async function attachmentMenu(item: string) {
    fireEvent.keyDown(screen.getByRole('button', { name: '첨부 작업' }), {
      key: 'Enter',
    });
    return screen.findByRole('menuitem', { name: item });
  }

  it('보낸 첨부를 방 문서와 따로 보이고 원본을 내려받는다', async () => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      attachments: [attachment],
    });
    vi.mocked(api.downloadThreadAttachment).mockResolvedValue(new Blob(['x']));
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    render(room('room'));
    open();
    expect(await screen.findByText('근로기준법.pdf')).toBeTruthy();
    expect(screen.getByText('채팅 첨부 1')).toBeTruthy();
    expect(screen.getByText('2KB')).toBeTruthy();
    fireEvent.click(await attachmentMenu('원본 내려받기'));
    await waitFor(() =>
      expect(api.downloadThreadAttachment).toHaveBeenCalledWith(
        'room',
        'file1',
      ),
    );
    expect(api.downloadThreadDocument).not.toHaveBeenCalled();
  });

  it('방 문서로 등록하면 목록을 다시 읽는다', async () => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      attachments: [attachment],
    });
    vi.mocked(api.registerThreadAttachment).mockResolvedValue(doc);
    render(room('room'));
    open();
    await screen.findByText('근로기준법.pdf');
    const before = vi.mocked(api.listThreadDocuments).mock.calls.length;
    fireEvent.click(await attachmentMenu('방 문서로 등록'));
    await waitFor(() =>
      expect(api.registerThreadAttachment).toHaveBeenCalledWith(
        'room',
        'file1',
      ),
    );
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(before + 1),
    );
  });

  it('등록된 첨부와 원본 없는 첨부는 상태만 보이고 할 일을 숨긴다', async () => {
    vi.mocked(api.listThreadDocuments).mockResolvedValue({
      ...listing,
      attachments: [
        { ...attachment, registered: true, canRegister: false },
        {
          ...attachment,
          id: 'old',
          fileName: '예전.txt',
          size: null,
          own: true,
          canReadOriginal: false,
          canRegister: false,
        },
      ],
    });
    render(room('room'));
    open();
    expect(await screen.findByText(/방 문서로 등록됨/)).toBeTruthy();
    expect(screen.getByText('내가 보냄 · 원본 없음')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: '첨부 작업' })).toHaveLength(
      1,
    );
    expect(
      (await attachmentMenu('원본 내려받기')).getAttribute('aria-disabled'),
    ).not.toBe('true');
    expect(
      screen.queryByRole('menuitem', { name: '방 문서로 등록' }),
    ).toBeNull();
  });

  it('첨부가 실린 메시지가 오면 폴링을 기다리지 않고 다시 읽는다', async () => {
    const view = (count: number) => (
      <ThreadDocumentsProvider threadId="room" attachmentCount={count}>
        <ThreadDocumentsButton />
      </ThreadDocumentsProvider>
    );
    const { rerender } = render(view(0));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(1),
    );
    rerender(view(0));
    expect(api.listThreadDocuments).toHaveBeenCalledTimes(1);
    rerender(view(1));
    await waitFor(() =>
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(2),
    );
  });
});

describe('넓은 화면 오른쪽 패널', () => {
  beforeEach(() => {
    // jsdom에는 matchMedia가 없다. 넓은 화면(md 이상)으로 둔다.
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockImplementation((query: string) => ({
        matches: true,
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it('넓은 화면에서는 시트를 열지 않아도 목록을 보이고 폴링한다', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      render(
        <ThreadDocumentsProvider threadId="room">
          <ThreadDocumentsPanel />
        </ThreadDocumentsProvider>,
      );
      expect(await screen.findByText('guide.txt')).toBeTruthy();
      const before = vi.mocked(api.listThreadDocuments).mock.calls.length;
      await act(async () => vi.advanceTimersByTime(10_000));
      expect(api.listThreadDocuments).toHaveBeenCalledTimes(before + 1);
    } finally {
      vi.useRealTimers();
    }
  });
});
