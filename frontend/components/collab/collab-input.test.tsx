// @vitest-environment jsdom
/********************************************************
 파일명 : collab-input.test.tsx (components/collab)
 설 명 : 협업방 입력창의 안내 줄이 다른 사람의 입력 중 표시와 자리를 나누는 규칙과, 입력할 때 입력 중
 여부를 알리는 것을 본다.
 *********************************************************/

import { TYPING_RESEND_MS } from '@/lib/chat/typing';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CollabInput } from './collab-input';

vi.mock('sonner', () => ({ toast: { error: vi.fn() } }));
// 업로드가 끝나지 않은 채로 둔다 — 올라가는 동안의 알림만 본다.
vi.mock('@/lib/api/attachments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/attachments')>()),
  uploadAttachment: () => new Promise(() => {}),
}));

afterEach(cleanup);

const HINT = '참여자에게만 보입니다.';

function renderInput(props: Partial<Parameters<typeof CollabInput>[0]> = {}) {
  return render(<CollabInput canSend onSend={() => true} {...props} />);
}

describe('CollabInput — 입력 중 표시', () => {
  it('누가 입력 중이면 안내 대신 그 문구를 보인다', () => {
    renderInput({ typingLabel: '전도연 과장 님이 입력 중…' });

    expect(screen.getByText('전도연 과장 님이 입력 중…')).toBeTruthy();
    expect(screen.queryByText(HINT, { exact: false })).toBeNull();
  });

  it('아무도 입력하지 않으면 원래 안내를 보인다', () => {
    renderInput({ typingLabel: null });

    expect(screen.getByText(HINT, { exact: false })).toBeTruthy();
  });

  it('내가 AI를 부르는 중에도 입력 중 표시를 AI 안내 옆에 함께 보인다', () => {
    renderInput({ typingLabel: '전도연 과장 님이 입력 중…' });

    fireEvent.change(screen.getByLabelText('협업방 메시지 입력'), {
      target: { value: '@AI 요약해줘' },
    });

    expect(screen.getByText('AI가 이 메시지에 답합니다.')).toBeTruthy();
    expect(screen.getByText('전도연 과장 님이 입력 중…')).toBeTruthy();
    expect(screen.queryByText(HINT, { exact: false })).toBeNull();
  });

  it('입력창에 글자가 있는지를 입력할 때마다 알린다', () => {
    const onTyping = vi.fn();
    renderInput({ onTyping });
    const textarea = screen.getByLabelText('협업방 메시지 입력');

    fireEvent.change(textarea, { target: { value: '안녕' } });
    fireEvent.change(textarea, { target: { value: '  ' } });

    expect(onTyping.mock.calls).toEqual([[true], [false]]);
  });

  it('첨부가 올라가는 동안은 키를 누르지 않아도 입력 중으로 다시 알린다', () => {
    vi.useFakeTimers();
    try {
      const onTyping = vi.fn();
      const { container } = renderInput({ onTyping });
      const input = container.querySelector(
        'input[type="file"]',
      ) as HTMLInputElement;

      fireEvent.change(input, {
        target: { files: [new File(['x'], '근로기준법.pdf')] },
      });
      expect(onTyping.mock.calls).toEqual([[true]]);

      vi.advanceTimersByTime(TYPING_RESEND_MS);
      expect(onTyping.mock.calls).toEqual([[true], [true]]);

      // 올라가는 중에는 글자를 지워도 멈췄다고 알리지 않는다.
      const textarea = screen.getByLabelText('협업방 메시지 입력');
      fireEvent.change(textarea, { target: { value: '' } });
      expect(onTyping).toHaveBeenLastCalledWith(true);
    } finally {
      vi.useRealTimers();
    }
  });
});
