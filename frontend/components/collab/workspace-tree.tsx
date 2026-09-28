'use client';

/********************************************************
 파일명 : workspace-tree.tsx (components/collab)
 설 명 : 협업 채널을 워크스페이스 트리로 보여준다. 폴더(워크스페이스) 아래에 그 방들이 바로 붙고, 폴더마다
 맨 아래 "새 방"으로 그 워크스페이스에 방을 만든다. 폴더는 접고 펼 수 있다(처음엔 모두 펼침).

 트리는 서버가 준 "볼 수 있는 워크스페이스"만으로 짠다. 부모를 볼 수 없는 워크스페이스(예: 전사는 못 보고
 전사 공지방만 보는 사람)는 최상위로 올린다 — 볼 수 없는 부모를 이름만이라도 그리면 그 존재를 흘린다.
 순서는 서버가 준 순서(common 먼저, 부모 다음 자식, 같은 부모 아래는 이름순)를 그대로 따른다.
 *********************************************************/

import {
  ChevronDown,
  ChevronRight,
  Folder,
  MessageCircle,
  Plus,
} from 'lucide-react';
import Link from 'next/link';
import { type FormEvent, useRef, useState } from 'react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

import type { CollabThreadSummary, Workspace } from '@/lib/api/collab';
import { resolveChatError } from '@/lib/api/errors';

export interface WorkspaceTreeNode {
  workspace: Workspace;
  threads: CollabThreadSummary[];
  children: WorkspaceTreeNode[];
}

/** 예시 화면처럼 폴더마다 색을 달리한다. 의미는 없고 눈으로 가르기 쉽게 하는 것뿐이다. */
const FOLDER_COLORS = [
  'text-blue-400',
  'text-yellow-400',
  'text-green-400',
  'text-cyan-400',
  'text-purple-400',
  'text-orange-400',
];

/**
 * 워크스페이스 목록과 방 목록으로 트리를 짠다. 트리에 들어가지 못한 방(워크스페이스가 없거나 목록에 없는
 * 워크스페이스의 방)은 unplaced로 따로 돌려준다 — 서버가 이미 볼 수 있는 방만 주므로 숨기지 않는다.
 */
export function buildWorkspaceTree(
  workspaces: Workspace[],
  threads: CollabThreadSummary[],
): { roots: WorkspaceTreeNode[]; unplaced: CollabThreadSummary[] } {
  const nodes = new Map<string, WorkspaceTreeNode>();
  for (const workspace of workspaces) {
    nodes.set(workspace.id, { workspace, threads: [], children: [] });
  }
  const roots: WorkspaceTreeNode[] = [];
  for (const workspace of workspaces) {
    const node = nodes.get(workspace.id) as WorkspaceTreeNode;
    const parent = workspace.parentId
      ? nodes.get(workspace.parentId)
      : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  const unplaced: CollabThreadSummary[] = [];
  for (const thread of threads) {
    const node = thread.workspaceId ? nodes.get(thread.workspaceId) : undefined;
    if (node) node.threads.push(thread);
    else unplaced.push(thread);
  }
  return { roots, unplaced };
}

interface WorkspaceTreeProps {
  roots: WorkspaceTreeNode[];
  /** 그 워크스페이스에 방을 만든다. 성공하면 호출부가 방으로 옮긴다. 실패하면 예외를 던진다. */
  onCreate: (
    title: string,
    workspaceId: string,
    idempotencyKey: string,
  ) => Promise<void>;
}

export function WorkspaceTree({ roots, onCreate }: WorkspaceTreeProps) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const colorOf = new Map<string, string>();
  let index = 0;
  const assignColors = (list: WorkspaceTreeNode[]) => {
    for (const node of list) {
      colorOf.set(
        node.workspace.id,
        FOLDER_COLORS[index++ % FOLDER_COLORS.length],
      );
      assignColors(node.children);
    }
  };
  assignColors(roots);

  function toggle(id: string) {
    setCollapsed((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function renderNode(node: WorkspaceTreeNode) {
    const id = node.workspace.id;
    const open = !collapsed.has(id);
    return (
      <li key={id}>
        <button
          aria-expanded={open}
          className="flex w-full items-center gap-1.5 rounded-md px-1 py-1 text-left text-sm font-medium hover:bg-muted"
          onClick={() => toggle(id)}
          type="button"
        >
          {open ? (
            <ChevronDown className="size-4 shrink-0 text-muted-foreground" />
          ) : (
            <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
          )}
          <Folder className={`size-4 shrink-0 ${colorOf.get(id)}`} />
          <span>{node.workspace.name}</span>
        </button>
        {open && (
          <ul className="ml-3 flex flex-col border-l pl-3">
            {node.threads.map((thread) => (
              <li key={thread.id}>
                <Link
                  className="flex items-center gap-2 rounded-md px-1 py-1 text-sm hover:bg-muted"
                  href={`/collab/${thread.id}`}
                  title={`참여자 ${thread.participants.join(', ')}`}
                >
                  <MessageCircle className="size-4 shrink-0 text-muted-foreground" />
                  <span className="truncate">{thread.title}</span>
                </Link>
              </li>
            ))}
            <li>
              <NewThreadRow
                onCreate={(title, key) => onCreate(title, id, key)}
                workspaceName={node.workspace.name}
              />
            </li>
            {node.children.map(renderNode)}
          </ul>
        )}
      </li>
    );
  }

  return <ul className="flex flex-col gap-1">{roots.map(renderNode)}</ul>;
}

/** 폴더 맨 아래 "새 방". 누르면 그 자리에서 제목을 받는다. */
function NewThreadRow({
  workspaceName,
  onCreate,
}: {
  workspaceName: string;
  onCreate: (title: string, idempotencyKey: string) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 재시도 사이에는 같은 키를 쓴다(이슈 #149). 제목을 고치면 새 시도라 키를 버린다. */
  const idempotencyKeyRef = useRef<string | null>(null);

  if (!editing) {
    return (
      <button
        className="flex items-center gap-2 rounded-md px-1 py-1 text-sm text-muted-foreground hover:bg-muted hover:text-foreground"
        onClick={() => setEditing(true)}
        type="button"
      >
        <Plus className="size-4 shrink-0" />새 방
      </button>
    );
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (title.trim() === '') {
      setError('방 제목을 입력해 주세요.');
      return;
    }
    setCreating(true);
    setError(null);
    idempotencyKeyRef.current ??= crypto.randomUUID();
    try {
      await onCreate(title, idempotencyKeyRef.current);
    } catch (failure) {
      setError(
        resolveChatError(failure instanceof Error ? failure : new Error())
          .message,
      );
    } finally {
      setCreating(false);
    }
  }

  return (
    <form className="flex max-w-md flex-col gap-1 py-1" onSubmit={submit}>
      <div className="flex gap-2">
        <Input
          aria-label={`${workspaceName}에 만들 방 제목`}
          autoFocus
          className="h-8"
          disabled={creating}
          maxLength={255}
          onChange={(event) => {
            setTitle(event.target.value);
            idempotencyKeyRef.current = null;
          }}
          onKeyDown={(event) => {
            if (event.key === 'Escape') setEditing(false);
          }}
          placeholder="새 방 제목"
          value={title}
        />
        <Button disabled={creating} size="sm" type="submit">
          {creating ? '만드는 중…' : '만들기'}
        </Button>
        <Button
          disabled={creating}
          onClick={() => {
            setEditing(false);
            setTitle('');
            setError(null);
          }}
          size="sm"
          type="button"
          variant="ghost"
        >
          취소
        </Button>
      </div>
      {error && (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
