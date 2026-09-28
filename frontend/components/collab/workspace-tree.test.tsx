// @vitest-environment jsdom

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CollabThreadSummary, Workspace } from '@/lib/api/collab';
import { buildWorkspaceTree, WorkspaceTree } from './workspace-tree';

const common: Workspace = {
  id: 'common',
  parentId: null,
  name: 'Common',
  kind: 'COMMON',
  depth: 1,
};
const hr: Workspace = {
  id: 'hr',
  parentId: null,
  name: '인사팀',
  kind: 'ORG',
  depth: 1,
};
const hrLead: Workspace = {
  id: 'hr-lead',
  parentId: 'hr',
  name: '인사팀 관리자방',
  kind: 'WORK',
  depth: 2,
};
/** 부모(전사)를 볼 수 없는 사람에게 온 자식. */
const notice: Workspace = {
  id: 'notice',
  parentId: 'company',
  name: '전사 공지방',
  kind: 'WORK',
  depth: 2,
};

function thread(
  id: string,
  workspaceId: string | null,
  title = id,
): CollabThreadSummary {
  return {
    id,
    title,
    participants: ['demo1'],
    workspaceId,
    workspaceName: null,
  };
}

afterEach(cleanup);

describe('buildWorkspaceTree', () => {
  it('자식은 부모 아래로, 방은 자기 워크스페이스 아래로 넣는다', () => {
    const { roots, unplaced } = buildWorkspaceTree(
      [common, hr, hrLead],
      [thread('a', 'hr'), thread('b', 'hr-lead')],
    );

    expect(roots.map((node) => node.workspace.id)).toEqual(['common', 'hr']);
    expect(roots[1].children.map((node) => node.workspace.id)).toEqual([
      'hr-lead',
    ]);
    expect(roots[1].threads.map((t) => t.id)).toEqual(['a']);
    expect(roots[1].children[0].threads.map((t) => t.id)).toEqual(['b']);
    expect(unplaced).toEqual([]);
  });

  it('볼 수 없는 부모를 가진 워크스페이스는 최상위로 올린다', () => {
    const { roots } = buildWorkspaceTree([common, notice], []);

    expect(roots.map((node) => node.workspace.id)).toEqual([
      'common',
      'notice',
    ]);
  });

  it('워크스페이스가 없거나 목록에 없는 방은 따로 모은다', () => {
    const { unplaced } = buildWorkspaceTree(
      [common],
      [thread('legacy', null), thread('other', 'gone'), thread('c', 'common')],
    );

    expect(unplaced.map((t) => t.id)).toEqual(['legacy', 'other']);
  });
});

describe('WorkspaceTree', () => {
  it('맨 위 고객사(ROOT) 아래로 트리를 펼치고, ROOT에는 "새 방"을 달지 않는다', () => {
    const acme: Workspace = {
      id: 'root',
      parentId: null,
      name: 'ACME',
      kind: 'ROOT',
      depth: 0,
    };
    const { roots } = buildWorkspaceTree(
      [acme, { ...common, parentId: 'root' }, { ...hr, parentId: 'root' }],
      [],
    );
    render(<WorkspaceTree onCreate={vi.fn()} roots={roots} />);

    expect(roots.map((node) => node.workspace.id)).toEqual(['root']);
    expect(screen.getByRole('button', { name: 'ACME' })).toBeTruthy();
    // 새 방은 Common·인사팀 두 폴더에만 있다.
    expect(screen.getAllByRole('button', { name: '새 방' })).toHaveLength(2);
  });

  it('폴더를 접으면 그 아래 방과 하위 폴더가 사라진다', () => {
    const { roots } = buildWorkspaceTree(
      [hr, hrLead],
      [thread('a', 'hr', '채용 공고'), thread('b', 'hr-lead', '평가 검토')],
    );
    render(<WorkspaceTree onCreate={vi.fn()} roots={roots} />);

    expect(screen.getByText('채용 공고')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '인사팀' }));

    expect(screen.queryByText('채용 공고')).toBeNull();
    expect(screen.queryByText('인사팀 관리자방')).toBeNull();
  });

  it('"새 방"은 그 폴더의 워크스페이스로 만든다', async () => {
    const onCreate = vi.fn().mockResolvedValue(undefined);
    const { roots } = buildWorkspaceTree([common, hr], []);
    render(<WorkspaceTree onCreate={onCreate} roots={roots} />);

    fireEvent.click(screen.getAllByRole('button', { name: '새 방' })[1]);
    fireEvent.change(screen.getByLabelText('인사팀에 만들 방 제목'), {
      target: { value: '면접 질문' },
    });
    fireEvent.click(screen.getByRole('button', { name: '만들기' }));

    await waitFor(() =>
      expect(onCreate).toHaveBeenCalledWith(
        '면접 질문',
        'hr',
        expect.any(String),
      ),
    );
  });

  it('만들기에 실패하면 그 자리에 이유를 보여준다', async () => {
    const onCreate = vi
      .fn()
      .mockRejectedValue(
        new Error(
          JSON.stringify({ error: { code: 'FORBIDDEN', message: 'x' } }),
        ),
      );
    const { roots } = buildWorkspaceTree([hr], []);
    render(<WorkspaceTree onCreate={onCreate} roots={roots} />);

    fireEvent.click(screen.getByRole('button', { name: '새 방' }));
    fireEvent.change(screen.getByLabelText('인사팀에 만들 방 제목'), {
      target: { value: '방' },
    });
    fireEvent.click(screen.getByRole('button', { name: '만들기' }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      '이 작업을 수행할 권한이 없어요.',
    );
  });
});
