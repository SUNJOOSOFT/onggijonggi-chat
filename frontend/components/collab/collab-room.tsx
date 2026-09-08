'use client';

/********************************************************
 파일명 : collab-room.tsx (components/collab)
 설 명 : 협업방 화면(이슈 #19) — 참여자·메시지 목록·입력창, 그리고 방에 들어가지 못했을 때의 처리.

 메시지 목록을 1:1 채팅의 messages.tsx로 그리지 않은 이유는 그쪽이 AI SDK Message(role만 있고
 보낸 사람이 없다)를 전제하기 때문이다. 협업방은 여러 사람의 메시지를 이름과 함께 보여줘야 한다.
 스크롤 붙임(use-scroll-to-bottom)과 마크다운 렌더는 그대로 재사용한다.

 참여자 목록 사이드바는 접속자를 userId 앞자리로만 보여준다(#26). 표시 이름을 얻는 길은
 열렸지만(#128 — Keycloak에서 subject로 조회한다) 프레임이 싣는 식별자는 여전히 서버의
 app_user.id라, REST가 쓰는 subject와 맞지 않아 그 조회에 넣을 키가 없다. 식별자를 맞추는
 것은 #130이고, 그때 shortUserId 한 곳만 이름 조회로 바꾸면 된다.

 메시지 시각과 AI 라벨(@FIN 같은 에이전트 구분)은 기획 시안에 있으나 그리지 않는다 — 프레임
 계약(#8)에 그 필드가 없어 서버가 보내주지 않는다. 계약이 넓어지면 여기에 붙일 자리다.

 방 접근 거부는 두 갈래로 도착한다. 서버가 error 프레임(FORBIDDEN)으로 알려주면 사유가 분명해
 그대로 보여주고, 핸드셰이크에서 거부하면 브라우저가 이유를 넘겨주지 않아(#4) 서버 장애와
 구분되지 않는다 — 뒤쪽은 단정하지 않는 문구로 안내한다. 어느 방식이 될지는 #22에 미결이다.
 *********************************************************/

import { useEffect, useState } from 'react';

import { CitationsPanel } from '@/components/citations-panel';
import { Markdown } from '@/components/markdown';
import { SidebarToggle } from '@/components/sidebar-toggle';
import { useScrollToBottom } from '@/components/use-scroll-to-bottom';
import { fetchCollabThreads } from '@/lib/api/collab';
import type {
  CollabMessage,
  CollabPresenceNotice,
} from '@/lib/collab/room-state';
import { isForbidden, isPresenceNotice } from '@/lib/collab/room-state';
import {
  type RoomConnection,
  useCollabRoom,
} from '@/lib/collab/use-collab-room';
import { CollabInput } from './collab-input';

const CONNECTION_LABEL: Record<RoomConnection, string> = {
  connecting: '연결 중…',
  open: '연결됨',
  reconnecting: '다시 연결하는 중…',
  stalled: '연결하지 못했습니다',
};

/**
 * 사람을 가리키는 표시. 지금 손에 있는 것이 userId(UUID)뿐이라 앞부분만 잘라 쓴다 —
 * 프레임이 subject를 싣게 되면(#130) 이 함수 하나만 이름 조회로 바꾸면 된다.
 */
function shortUserId(userId: string): string {
  return userId.slice(0, 8);
}

/** 입퇴장 시스템 라인(#111). 말풍선도 작성자 머리글도 없이 흐름 가운데에 옅게 남긴다 —
 * 사람이 한 말이 아니기 때문이다. */
function PresenceRow({ notice }: { notice: CollabPresenceNotice }) {
  return (
    <p
      title={notice.userId}
      className="px-3 py-1 text-center text-xs text-muted-foreground"
    >
      {shortUserId(notice.userId)}님이{' '}
      {notice.event === 'join' ? '입장했습니다' : '퇴장했습니다'}
    </p>
  );
}

/** 사람 메시지는 보낸 사람 이름을, AI 답변은 "AI"를 머리에 달고 배경으로 구분한다. */
function MessageRow({ message }: { message: CollabMessage }) {
  const isAi = message.from === null;
  return (
    <div
      className={`flex flex-col gap-1 ${
        isAi ? 'rounded-lg bg-muted/60 px-3 py-2' : 'px-3 py-2'
      }`}
    >
      <span className="text-xs font-medium text-muted-foreground">
        {isAi ? 'AI' : message.from}
      </span>
      {isAi ? (
        <>
          {message.content !== '' && <Markdown>{message.content}</Markdown>}
          {(message.citations.length > 0 ||
            message.restrictedResultsOmitted) && (
            <CitationsPanel
              state={{
                status: 'success',
                citations: message.citations,
                restrictedResultsOmitted: message.restrictedResultsOmitted,
              }}
            />
          )}
        </>
      ) : (
        <p className="whitespace-pre-wrap text-sm">{message.content}</p>
      )}
    </div>
  );
}

