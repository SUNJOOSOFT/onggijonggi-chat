// @vitest-environment jsdom
/********************************************************
 파일명 : collab-input.test.tsx (components/collab)
 설 명 : 협업방 입력창의 안내 줄이 다른 사람의 입력 중 표시와 자리를 나누는 규칙과, 입력할 때 입력 중
 여부를 알리는 것을 본다.
 *********************************************************/

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CollabInput } from './collab-input';

vi.mock('sonner', () => ({ toast: { error: vi.fn() } }));

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
});
