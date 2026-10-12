// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PresenceAvatars } from './presence-avatars';

afterEach(cleanup);

const people = ['송강호', '김민수', '박지영', '이서연', '최우식'].map(
  (displayName, index) => ({ subject: `user-${index}`, displayName }),
);

describe('헤더 접속자 표시', () => {
  it('세 명까지만 동그라미로 그리고 나머지는 +N으로 줄인다', () => {
    render(<PresenceAvatars participants={people} />);
    const trigger = screen.getByRole('button', { name: '접속중 5명' });
    expect(trigger.textContent).toContain('송김박');
    expect(trigger.textContent).toContain('+2');
  });

  it('누르면 접속자 전체 이름을 subject와 함께 보인다', async () => {
    render(<PresenceAvatars participants={people} />);
    fireEvent.keyDown(screen.getByRole('button', { name: '접속중 5명' }), {
      key: 'Enter',
    });
    const last = await screen.findByText('최우식');
    expect(last.getAttribute('title')).toBe('user-4');
    expect(screen.getByText('송강호')).toBeTruthy();
  });

  it('접속자가 없으면 펼칠 수 없다', () => {
    render(<PresenceAvatars participants={[]} />);
    expect(
      (screen.getByRole('button', { name: '접속중 0명' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });
});