/** 방을 그릴 수 없을 때 화면 전체를 대신한다. */
function RoomBlocked({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="flex h-dvh flex-col items-center justify-center gap-2 p-8 text-center">
      <h1 className="text-lg font-semibold">{title}</h1>
      <p className="max-w-md text-sm text-muted-foreground">{detail}</p>
      <a className="mt-2 text-sm underline" href="/collab">
        협업 채널 목록으로
      </a>
    </div>
  );
}

/** 방 제목만 얻으려고 목록을 다시 부른다 — 단건 조회 경로가 아직 없어서다(#14·#16). */
function useThreadTitle(threadId: string): string {
  const [title, setTitle] = useState('협업 채널');

  useEffect(() => {
    let alive = true;
    fetchCollabThreads()
      .then((threads) => {
        const found = threads.find((thread) => thread.id === threadId);
        if (alive && found) setTitle(found.title);
      })
      // 제목은 부가 정보다 — 못 얻었다고 방을 못 열 이유는 없다.
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [threadId]);

  return title;
}

export function CollabRoom({ threadId }: { threadId: string }) {
  const { state, connection, send, dismissError } = useCollabRoom(threadId);
  const [containerRef, endRef] = useScrollToBottom<HTMLDivElement>();
  const title = useThreadTitle(threadId);

  if (isForbidden(state.error)) {
    return (
      <RoomBlocked
        title="이 방에 들어갈 수 없습니다"
        detail={state.error?.message ?? '접근 권한이 없습니다.'}
      />
    );
  }

  if (connection === 'stalled') {
    return (
      <RoomBlocked
        title="방에 연결하지 못했습니다"
        detail="접근 권한이 없거나 서버에 닿지 못하고 있습니다. 브라우저는 이 둘을 구분할 수 없어 계속 다시 시도하는 중입니다."
      />
    );
  }

  return (
    <div className="flex h-dvh flex-col">
      {/* 패딩·정렬을 chat-header.tsx와 같은 값으로 둔다 — 1:1 채팅과 오갈 때 사이드바 토글이
          같은 자리에 있어야 한다. */}
      <header className="flex sticky top-0 items-center gap-2 border-b bg-background px-2 py-1.5">
        <SidebarToggle />
        <h1 className="text-sm font-semibold">{title}</h1>
        <span className="ml-auto pr-2 text-xs text-muted-foreground">
          {CONNECTION_LABEL[connection]}
        </span>
      </header>

      <div className="flex min-h-0 flex-1">
        <main className="flex min-h-0 flex-1 flex-col">
          {/* 접근 거부가 아닌 오류는 방을 닫을 이유가 아니라 위에 얹어 알린다. */}
          {state.error && !isForbidden(state.error) && (
            <div
              role="alert"
              className="flex items-center gap-3 border-b bg-muted px-4 py-2 text-xs"
            >
              <span className="flex-1">{state.error.message}</span>
              <button
                type="button"
                className="shrink-0 underline underline-offset-2"
                onClick={dismissError}
              >
                닫기
              </button>
            </div>
          )}

          <div
            ref={containerRef}
            className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-4"
          >
            {state.messages.length === 0 && (
              <p className="text-sm text-muted-foreground">
                아직 메시지가 없습니다.
              </p>
            )}
            {state.messages.map((entry) =>
              isPresenceNotice(entry) ? (
                <PresenceRow key={entry.id} notice={entry} />
              ) : (
                <MessageRow key={entry.id} message={entry} />
              ),
            )}
            <div ref={endRef} className="min-h-6 shrink-0" />
          </div>

          <div className="border-t p-4">
            <CollabInput canSend={connection === 'open'} onSend={send} />
          </div>
        </main>

        {/* 접속자 목록(#26) — "지금 방에 붙어 있는 사람"이다. 방에 들어올 자격이 있는
            참여자 명단(#23)과는 다른 목록이라 같은 자리에 겹쳐 그리지 않는다.
            보여줄 수 있는 것이 userId(UUID)뿐이라 잘라서 그리고 전체는 title로 남긴다 —
            사람이 읽을 이름으로 바꾸는 것은 #130이다(파일 상단 설명 참고). */}
        <aside className="hidden w-56 shrink-0 flex-col gap-2 border-l p-4 sm:flex">
          <h2 className="text-xs font-medium text-muted-foreground">
            참여자 {state.participants.length}
          </h2>
          <ul className="flex flex-col gap-1">
            {state.participants.map((participant) => (
              <li key={participant} title={participant} className="text-sm">
                {shortUserId(participant)}
              </li>
            ))}
          </ul>
        </aside>
      </div>
    </div>
  );
}
