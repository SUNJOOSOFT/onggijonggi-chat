'use client';

/********************************************************
 파일명 : thread-list.tsx (components/collab)
 설 명 : 협업 채널 목록(이슈 #19 완료 기준 ①의 앞부분). 어떤 방을 보여줄지는 서버가 이미
 걸러서 내려주므로 여기서는 표시만 한다 — 클라이언트 필터링을 넣으면 그 계약이 흐려진다.

 서버 컴포넌트가 아니라 클라이언트에서 조회하는 이유는 목업 모드 때문이다. 목업에서는 경로가
 상대주소라 Node의 fetch가 파싱하지 못한다(lib/api/collab.ts 주석). 재인증은 authFetch가
 알아서 처리하므로 여기서는 "못 불러왔다"만 알리면 된다.
 *********************************************************/

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type FormEvent, useEffect, useState } from 'react';

import { SidebarToggle } from '@/components/sidebar-toggle';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

import {
  createCollabThread,
  type CollabThreadSummary,
  fetchCollabThreads,
} from '@/lib/api/collab';
import { resolveChatError } from '@/lib/api/errors';

export function ThreadList() {
  const router = useRouter();
  const [threads, setThreads] = useState<CollabThreadSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [title, setTitle] = useState('');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchCollabThreads()
      .then((list) => {
        if (alive) setThreads(list);
      })
      .catch(() => {
        if (alive) setFailed(true);
      });
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
    try {
      const created = await createCollabThread(title);
      router.push(`/collab/${created.id}`);
    } catch (error) {
      setCreateError(
        resolveChatError(error instanceof Error ? error : new Error()).message,
      );
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="flex h-dvh flex-col">
      {/* 목록 본문은 가운데 정렬이지만 헤더는 그러지 않는다 — 사이드바 토글이 1:1 채팅
          (chat-header.tsx)·협업방과 같은 자리에 있어야 하기 때문이다. */}
      <header className="flex sticky top-0 items-center gap-2 border-b bg-background px-2 py-1.5">
        <SidebarToggle />
        <h1 className="text-sm font-semibold">협업 채널</h1>
      </header>

      <div className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-4 overflow-y-auto p-6">
        <form
          className="flex flex-col gap-2 sm:flex-row"
          onSubmit={createThread}
        >
          <Input
            aria-describedby={createError ? 'create-thread-error' : undefined}
            disabled={creating}
            maxLength={255}
            onChange={(event) => setTitle(event.target.value)}
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

        {failed && (
          <p className="rounded-lg bg-muted px-4 py-3 text-sm">
            목록을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        )}

        {!failed && threads === null && (
          <p className="text-sm text-muted-foreground">불러오는 중…</p>
        )}

        {threads?.length === 0 && (
          <p className="text-sm text-muted-foreground">
            참여 중인 협업 채널이 없습니다.
          </p>
        )}

        <ul className="flex flex-col gap-2">
          {threads?.map((thread) => (
            <li key={thread.id}>
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
          ))}
        </ul>
      </div>
    </div>
  );
}
