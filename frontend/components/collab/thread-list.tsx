'use client';

/********************************************************
 파일명 : thread-list.tsx (components/collab)
 설 명 : 협업 채널 목록(이슈 #19 완료 기준 ①의 앞부분). 어떤 방을 보여줄지는 서버가 이미
 걸러서 내려주므로 여기서는 표시만 한다 — 클라이언트 필터링을 넣으면 그 계약이 흐려진다.

 방은 워크스페이스에 놓인다. 볼 수 있는 워크스페이스가 있으면 트리(workspace-tree.tsx)로 보여주고, 방은
 폴더마다 붙은 "새 방"으로 그 워크스페이스에 만든다. 워크스페이스가 하나도 없으면(권한 기능이 꺼진 배포)
 예전처럼 위쪽 입력칸에서 제목만으로 만들고 평평한 목록으로 보여준다.

 서버 컴포넌트가 아니라 클라이언트에서 조회하는 이유는 목업 모드 때문이다. 목업에서는 경로가
 상대주소라 Node의 fetch가 파싱하지 못한다(lib/api/collab.ts 주석). 재인증은 authFetch가
 알아서 처리하므로 여기서는 "못 불러왔다"만 알리면 된다.
 *********************************************************/

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type FormEvent, useEffect, useRef, useState } from 'react';

import { SidebarToggle } from '@/components/sidebar-toggle';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { buildWorkspaceTree, WorkspaceTree } from './workspace-tree';

import {
  createCollabThread,
  type CollabThreadSummary,
  fetchCollabThreads,
  fetchWorkspaces,
  type Workspace,
} from '@/lib/api/collab';
import { resolveChatError } from '@/lib/api/errors';

/** 방 하나를 목록 칸으로 그린다. 트리 밖(워크스페이스가 없는 방)과 평평한 목록이 같이 쓴다. */
function ThreadCard({ thread }: { thread: CollabThreadSummary }) {
  return (
    <li>
      <Link
        href={`/collab/${thread.id}`}
        className="flex flex-col gap-1 rounded-lg border px-4 py-3 hover:bg-muted"
      >
        <span className="text-sm font-medium">{thread.title}</span>
        <span className="text-xs text-muted-foreground">
          참여자 {thread.participants.join(', ')}
        </span>
      </Link>
    </li>
  );
}

export function ThreadList() {
  const router = useRouter();
  const [threads, setThreads] = useState<CollabThreadSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [title, setTitle] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  /** 재시도 사이에는 같은 키를 재사용해야 서버가 같은 시도로 본다(이슈 #149) — 매번 새로
   * 생성하면 idempotency가 무의미해진다. 제목을 고치면 다른 시도로 보고 초기화한다. 트리의 "새 방"은
   * 폴더마다 자기 키를 따로 든다(workspace-tree.tsx). */
  const idempotencyKeyRef = useRef<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchCollabThreads()
      .then((list) => {
        if (alive) setThreads(list);
      })
      .catch(() => {
        if (alive) setFailed(true);
      });
    // 못 불러오면 트리 없이 예전 화면으로 둔다. 권한 기능이 켜진 배포라면 만들 때 서버가 거절하고 그 문구가 뜬다.
    fetchWorkspaces()
      .then((list) => {
        if (alive) setWorkspaces(list);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  async function createThread(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (title.trim() === '') {
      setCreateError('방 제목을 입력해 주세요.');
      return;
    }
    setCreating(true);
    setCreateError(null);
    idempotencyKeyRef.current ??= crypto.randomUUID();
    try {
      const created = await createCollabThread(
        title,
        undefined,
        idempotencyKeyRef.current,
      );
      router.push(`/collab/${created.id}`);
    } catch (error) {
      setCreateError(
        resolveChatError(error instanceof Error ? error : new Error()).message,
      );
    } finally {
      setCreating(false);
    }
  }

  /** 트리의 "새 방". 실패는 그 폴더의 입력칸이 보여주도록 그대로 던진다. */
  async function createInWorkspace(
    newTitle: string,
    workspaceId: string,
    idempotencyKey: string,
  ) {
    const created = await createCollabThread(
      newTitle,
      workspaceId,
      idempotencyKey,
    );
    router.push(`/collab/${created.id}`);
  }

  const tree =
    threads && workspaces.length > 0
      ? buildWorkspaceTree(workspaces, threads)
      : null;

  return (
    <div className="flex h-dvh flex-col">
      {/* 평평한 목록 본문은 가운데 정렬이지만 헤더는 그러지 않는다 — 사이드바 토글이 1:1 채팅
          (chat-header.tsx)·협업방과 같은 자리에 있어야 하기 때문이다. 트리는 파일 탐색기처럼
          왼쪽에 붙인다 — 가운데에 두면 들여쓰기 기준선이 화면 중간에 떠 어색하다. */}
      <header className="flex sticky top-0 items-center gap-2 border-b bg-background px-2 py-1.5">
        <SidebarToggle />
        <h1 className="text-sm font-semibold">협업 채널</h1>
      </header>

      <div
        className={`flex w-full flex-1 flex-col gap-4 overflow-y-auto ${
          workspaces.length > 0 ? 'px-4 py-4' : 'mx-auto max-w-2xl p-6'
        }`}
      >
        {workspaces.length === 0 && (
          <>
            <form
              className="flex flex-col gap-2 sm:flex-row"
              onSubmit={createThread}
            >
              <Input
                aria-describedby={
                  createError ? 'create-thread-error' : undefined
                }
                disabled={creating}
                maxLength={255}
                onChange={(event) => {
                  setTitle(event.target.value);
                  // 제목을 고쳤다는 것은 이전 시도의 재시도가 아니라 새 시도라는 뜻이다 — 이전 키를
                  // 그대로 쓰면 이번 title이 그 키의 최초 title과 달라 서버가 충돌로 거절한다.
                  idempotencyKeyRef.current = null;
                }}
                placeholder="새 협업방 제목"
                value={title}
              />
              <Button disabled={creating} type="submit">
                {creating ? '만드는 중…' : '만들기'}
              </Button>
            </form>

            {createError && (
              <p
                className="text-sm text-destructive"
                id="create-thread-error"
                role="alert"
              >
                {createError}
              </p>
            )}
          </>
        )}

        {failed && (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm">
            목록을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        )}

        {!failed && threads === null && (
          <p className="text-sm text-muted-foreground">불러오는 중…</p>
        )}

        {tree && (
          <>
            <WorkspaceTree onCreate={createInWorkspace} roots={tree.roots} />
            {tree.unplaced.length > 0 && (
              <section className="flex flex-col gap-2">
                <h2 className="text-xs font-semibold text-muted-foreground">
                  워크스페이스 없는 방
                </h2>
                <ul className="flex flex-col gap-2">
                  {tree.unplaced.map((thread) => (
                    <ThreadCard key={thread.id} thread={thread} />
                  ))}
                </ul>
              </section>
            )}
          </>
        )}

        {!tree && threads?.length === 0 && (
          <p className="text-sm text-muted-foreground">
            참여 중인 협업 채널이 없습니다.
          </p>
        )}

        {!tree && threads && threads.length > 0 && (
          <ul className="flex flex-col gap-2">
            {threads.map((thread) => (
              <ThreadCard key={thread.id} thread={thread} />
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
